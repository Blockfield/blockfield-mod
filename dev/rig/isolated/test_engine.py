import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import launch  # noqa: E402 — import the sibling after adding its directory.


class DockerEndpoint(unittest.TestCase):
    def test_local_socket_is_pinned(self):
        with (
            patch.object(launch, "DOCKER_ENDPOINT", None),
            patch.dict(os.environ, {}, clear=True),
        ):
            with patch.object(
                launch,
                "execute",
                return_value=json.dumps(
                    [{"Endpoints": {"docker": {"Host": "unix:///var/run/docker.sock"}}}]
                ),
            ):
                self.assertEqual(
                    launch.ensure_local_engine(), "unix:///var/run/docker.sock"
                )
                self.assertEqual(
                    launch.docker_args("docker", "inspect", "id"),
                    [
                        "docker",
                        "--host",
                        "unix:///var/run/docker.sock",
                        "inspect",
                        "id",
                    ],
                )
                self.assertEqual(launch.docker_args("git", "status"), ["git", "status"])

    def test_remote_context_is_rejected(self):
        for host in (
            "ssh://host",
            "tcp://host:2375",
            "https://host",
            "unix://relative",
            "unix:///x?y",
        ):
            with (
                patch.object(launch, "DOCKER_ENDPOINT", None),
                patch.dict(os.environ, {}, clear=True),
            ):
                with patch.object(
                    launch,
                    "execute",
                    return_value=json.dumps(
                        [{"Endpoints": {"docker": {"Host": host}}}]
                    ),
                ):
                    with self.assertRaises(ValueError):
                        launch.ensure_local_engine()

    def test_explicit_host_and_context_precedence(self):
        with (
            patch.object(launch, "DOCKER_ENDPOINT", None),
            patch.dict(
                os.environ, {"DOCKER_HOST": "tcp://production:2375"}, clear=True
            ),
        ):
            with patch.object(launch, "execute") as call:
                with self.assertRaises(ValueError):
                    launch.ensure_local_engine()
                call.assert_not_called()
        with (
            patch.object(launch, "DOCKER_ENDPOINT", None),
            patch.dict(
                os.environ,
                {
                    "DOCKER_CONTEXT": "remote",
                    "DOCKER_HOST": "unix:///var/run/docker.sock",
                },
                clear=True,
            ),
        ):
            with patch.object(
                launch,
                "execute",
                return_value=json.dumps(
                    [{"Endpoints": {"docker": {"Host": "ssh://production"}}}]
                ),
            ) as call:
                with self.assertRaises(ValueError):
                    launch.ensure_local_engine()
                call.assert_called_once_with("docker", "context", "inspect", "remote")

    def test_child_environment_cannot_change_pinned_endpoint(self):
        with patch.dict(
            os.environ,
            {"DOCKER_CONTEXT": "remote", "DOCKER_HOST": "ssh://prod", "PATH": "/bin"},
            clear=True,
        ):
            self.assertEqual(launch.child_env(), {"PATH": "/bin"})

    def test_required_dependencies_are_pinned(self):
        manifest = json.loads((HERE / "dependencies.json").read_text())
        self.assertEqual({m["id"] for m in manifest["mods"]}, {"accessories", "owo"})
        for entry in manifest["mods"]:
            self.assertRegex(entry["sha512"], "^[0-9a-f]{128}$")
            self.assertTrue(entry["url"].startswith("https://cdn.modrinth.com/data/"))
            self.assertEqual(Path(entry["file"]).name, entry["file"])


if __name__ == "__main__":
    unittest.main()
