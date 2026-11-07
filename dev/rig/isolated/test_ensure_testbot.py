import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import launch  # noqa: E402 — import the sibling after adding its directory.


def layout(root):
    rig = Path(root) / "dev" / "rig"
    upstream = rig / "testbot" / "upstream"
    libs = upstream / "build" / "libs"
    libs.mkdir(parents=True, exist_ok=True)
    fetch = rig / "testbot" / "fetch-testbot.sh"
    fetch.touch(exist_ok=True)
    return rig, upstream, libs, fetch


class EnsureTestbot(unittest.TestCase):
    def run_ensure(self, root):
        rig, _, _, _ = layout(root)
        with (
            patch.object(launch, "HERE", rig / "isolated"),
            patch.object(launch.subprocess, "run") as run,
        ):
            launch.ensure_testbot()
            return run

    def test_fresh_jar_skips_rebuild(self):
        with tempfile.TemporaryDirectory() as root:
            _, upstream, libs, fetch = layout(root)
            (upstream / ".upstream-sha").write_text("pin\n")
            jar = libs / "bftestbot-0.1.0.jar"
            jar.write_bytes(b"jar")
            marker = (upstream / ".upstream-sha").stat().st_mtime
            os.utime(jar, (marker + 10, marker + 10))
            run = self.run_ensure(root)
            run.assert_called_once_with(["bash", str(fetch)], check=True)

    def test_stale_jar_triggers_rebuild(self):
        with tempfile.TemporaryDirectory() as root:
            _, upstream, libs, _ = layout(root)
            jar = libs / "bftestbot-0.1.0.jar"
            jar.write_bytes(b"stale")
            time.sleep(0.02)
            (upstream / ".upstream-sha").write_text("pin\n")
            run = self.run_ensure(root)
            self.assertEqual(run.call_count, 2)
            self.assertEqual(run.call_args[0][0][0], "just")
            self.assertFalse(jar.exists())

    def test_missing_jar_triggers_rebuild(self):
        with tempfile.TemporaryDirectory() as root:
            _, upstream, _, _ = layout(root)
            (upstream / ".upstream-sha").write_text("pin\n")
            run = self.run_ensure(root)
            self.assertEqual(run.call_count, 2)
            self.assertEqual(run.call_args[0][0][0], "just")


if __name__ == "__main__":
    unittest.main()
