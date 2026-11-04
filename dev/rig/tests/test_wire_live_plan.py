"""Pure-part tests for the live U11 wire runner (no stand, no network)."""

import importlib.util
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location(
    "wire_live", ROOT / "dev/run_anim_sync_wire_live.py"
)
LIVE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LIVE)


class WireLivePlanTest(unittest.TestCase):
    def test_step_ids_cover_every_live_item(self):
        ids = [step[0] for step in LIVE.STEPS]
        self.assertEqual(len(set(ids)), len(ids))
        for prefix in ("L0", "L1", "L2", "L3", "L4", "L5", "L6", "L7", "L8", "L9"):
            self.assertTrue(any(i.startswith(prefix) for i in ids), f"missing {prefix}")
        self.assertIn("L10-pack", ids)

    def test_thresholds_match_adr42(self):
        self.assertEqual(LIVE.AGGREGATE_MBPS_CAP, 150.0)
        self.assertEqual(LIVE.VELOCITY_MEM_MB_CAP, 192)
        self.assertEqual(LIVE.MAIN_SHARE_MS_CAP, 10.0)
        self.assertEqual(LIVE.COMPRESSION_THRESHOLD, -1)
        self.assertEqual(tuple(LIVE.FPS_LEVELS), (30, 60, 144, 240))

    def test_tc_commands_exact(self):
        self.assertEqual(
            LIVE.tc_shape_cmd("eth0", 4),
            [
                "tc",
                "qdisc",
                "replace",
                "dev",
                "eth0",
                "root",
                "tbf",
                "rate",
                "4mbit",
                "burst",
                "32kbit",
                "latency",
                "100ms",
            ],
        )
        self.assertEqual(
            LIVE.tc_clear_cmd("eth0"),
            ["tc", "qdisc", "del", "dev", "eth0", "root"],
        )

    def test_validate_rejects_bad_observers(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertTrue(LIVE.validate(Path(directory), 30) == [])
            self.assertTrue(LIVE.validate(Path(directory), 1))
            self.assertTrue(LIVE.validate(Path(directory), 65))

    def test_plan_writes_evidence_per_step(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            plan = LIVE.build_plan(out, 30)
            self.assertEqual(len(plan), len(LIVE.STEPS))
            for item in plan:
                for key in ("command", "metric", "threshold", "evidence"):
                    self.assertTrue(item[key], f"{item['step']} misses {key}")
                self.assertTrue(item["evidence"].startswith(str(out)))


if __name__ == "__main__":
    unittest.main()
