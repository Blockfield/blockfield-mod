import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "guard", Path(__file__).resolve().parents[1] / "process_guard.py"
)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class ProcessGuardTest(unittest.TestCase):
    def setUp(self):
        self.cwd = Path.home() / ".cache/bf-test-server"
        self.command = ["java", "-jar", "fabric-server-launch.jar", "nogui"]
        self.metadata = {
            "id": "exact-id",
            "owner": "current",
            "pid": 123,
            "start_ticks": "456",
            "cwd": str(self.cwd),
            "command": self.command,
        }
        self.process = {
            "pid": 123,
            "ticks": "456",
            "cwd": str(self.cwd),
            "command": self.command,
            "environment": ["AI_AGENT_PROCESS_ID=exact-id"],
        }

    def attempt_stop(self, metadata, process):
        with (
            patch.object(guard, "owner", return_value="current"),
            patch.object(guard, "registered", return_value=metadata),
            patch.object(guard, "process", return_value=process),
            patch.object(guard.subprocess, "run") as run,
        ):
            with self.assertRaises(RuntimeError):
                guard.stop("server")
            run.assert_not_called()

    def test_refuses_other_session(self):
        self.attempt_stop([{**self.metadata, "owner": "old-session"}], self.process)

    def test_refuses_reused_pid(self):
        self.attempt_stop([self.metadata], {**self.process, "ticks": "789"})

    def test_refuses_untagged_process(self):
        self.attempt_stop([self.metadata], {**self.process, "environment": []})

    def test_refuses_changed_command(self):
        changed = {**self.process, "command": self.command + ["unexpected"]}
        self.attempt_stop([self.metadata], changed)

    def test_refuses_wrong_working_directory(self):
        self.attempt_stop([self.metadata], {**self.process, "cwd": "/another/project"})

    def test_refuses_unregistered_and_ambiguous(self):
        self.attempt_stop([], self.process)
        self.attempt_stop([self.metadata, self.metadata], self.process)

    def test_stop_only_exact_registry_id(self):
        with (
            patch.object(guard, "owner", return_value="current"),
            patch.object(guard, "registered", return_value=[self.metadata]),
            patch.object(guard, "process", return_value=self.process),
            patch.object(guard.subprocess, "run") as run,
        ):
            guard.stop("server")
            run.assert_called_once_with(
                [str(guard.HELPER), "stop", "--owner", "current", "exact-id"],
                check=True,
            )

    def test_start_refuses_unrelated_listener(self):
        with (
            patch.object(guard, "processes", return_value=[]),
            patch.object(guard, "listeners", return_value=[("occupied", set())]),
        ):
            with self.assertRaises(RuntimeError):
                guard.discover("server")

    def test_start_reuses_booting_server(self):
        with (
            patch.object(guard, "processes", return_value=[self.process]),
            patch.object(guard, "listeners", return_value=[]),
            patch.object(
                guard.socket, "create_connection", side_effect=ConnectionRefusedError
            ),
        ):
            self.assertTrue(guard.discover("server"))

    def test_second_client_is_a_separate_identity(self):
        first = {
            "cwd": str(guard.RIG),
            "command": ["podman", "run", "--rm", "--name", "bf-client"],
        }
        second = {
            "cwd": str(guard.RIG),
            "command": ["podman", "run", "--rm", "--name", "bf-client2"],
        }
        self.assertTrue(guard.matches(second, "client2", guard.RIG))
        self.assertFalse(guard.matches(second, "client", guard.RIG))
        self.assertFalse(guard.matches(first, "client2", guard.RIG))
        self.assertEqual(("bf-client2", (47778,)), guard.settings("client2")[1:])
        self.assertEqual(("bf-client5", (47781,)), guard.settings("client5")[1:])
        self.assertEqual("bf-test-client5", guard.client_dir("client5"))
        with self.assertRaises(RuntimeError):
            guard.settings("client1")

    def test_real_session_takes_precedence_over_manual_owner(self):
        with patch.dict(
            guard.os.environ,
            {"CODEX_THREAD_ID": "current", "BF_RIG_OWNER": "old"},
            clear=True,
        ):
            self.assertEqual("current", guard.owner())

    def test_no_fake_default_owner(self):
        with patch.dict(guard.os.environ, {}, clear=True):
            with self.assertRaises(RuntimeError):
                guard.owner()


if __name__ == "__main__":
    unittest.main()
