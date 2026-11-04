"""Gradle just recipes refuse the owner workstation: Boat VM or CI only."""

import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "quality", Path(__file__).resolve().parents[2] / "quality.py"
)
quality = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(quality)

SIGNALS = ("BF_ALLOW_LOCAL_GRADLE", "GITHUB_ACTIONS", "CI", "BLOCKFIELD_BOAT_VM")


def bare_env(extra=None):
    env = {k: v for k, v in os.environ.items() if k not in SIGNALS}
    env.update(extra or {})
    return env


class GradleGuard(unittest.TestCase):
    def test_blocked_on_owner_pc(self):
        with patch.dict(os.environ, bare_env(), clear=True):
            with self.assertRaises(SystemExit) as raised:
                quality.require_remote_gradle("./gradlew build")
        message = str(raised.exception.code)
        self.assertIn("Gradle is not allowed on the owner PC", message)
        self.assertIn("boat-build", message)
        self.assertIn("BF_ALLOW_LOCAL_GRADLE=1", message)

    def test_owner_escape_hatch(self):
        with patch.dict(os.environ, bare_env({"BF_ALLOW_LOCAL_GRADLE": "1"})):
            self.assertIsNone(quality.require_remote_gradle("./gradlew build"))

    def test_ci(self):
        for signal in ("CI", "GITHUB_ACTIONS"):
            with patch.dict(os.environ, bare_env({signal: "true"}), clear=True):
                self.assertIsNone(quality.require_remote_gradle("./gradlew build"))

    def test_boat_vm_env(self):
        with patch.dict(os.environ, bare_env({"BLOCKFIELD_BOAT_VM": "1"}), clear=True):
            self.assertIsNone(quality.require_remote_gradle("./gradlew build"))

    def test_gradle_entry_never_forks_on_owner_pc(self):
        with (
            patch.dict(os.environ, bare_env(), clear=True),
            patch.object(quality, "subprocess") as subprocess,
        ):
            with self.assertRaises(SystemExit):
                quality.gradle("--version")
        subprocess.run.assert_not_called()

    def test_gradle_entry_runs_with_owner_hatch(self):
        with (
            patch.dict(os.environ, bare_env({"BF_ALLOW_LOCAL_GRADLE": "1"})),
            patch.object(quality, "subprocess") as subprocess,
        ):
            quality.gradle("--version")
        subprocess.run.assert_called_once()
