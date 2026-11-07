"""mod-server consumes the versioned common artifact (blockfield-server#318).

The mod Release workflow publishes blockfield-common-<version>.jar with
SHA256SUMS; this test resolves those published coordinates + checksum the
way a consumer does: a fixture release layout (offline), the workflow
contract (offline) and the live release (skipped until the artifact exists).
"""

import hashlib
import re
import threading
import unittest
import urllib.error
import urllib.request
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[3]
REPO = "https://github.com/Blockfield/blockfield-mod"


def gradle_property(name):
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    raise AssertionError(f"gradle.properties lacks {name}")


def coordinates():
    version = (
        f"{gradle_property('mod_version')}+mc{gradle_property('minecraft_version')}"
    )
    asset = f"blockfield-common-{version}.jar"
    tag = f"v{gradle_property('mod_version')}"
    return {
        "group": gradle_property("mod_group_id"),
        "artifact": "blockfield-common",
        "version": version,
        "tag": tag,
        "asset": asset,
        "base_url": f"{REPO}/releases/download/{tag}/",
    }


def fetch(url, timeout=30):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return response.read()


def resolve_and_verify(base_url, asset, timeout=30):
    """Download SHA256SUMS, then the asset, and verify its checksum."""
    sums = fetch(base_url + "SHA256SUMS", timeout=timeout).decode("utf-8")
    expected = None
    for line in sums.splitlines():
        digest, _, name = line.partition("  ")
        if name.strip() == asset:
            expected = digest.strip()
    if expected is None:
        raise AssertionError(f"{asset} missing from {base_url}SHA256SUMS")
    actual = hashlib.sha256(fetch(base_url + asset, timeout=timeout)).hexdigest()
    if actual != expected:
        raise AssertionError(f"{asset}: sha256 {actual}, expected {expected}")
    return actual


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


class ConsumerResolvesArtifact(unittest.TestCase):
    def serve(self, files):
        tmp = TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        for name, payload in files.items():
            (Path(tmp.name) / name).write_bytes(payload)
        server = ThreadingHTTPServer(
            ("127.0.0.1", 0), partial(QuietHandler, directory=tmp.name)
        )
        self.addCleanup(server.server_close)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.shutdown)
        return f"http://127.0.0.1:{server.server_address[1]}/"

    def test_fixture_release_layout_verifies(self):
        asset = coordinates()["asset"]
        payload = b"published common jar bytes"
        digest = hashlib.sha256(payload).hexdigest()
        sums = f"{digest}  {asset}\n".encode()
        base = self.serve({asset: payload, "SHA256SUMS": sums})
        self.assertEqual(resolve_and_verify(base, asset), digest)

    def test_fixture_tampered_jar_rejected(self):
        asset = coordinates()["asset"]
        digest = hashlib.sha256(b"published common jar bytes").hexdigest()
        sums = f"{digest}  {asset}\n".encode()
        base = self.serve({asset: b"tampered bytes", "SHA256SUMS": sums})
        with self.assertRaises(AssertionError) as ctx:
            resolve_and_verify(base, asset)
        self.assertIn("expected", str(ctx.exception))

    def test_live_release(self):
        coords = coordinates()
        try:
            resolve_and_verify(coords["base_url"], coords["asset"])
        except (urllib.error.URLError, OSError) as error:
            self.skipTest(f"published artifact not reachable: {error}")
        except AssertionError as error:
            if "missing from" in str(error):
                self.skipTest(f"release {coords['tag']} predates the common artifact")
            raise


class WorkflowPublishesCommonArtifact(unittest.TestCase):
    def test_release_workflow_builds_and_uploads_common_jar(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn(":common:remapJar", workflow)
        self.assertIn("blockfield-common-", workflow)
        self.assertIn("SHA256SUMS", workflow)

    def _steps(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        return re.split(r"(?m)^      - ", workflow)[1:]

    def _publish_step(self):
        publish = [s for s in self._steps() if s.startswith("name: Publish release")]
        self.assertEqual(len(publish), 1)
        return publish[0]

    def test_publish_check_matches_only_remapped_jar(self):
        body = self._publish_step()
        # The loose "any jar exists" check also accepted -dev.jar, which the
        # next rm deletes (release with only the client jar, immutable tag).
        self.assertNotIn("common_built", body)
        # A lone -dev.jar must fail the step instead of publishing.
        self.assertIn("blockfield-common-", body)
        self.assertIn("exit 1", body)

    def test_build_runs_without_write_token(self):
        builders = [s for s in self._steps() if ":common:remapJar" in s]
        self.assertTrue(builders)
        for step in builders:
            self.assertNotIn("GH_TOKEN", step)
            self.assertNotIn("github.token", step)
        # Publish keeps the contents:write token, so it must only upload.
        self.assertNotIn("gradle", self._publish_step().lower())


if __name__ == "__main__":
    unittest.main()
