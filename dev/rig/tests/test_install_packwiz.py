import importlib.util
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "installer", Path(__file__).parents[1] / "install-packwiz.py"
)
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class InstallerTests(unittest.TestCase):
    def test_download_is_verified_and_valid_cache_works_offline(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "source.jar"
            source.write_bytes(b"PK-installer")
            manifest = root / "launcher.json"
            pin = {
                "url": source.as_uri(),
                "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            }
            manifest.write_text(json.dumps({"bootstrap": pin, "installer": pin}))
            installer.install(manifest, root / "cache")
            cached = root / "cache/packwiz-installer.jar"
            self.assertEqual(cached.read_bytes(), source.read_bytes())
            source.unlink()
            installer.install(manifest, root / "cache")
            source.write_bytes(b"PK-wrong")
            cached.write_bytes(b"corrupt")
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                installer.install(manifest, root / "cache")
