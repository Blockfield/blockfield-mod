import hashlib
from pathlib import Path
import sys
import unittest
import uuid
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import launch
import session


class TwoClients(unittest.TestCase):
    def args(self):
        return [
            "java",
            "-Xmx1536M",
            "-Djna.tmpdir=/opt/bf/natives",
            "-Dbf.testbot.port=47777",
            "-cp",
            "libs",
            "Main",
            "--username",
            "RigPlayer",
            "--uuid",
            "old",
            "--gameDir",
            "/work/client",
        ]

    def test_second_identity_and_storage_do_not_alias_first(self):
        before = self.args()
        actual = session.client_command(before, 1)
        self.assertEqual(before, self.args())
        self.assertEqual(actual[actual.index("--username") + 1], "RigOpponent")
        expected = uuid.UUID(
            bytes=hashlib.md5(b"OfflinePlayer:RigOpponent").digest(), version=3
        ).hex
        self.assertEqual(actual[actual.index("--uuid") + 1], expected)
        self.assertEqual(actual[actual.index("--gameDir") + 1], "/work/client2")
        self.assertIn("-Dbf.testbot.port=47778", actual)
        self.assertNotIn("-Dbf.testbot.port=47777", actual)
        self.assertIn("-Duser.home=/tmp/rig-home-2", actual)
        self.assertEqual(actual.count("-Djna.tmpdir=/tmp"), 1)

    def test_first_identity_unchanged(self):
        actual = session.client_command(self.args(), 0)
        self.assertEqual(actual[actual.index("--username") + 1], "RigPlayer")
        self.assertEqual(actual[actual.index("--gameDir") + 1], "/work/client")
        self.assertIn("-Dbf.testbot.port=47777", actual)

    def test_ambiguous_second_arguments_fail(self):
        for key in ("--username", "--uuid", "--gameDir"):
            for args in (
                self.args() + [key, "duplicate"],
                self.args() + [key],
                ["java", "Main"],
            ):
                with self.assertRaises(ValueError):
                    session.client_command(args, 1)

    def test_bot_target_cannot_escape_fixed_slots(self):
        with patch.object(session.socket, "create_connection") as connect:
            for index in (-1, 2, 25565, "1", True, None):
                with self.assertRaises(ValueError):
                    session.bot("state", index)
                with self.assertRaises(ValueError):
                    session.client_command(self.args(), index)
            connect.assert_not_called()

    def test_two_clients_keep_same_network_and_mount_restrictions(self):
        args = launch.container_args(
            "sha256:" + "a" * 64, Path("/tmp/out"), "b" * 32, "pvp"
        )
        self.assertEqual(args[args.index("--memory") + 1], "6g")
        self.assertEqual(args[args.index("--network") + 1], "none")
        self.assertIn("--read-only", args)
        self.assertEqual(args.count("--mount"), 1)
        self.assertNotIn("--privileged", args)
        self.assertNotIn("--publish", args)

    def test_second_client_readiness_checks_name(self):
        s = {
            "connected": True,
            "screen": None,
            "server": "127.0.0.1:25565",
            "name": "RigOpponent",
            "dimension": "minecraft:overworld",
            "onGround": True,
            "pos": [0.5, -59, 0.5],
        }
        self.assertFalse(session.client_ready(s))
        self.assertTrue(session.client_ready(s, "RigOpponent"))


if __name__ == "__main__":
    unittest.main()
