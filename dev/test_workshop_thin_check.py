"""Unit tests for dev/workshop-thin-check.py (S233-U1 round 2).

Covers both review findings: the register() exemption is method-body-scoped
(registerWorkshop stays checked), and every com.lunares.blockfield import must
resolve to workshop/thin.txt.
"""

import importlib.util
import re
import unittest
from pathlib import Path

DEV = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "workshop_thin_check", DEV / "workshop-thin-check.py"
)
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


THIN = [
    "com/lunares/blockfield/api/mode/GameState.java",
    "com/lunares/blockfield/api/voice/VoiceProxyHolder.java",
    "com/lunares/blockfield/network/packet/MapPingC2SPacket.java",
    "com/lunares/blockfield/workshop/WorkshopGeometry.java",
]


class GateTest(unittest.TestCase):
    def test_register_workshop_game_ref_fails(self):
        text = (
            "package com.lunares.blockfield.network;\n"
            "public class NetworkHandler {\n"
            "    public static void register() {\n"
            "        onceC2S(GunBalanceS2SPacketX.TYPE, GunBalanceS2SPacketX.CODEC);\n"
            "    }\n"
            "    public static void registerWorkshop() {\n"
            "        onceC2S(GunBalanceS2CPacket.TYPE, GunBalanceS2CPacket.CODEC);\n"
            "    }\n"
            "}\n"
        )
        with self.assertRaises(SystemExit):
            GATE.check_references(GATE.EXEMPT_REFERENCE, text, ("GunBalanceS2CPacket",))

    def test_register_body_game_ref_exempt(self):
        text = (
            "package com.lunares.blockfield.network;\n"
            "public class NetworkHandler {\n"
            "    public static void register() {\n"
            "        onceC2S(GunBalanceS2CPacket.TYPE, GunBalanceS2CPacket.CODEC);\n"
            "    }\n"
            "    public static void registerWorkshop() {\n"
            "    }\n"
            "}\n"
        )
        GATE.check_references(GATE.EXEMPT_REFERENCE, text, ("GunBalanceS2CPacket",))

    def test_nonlisted_import_fails(self):
        for imp in (
            "import com.lunares.blockfield.workshop.Missing;",
            "import com.lunares.blockfield.network.Missing;",
            "import com.lunares.blockfield.api.Missing;",
            "import static com.lunares.blockfield.workshop.Missing.CONST;",
            "import com.lunares.blockfield.combat.*;",
        ):
            with self.assertRaises(SystemExit, msg=imp):
                GATE.check_imports("some/Thin.java", imp + "\n", THIN)

    def test_listed_imports_pass(self):
        text = (
            "import com.lunares.blockfield.api.mode.GameState;\n"
            "import com.lunares.blockfield.api.voice.VoiceProxyHolder;\n"
            "import static com.lunares.blockfield.api.mode.GameState.VALUE;\n"
            "import com.lunares.blockfield.network.packet.*;\n"
            "import net.minecraft.server.level.ServerPlayer;\n"
        )
        GATE.check_imports("some/Thin.java", text, THIN)

    def test_current_tree_imports_resolve(self):
        files = GATE.thin_files()
        for rel in files:
            GATE.check_imports(rel, (GATE.COMMON / rel).read_text(), files)

    def test_current_register_workshop_clean(self):
        text = (GATE.COMMON / GATE.EXEMPT_REFERENCE).read_text()
        masked = GATE.mask_non_code(text)
        match = re.search(
            r"public\s+static\s+void\s+registerWorkshop\s*\(\s*\)\s*\{", masked
        )
        self.assertIsNotNone(match)
        depth, start, stop = 0, match.end() - 1, None
        for i in range(start, len(masked)):
            if masked[i] == "{":
                depth += 1
            elif masked[i] == "}":
                depth -= 1
                if depth == 0:
                    stop = i + 1
                    break
        self.assertIsNotNone(stop)
        body = text[start:stop]
        files = GATE.thin_files()
        thin_names = {Path(name).name for name in files}
        game_names = GATE.game_packets(thin_names)
        hits = [name for name in game_names if re.search(rf"\b{name}\b", body)]
        self.assertEqual(hits, [])


if __name__ == "__main__":
    unittest.main()
