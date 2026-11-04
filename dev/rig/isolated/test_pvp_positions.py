"""Regressions for a collision-free fixture, not a substitute for live PvP."""

import math
import unittest

from pvp_scenario import COMBAT_POSITIONS, HUB_SPAWN, at_position


class PvpPositions(unittest.TestCase):
    def test_combatants_are_outside_the_respawn_collision_area(self):
        for position in COMBAT_POSITIONS:
            self.assertGreater(math.dist(position, HUB_SPAWN), 8)
            self.assertFalse(at_position({"pos": list(position)}, HUB_SPAWN))
        self.assertEqual(2, math.dist(*COMBAT_POSITIONS))

    def test_exact_spawn_and_small_network_rounding_are_accepted(self):
        self.assertTrue(at_position({"pos": list(HUB_SPAWN)}, HUB_SPAWN))
        self.assertTrue(at_position({"pos": [0.55, -59, 0.45]}, HUB_SPAWN))

    def test_every_axis_is_checked_not_only_z(self):
        for position in ([8.5, -59, 0.5], [0.5, -60, 0.5], [0.5, -59, 8.5]):
            self.assertFalse(at_position({"pos": position}, HUB_SPAWN))

    def test_collision_displacement_is_not_hidden_by_a_looser_tolerance(self):
        self.assertFalse(at_position({"pos": [0.5, -59, 0.9]}, HUB_SPAWN))
        self.assertFalse(at_position({"pos": [0.75, -59, 0.5]}, HUB_SPAWN))

    def test_disconnected_malformed_and_nonfinite_positions_fail_closed(self):
        for position in (
            None,
            [],
            [0.5, -59],
            [0.5, -59, 0.5, 0],
            ["0.5", -59, 0.5],
            [True, -59, 0.5],
            [float("nan"), -59, 0.5],
            [0.5, float("inf"), 0.5],
        ):
            self.assertFalse(at_position({"pos": position}, HUB_SPAWN))
        self.assertFalse(at_position({}, HUB_SPAWN))


if __name__ == "__main__":
    unittest.main()
