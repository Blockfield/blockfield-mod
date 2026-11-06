"""Unit tests for dev/workshop-thin-check.py (S233-U1 round 3).

javac enforces the thin closure (no sibling-project dependency on the
workshop compile classpath); the gate only checks the wiring javac cannot
see. These tests pin that wiring check.
"""

import importlib.util
import unittest
from pathlib import Path

DEV = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "workshop_thin_check", DEV / "workshop-thin-check.py"
)
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class GateTest(unittest.TestCase):
    def test_current_tree_passes(self):
        GATE.main()

    def test_missing_source_fails(self):
        with self.assertRaises(SystemExit):
            GATE.check_sources(["no/such/Missing.java"])

    def test_project_dependency_fails(self):
        with self.assertRaises(SystemExit):
            GATE.check_build_wiring(
                "include thin\n"
                "implementation(project(path: ':common')) { transitive = false }\n"
            )

    def test_unwired_sources_fail(self):
        with self.assertRaises(SystemExit):
            GATE.check_build_wiring("srcDir 'common/src/main/java'\n")


if __name__ == "__main__":
    unittest.main()
