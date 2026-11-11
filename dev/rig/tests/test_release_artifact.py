"""mod#46: the v1.59.2 tag declared PROTOCOL 9 and the published jar spoke 9,
but nothing in the pipeline proved it. dev/rig/release_artifact.py is that
proof: it fails a release whose jar filenames or baked protocol differ from
the tagged source. Tests use synthetic class bytes, never the network."""

import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

HERE = Path(__file__).resolve()
ROOT = HERE.parents[3]
RIG = HERE.parents[1]

spec = importlib.util.spec_from_file_location(
    "release_artifact", RIG / "release_artifact.py"
)
release_artifact = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_artifact)

SOURCE = ROOT / "common/src/main/java/com/lunares/blockfield/network/PackContract.java"


def class_bytes(protocol):
    """Minimal .class with a ConstantValue PROTOCOL int field."""
    pool = bytearray()
    count = [0]

    def add(entry):
        pool.extend(entry)
        count[0] += 1
        return count[0]

    def utf8(text):
        encoded = text.encode("utf-8")
        return add(b"\x01" + struct.pack(">H", len(encoded)) + encoded)

    i_name = utf8("PackContract")
    i_class = add(b"\x07" + struct.pack(">H", i_name))
    i_field = utf8("PROTOCOL")
    i_desc = utf8("I")
    i_attr = utf8("ConstantValue")
    i_value = add(b"\x03" + struct.pack(">i", protocol))
    header = b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 65, count[0] + 1)
    field = struct.pack(">HHHH", 0x0019, i_field, i_desc, 1) + struct.pack(
        ">HIH", i_attr, 2, i_value
    )
    return (
        header
        + bytes(pool)
        + struct.pack(">HHH", 0x0021, i_class, 0)
        + struct.pack(">H", 0)
        + struct.pack(">H", 1)
        + field
    )


def source_file(protocol):
    text = f"public static final int PROTOCOL = {protocol};\n"
    path = Path(tempfile.mkdtemp()) / "PackContract.java"
    path.write_text(text, encoding="utf-8")
    return str(path)


def jar(name, protocol=None):
    path = Path(tempfile.mkdtemp()) / name
    with zipfile.ZipFile(path, "w") as archive:
        if protocol is not None:
            archive.writestr(
                "com/lunares/blockfield/network/PackContract.class",
                class_bytes(protocol),
            )
    return str(path)


class ReleaseArtifactTest(unittest.TestCase):
    def test_match_passes(self):
        jars = [
            jar("blockfield-client-1.60.1+mc1.21.1.jar", 10),
            jar("blockfield-common-1.60.1+mc1.21.1.jar", 10),
        ]
        self.assertEqual(
            release_artifact.check_release("v1.60.1", source_file(10), jars), 10
        )

    def test_mismatched_protocol_fails(self):
        with self.assertRaises(release_artifact.ReleaseMismatch) as ctx:
            release_artifact.check_release(
                "v1.59.2",
                source_file(9),
                [jar("blockfield-client-1.59.2+mc1.21.1.jar", 10)],
            )
        message = str(ctx.exception)
        self.assertIn("10", message)
        self.assertIn("9", message)

    def test_missing_class_fails_closed(self):
        with self.assertRaises(release_artifact.ReleaseMismatch):
            release_artifact.check_release(
                "v1.60.1",
                source_file(10),
                [jar("blockfield-client-1.60.1+mc1.21.1.jar")],
            )

    def test_filename_version_must_match_tag(self):
        with self.assertRaises(release_artifact.ReleaseMismatch):
            release_artifact.check_release(
                "v1.60.1",
                source_file(10),
                [jar("blockfield-client-1.59.2+mc1.21.1.jar", 10)],
            )

    def test_source_without_protocol_fails_closed(self):
        path = Path(tempfile.mkdtemp()) / "PackContract.java"
        path.write_text("public final class PackContract {}\n", encoding="utf-8")
        with self.assertRaises(release_artifact.ReleaseMismatch):
            release_artifact.check_release(
                "v1.60.1", str(path), [jar("x-1.60.1+mc1.21.1.jar", 10)]
            )

    def test_real_source_parses(self):
        protocol = release_artifact.source_protocol(str(SOURCE))
        self.assertIsInstance(protocol, int)
        self.assertGreaterEqual(protocol, 1)

    def test_workflow_runs_guard_before_publish(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        publish = workflow.split("name: Publish release", 1)[1]
        self.assertIn("dev/rig/release_artifact.py", publish)
        self.assertLess(
            publish.index("dev/rig/release_artifact.py"),
            publish.index("gh release create"),
        )


if __name__ == "__main__":
    unittest.main()
