import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "mod_jars", Path(__file__).resolve().parents[1] / "mod_jars.py"
)
mod_jars = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod_jars)


class ModJarsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.mods = self.root / "mods"
        self.mods.mkdir()
        self.old = self.mods / "blockfield-server-1.0.0.jar"
        self.old.write_bytes(b"old")
        self.source = self.root / "blockfield-server-1.3.0+mc1.21.1.jar"
        self.source.write_bytes(b"new")

    def test_install_uses_incoming_version_and_preserves_other_mods(self):
        client = self.mods / "blockfield-client-1.0.0.jar"
        client.write_bytes(b"client")
        other = self.mods / "another-mod.jar"
        other.write_bytes(b"other")
        installed = mod_jars.install(self.source, self.mods, "server")
        self.assertEqual(installed.name, self.source.name)
        self.assertEqual(installed.read_bytes(), b"new")
        self.assertFalse(self.old.exists())
        self.assertEqual(client.read_bytes(), b"client")
        self.assertEqual(other.read_bytes(), b"other")
        self.assertEqual(mod_jars.deployed(self.mods, "server"), installed)

    def test_same_file_is_safe_and_still_removes_legacy_duplicate(self):
        installed = self.mods / self.source.name
        installed.write_bytes(b"new")
        self.assertEqual(mod_jars.install(installed, self.mods, "server"), installed)
        self.assertEqual(installed.read_bytes(), b"new")
        self.assertFalse(self.old.exists())

    def test_copy_failure_keeps_old_jar_and_existing_same_version(self):
        installed = self.mods / self.source.name
        installed.write_bytes(b"previous build")
        with patch.object(
            mod_jars.shutil, "copyfile", side_effect=OSError("disk full")
        ):
            with self.assertRaises(OSError):
                mod_jars.install(self.source, self.mods, "server")
        self.assertEqual(self.old.read_bytes(), b"old")
        self.assertEqual(installed.read_bytes(), b"previous build")
        self.assertFalse(list(self.mods.glob(".blockfield-install-*")))

    def test_deployed_rejects_duplicates_and_ignores_source_and_dev_jars(self):
        for suffix in ("sources", "dev"):
            (self.mods / f"blockfield-server-1.3.0-{suffix}.jar").write_bytes(b"extra")
        self.assertEqual(mod_jars.deployed(self.mods, "server"), self.old)
        (self.mods / self.source.name).write_bytes(b"new")
        with self.assertRaises(ValueError):
            mod_jars.deployed(self.mods, "server")

    def test_wrong_side_source_never_removes_existing_jar(self):
        with self.assertRaises(ValueError):
            mod_jars.install(self.source, self.mods, "client")
        self.assertEqual(self.old.read_bytes(), b"old")

    def test_prune_keeps_tracked_jars_and_testbot_only(self):
        for name in ("ParCool-bf1.jar", "ParCool-bf5.jar", "bftestbot.jar"):
            (self.mods / name).write_bytes(b"jar")
        (self.root / "packwiz.json").write_text(
            '{"cachedFiles": {"mods/parcool.pw.toml": '
            '{"cachedLocation": "mods/ParCool-bf5.jar"}}}'
        )
        self.assertEqual(
            ["ParCool-bf1.jar", "blockfield-server-1.0.0.jar"],
            mod_jars.prune(self.mods),
        )
        self.assertEqual(
            ["ParCool-bf5.jar", "bftestbot.jar"],
            sorted(p.name for p in self.mods.iterdir()),
        )

    def test_deployed_rejects_empty_directory(self):
        with self.assertRaises(ValueError):
            mod_jars.deployed(self.mods, "client")


if __name__ == "__main__":
    unittest.main()
