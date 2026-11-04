import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch, Mock
from zipfile import ZipFile

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import launch  # noqa: E402 — import siblings after adding their directory.
import session  # noqa: E402


class PathsAndJars(unittest.TestCase):
    def test_new_output(self):
        with tempfile.TemporaryDirectory() as root:
            result = launch.new_directory(Path(root) / "reports")
            self.assertEqual(result.stat().st_mode & 0o777, 0o700)
            (result / "sentinel").write_text("keep")
            with self.assertRaises(ValueError):
                launch.new_directory(result)
            self.assertEqual((result / "sentinel").read_text(), "keep")

    def test_symlink_output_or_ancestor(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            (root / "real").mkdir()
            (root / "alias").symlink_to(root / "real")
            for target in (root / "alias", root / "alias/new"):
                with self.assertRaises(ValueError):
                    launch.new_directory(target)
            self.assertFalse((root / "real/new").exists())

    def test_missing_parent_not_created(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(FileNotFoundError):
                launch.new_directory(Path(root) / "missing/new")

    def test_jar_identity_and_side(self):
        with tempfile.TemporaryDirectory() as root:
            p = Path(root) / "test.jar"
            with ZipFile(p, "w") as z:
                z.writestr(
                    "fabric.mod.json",
                    json.dumps({"id": "blockfield", "environment": "server"}),
                )
            self.assertEqual(
                launch.mod_jar(p, "blockfield", "server")["id"], "blockfield"
            )
            for mod, side in [("blockfield", "client"), ("bftestbot", "server")]:
                with self.assertRaises(ValueError):
                    launch.mod_jar(p, mod, side)
            link = Path(root) / "link.jar"
            link.symlink_to(p)
            with self.assertRaises(ValueError):
                launch.mod_jar(link, "blockfield", "server")

    def test_ambiguous_or_missing_jar(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(ValueError):
                launch.select_jar(Path(root), "blockfield-", "blockfield", "server")
            for name in ("blockfield-one.jar", "blockfield-two.jar"):
                (Path(root) / name).touch()
            with self.assertRaises(ValueError):
                launch.select_jar(Path(root), "blockfield-", "blockfield", "server")


class Isolation(unittest.TestCase):
    def test_restricted_arguments(self):
        args = launch.container_args(
            "sha256:" + "a" * 64, Path("/tmp/report"), "b" * 32, "client"
        )
        self.assertEqual(args[args.index("--network") + 1], "none")
        self.assertIn("--read-only", args)
        self.assertIn("--cap-drop", args)
        self.assertNotIn("--privileged", args)
        self.assertNotIn("--publish", args)
        self.assertNotIn("--network=host", args)
        self.assertEqual(args.count("--mount"), 1)
        self.assertNotIn("/var/run/docker.sock", " ".join(args))

    def test_reject_tag_malformed_identity_and_mount(self):
        for image, path, token, mode in [
            ("bf:latest", "/tmp/x", "a" * 32, "server"),
            ("sha256:" + "a" * 64, "/tmp/x,ro", "a" * 32, "client"),
            ("sha256:" + "a" * 64, "/tmp/x", "wrong", "server"),
            ("sha256:" + "a" * 64, "/tmp/x", "a" * 32, "shell"),
        ]:
            with self.assertRaises(ValueError):
                launch.container_args(image, Path(path), token, mode)

    def test_actual_inspect_is_verified(self):
        info = {
            "HostConfig": {"NetworkMode": "none", "ReadonlyRootfs": True},
            "Mounts": [{"Type": "bind", "Source": "/tmp/out", "Destination": "/out"}],
        }
        self.assertTrue(launch.verify_isolation(info, Path("/tmp/out")))
        for key, value in [
            ("NetworkMode", "host"),
            ("Privileged", True),
            ("ReadonlyRootfs", False),
            ("PortBindings", {"25565/tcp": []}),
            ("PidMode", "host"),
            ("IpcMode", "host"),
            ("Devices", ["device"]),
        ]:
            changed = json.loads(json.dumps(info))
            changed["HostConfig"][key] = value
            with self.assertRaises(ValueError):
                launch.verify_isolation(changed, Path("/tmp/out"))
        info["Mounts"].append(
            {"Type": "bind", "Source": "/home", "Destination": "/home"}
        )
        with self.assertRaises(ValueError):
            launch.verify_isolation(info, Path("/tmp/out"))

    def test_cleanup_identity_must_match(self):
        cid, token = "a" * 64, "b" * 32
        with patch.object(
            launch,
            "execute",
            return_value=json.dumps([{"Id": cid, "Config": {"Labels": {}}}]),
        ):
            with self.assertRaises(ValueError):
                launch.owned_info(cid, token)
        with patch.object(launch, "execute") as call:
            with self.assertRaises(ValueError):
                launch.owned_info("not-id", token)
            call.assert_not_called()

    def test_eula_and_arguments_before_mutation(self):
        with patch.object(launch, "new_directory") as mkdir:
            with self.assertRaises(ValueError):
                launch.run("sha256:" + "a" * 64, Path("/tmp/x"), "client", False)
            with self.assertRaises(ValueError):
                launch.run("latest", Path("/tmp/x"), "client", True)
            mkdir.assert_not_called()


class ProtocolAndLifecycle(unittest.TestCase):
    def test_partial_reads_and_eof(self):
        stream = Mock()
        stream.recv.side_effect = [b"ab", b"c"]
        self.assertEqual(session.read_exact(stream, 3), b"abc")
        stream.recv.side_effect = [b"a", b""]
        with self.assertRaises(EOFError):
            session.read_exact(stream, 2)

    def test_properties_have_no_external_endpoint(self):
        props = session.server_properties("random-token")
        self.assertIn("server-ip=127.0.0.1", props)
        self.assertIn("rcon.password=random-token", props)
        self.assertIn("level-name=world", props)
        self.assertIn("level-type=minecraft:flat", props)
        self.assertNotIn("leaks", props)

    def test_rcon_framing_and_auth(self):
        def packet(number, kind, text):
            data = struct.pack("<ii", number, kind) + text.encode() + b"\0\0"
            return struct.pack("<i", len(data)), data

        fake = Mock()
        fake.__enter__ = Mock(return_value=fake)
        fake.__exit__ = Mock(return_value=False)
        fake.recv.side_effect = [*packet(1, 2, ""), *packet(2, 0, "Blockfield")]
        with patch.object(
            session.socket, "create_connection", return_value=fake
        ) as connect:
            self.assertEqual(session.rcon("bf version", "test"), "Blockfield")
            self.assertEqual(connect.call_args.args[0], ("127.0.0.1", 25575))
        fake.recv.side_effect = [*packet(-1, 2, "")]
        with patch.object(session.socket, "create_connection", return_value=fake):
            with self.assertRaises(ValueError):
                session.rcon("bf version", "bad")

    def test_oversized_rcon(self):
        fake = Mock()
        fake.__enter__ = Mock(return_value=fake)
        fake.__exit__ = Mock(return_value=False)
        fake.recv.side_effect = [struct.pack("<i", 2**30)]
        with patch.object(session.socket, "create_connection", return_value=fake):
            with self.assertRaises(ValueError):
                session.rcon("bf version", "test")

    def test_dead_process_not_ready(self):
        proc = Mock()
        proc.poll.return_value = 1
        with self.assertRaises(RuntimeError):
            session.wait_for(lambda: True, [("server", proc)], 3)

    def test_wait_timeout_not_success(self):
        with self.assertRaises(TimeoutError):
            session.wait_for(lambda: False, [], 0)

    def test_cleanup_owns_only_created_children(self):
        proc = Mock()
        proc.poll.return_value = None
        self.assertEqual(session.stop_children([("server", proc)]), [])
        proc.stdin.write.assert_called_once_with("stop\n")
        proc.terminate.assert_not_called()
        proc.kill.assert_not_called()
        proc = Mock()
        proc.poll.return_value = 0
        session.stop_children([("client", proc)])
        proc.terminate.assert_not_called()

    def test_forced_shutdown_is_reported(self):
        proc = Mock()
        proc.poll.return_value = None
        proc.wait.side_effect = [subprocess.TimeoutExpired("child", 15), 0]
        self.assertEqual(
            session.stop_children([("client", proc)]), ["client: forced shutdown"]
        )
        proc.kill.assert_called_once()


if __name__ == "__main__":
    unittest.main()
