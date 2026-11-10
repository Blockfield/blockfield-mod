"""The published oracle-fixtures jar must contain every fixture class the
server oracle tests import (Blockfield/blockfield-mod#42).

The server oracleTest compiles against blockfield-oracle-fixtures-<version>.jar;
when the jar omits a class the server keeps a tag-pinned vendored copy
(blockfield-server#392, unblocks server#318). This test parses the include
list that builds the jar (oracleFixtureClasses in dev/oracle-runner.gradle)
and fails when any server-imported class is missing, before any Gradle build.
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
GRADLE = ROOT / "dev/oracle-runner.gradle"
PACKAGE_DIR = Path("dev/oracleTest/java/com/lunares/blockfield/oracle")

# Every com.lunares.blockfield.oracle class the server oracleTest references
# (explicit imports plus fully-qualified uses in MixedRendererPortOracle and
# ServerTransformedOracleSession, server@b586ca6). Update with the server side
# when its imports change (server#318); a missing entry means a new vendored
# copy instead of the published jar.
SERVER_IMPORTED = {
    "AnimationManifestCases",
    "FxClientCases",
    "FxClientItems",
    "FxPoseCases",
    "LrSelectorNativeCases",
    "LrTransitionNativeCases",
    "MixedRendererCases",
    "OracleFixtureRegistry",
    "OracleProvenance",
    "OracleReflection",
    "OracleTransformAudit",
    "PaGraphNativeCases",
    "PaObserverNativeCases",
    "ParCoolNativeValues",
    "ParCoolPresentationCases",
    "ParCoolProjectionCases",
    "ParCoolStagesCases",
    "ParCoolZiplineCases",
    "StanceEaseCases",
    "WireReportVectors",
}


def fixture_list():
    text = GRADLE.read_text(encoding="utf-8")
    block = text.split("def oracleFixtureClasses = [", 1)[1].split("]", 1)[0]
    return set(re.findall(r"'(\w+)'", block))


class OracleFixturesJarCoversServerImports(unittest.TestCase):
    def test_server_imported_classes_are_in_the_jar(self):
        missing = sorted(SERVER_IMPORTED - fixture_list())
        self.assertEqual(missing, [], f"fixtures jar omits {missing} (mod#42)")

    def test_listed_classes_have_matching_sources(self):
        # A typo in the Gradle list would build a jar missing the class the
        # same way an omitted entry does: every entry must resolve to the
        # source whose top-level class the jar entry is compiled from.
        for name in sorted(fixture_list()):
            source = ROOT / PACKAGE_DIR / f"{name}.java"
            self.assertTrue(
                source.is_file(), f"oracleFixtureClasses lists {name} with no source"
            )
            body = source.read_text(encoding="utf-8")
            self.assertRegex(body, rf"(?m)^(?:public\s+)?(?:final\s+)?class\s+{name}\b")

    def test_fixtures_jar_wiring_includes_each_listed_class(self):
        # oracleFixturesJar must map every listed name to its jar entries
        # (com/lunares/blockfield/oracle/<Name>*.class covers nested classes).
        text = GRADLE.read_text(encoding="utf-8")
        self.assertIn("archiveBaseName = 'blockfield-oracle-fixtures'", text)
        self.assertRegex(
            text,
            r"from\(sourceSets\.oracleTest\.output\)\s*\{\s*include\s+"
            r'"com/lunares/blockfield/oracle/\$\{name\}\*\.class"\s*\}',
        )


if __name__ == "__main__":
    unittest.main()
