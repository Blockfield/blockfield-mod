"""parkour-test.py: how a finished attempt is judged from the blocks the player stood on."""

import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "parkour-test.py"
spec = importlib.util.spec_from_file_location("parkour_test", SCRIPT)
parkour = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parkour)


class Judge(unittest.TestCase):
    def test_reached_near_and_no(self):
        start, target = [0, 64.0, 0], [1, 67.0, 0]
        self.assertEqual(
            parkour.judge(start, target, ["0 0 64.0", "1 0 67.0"]), "reached"
        )
        # Next to the target on the ledge (the model took the top of an iron bar there).
        self.assertEqual(parkour.judge(start, target, ["0 0 64.0", "1 1 66.5"]), "near")
        # Walked under the ledge into the target column at the start height.
        self.assertEqual(parkour.judge(start, target, ["0 0 64.0", "1 0 64.0"]), "no")
        # Jumping in place on the start block never counts.
        self.assertEqual(parkour.judge(start, [0, 64.0, 1], ["0 0 64.0"]), "no")


if __name__ == "__main__":
    unittest.main()
