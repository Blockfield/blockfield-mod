import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import session


class Readiness(unittest.TestCase):
    def valid(self):
        return {
            "connected": True,
            "screen": None,
            "server": "127.0.0.1:25565",
            "name": "RigPlayer",
            "dimension": "minecraft:overworld",
            "onGround": True,
            "pos": [0.5, -59.0, 0.5],
        }

    def test_synchronized_world(self):
        self.assertTrue(session.client_ready(self.valid()))

    def test_local_player_before_terrain_is_not_ready(self):
        state = self.valid()
        state.update(screen="class_434", pos=[8.5, 65, 8.5], onGround=False)
        self.assertFalse(session.client_ready(state))
        for key, value in [
            ("screen", "TitleScreen"),
            ("connected", False),
            ("server", "other:25565"),
            ("name", "SomeoneElse"),
            ("dimension", "minecraft:the_end"),
            ("onGround", False),
        ]:
            state = self.valid()
            state[key] = value
            self.assertFalse(session.client_ready(state))

    def test_missing_invalid_or_untrusted_position(self):
        for pos in (
            None,
            [],
            [0, 1],
            [0, 1, 2, 3],
            ["0", -59, 0],
            [0.5, float("nan"), 0.5],
            [0.5, float("inf"), 0.5],
            [0.5, True, 0.5],
            [0, 65, 0],
        ):
            state = self.valid()
            state["pos"] = pos
            self.assertFalse(session.client_ready(state))
        self.assertFalse(session.client_ready(None))
        self.assertFalse(session.client_ready({}))

    def test_actual_custom_menu_model(self):
        model = {
            "id": "hub",
            "title": "BLOCKFIELD",
            "sections": [
                {
                    "style": "corner",
                    "buttons": [{"label": "Статистика", "action": "top:all:1"}],
                },
                {
                    "style": "cards",
                    "buttons": [
                        {"label": "Casual", "action": "casual", "primary": True}
                    ],
                },
            ],
        }
        self.assertTrue(session.hub_menu_ready(model))
        original = copy.deepcopy(model)
        for key, value in [
            ("id", "match"),
            ("title", ""),
            ("sections", {}),
            ("sections", []),
        ]:
            changed = copy.deepcopy(model)
            changed[key] = value
            self.assertFalse(session.hub_menu_ready(changed))
        for change in (
            {"action": "leave"},
            {"label": ""},
            {"enabled": False},
            {"disabled": True},
        ):
            changed = copy.deepcopy(model)
            changed["sections"][1]["buttons"][0].update(change)
            self.assertFalse(session.hub_menu_ready(changed))
        self.assertEqual(model, original)

    def test_custom_menu_missing_or_malformed(self):
        for model in (
            None,
            [],
            {},
            {"id": "hub", "title": "BLOCKFIELD", "sections": [None, [], {}]},
            {
                "id": "hub",
                "title": "BLOCKFIELD",
                "sections": [{"buttons": [None, "x"]}],
            },
            {"id": "hub", "title": "BLOCKFIELD", "sections": [{"buttons": {}}]},
        ):
            self.assertFalse(session.hub_menu_ready(model))

    def test_jna_scratch_not_readonly_native_directory(self):
        original = [
            "/usr/bin/java",
            "-Xmx1536M",
            "-Djna.tmpdir=/opt/bf/natives",
            "-Djava.library.path=/opt/bf/natives",
            "Main",
        ]
        actual = session.client_command(original)
        self.assertEqual(actual.count("-Djna.tmpdir=/tmp"), 1)
        self.assertNotIn("-Djna.tmpdir=/opt/bf/natives", actual)
        self.assertIn("-Djava.library.path=/opt/bf/natives", actual)
        self.assertIn("-Djna.tmpdir=/opt/bf/natives", original)
        self.assertEqual(actual[0], "/usr/bin/java")


if __name__ == "__main__":
    unittest.main()
