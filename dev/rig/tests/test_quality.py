"""The portable runner discovers live sources, including paths with spaces."""

import importlib.util
import os
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "quality", Path(__file__).resolve().parents[2] / "quality.py"
)
quality = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(quality)


class SharedInstall(unittest.TestCase):
    def test_interrupted_build_leaves_nothing_and_the_first_finished_install_wins(self):
        toolchain = sys.modules["toolchain"]
        with TemporaryDirectory(prefix="bf shared ") as tmp:
            target = Path(tmp) / "tool"

            def interrupted(staging):
                staging.mkdir()
                (staging / "half").touch()
                raise RuntimeError("interrupted")

            with self.assertRaises(RuntimeError):
                toolchain.staged_directory(target, interrupted)
            self.assertEqual(list(Path(tmp).iterdir()), [])

            def overtaken(staging):
                staging.mkdir()
                (staging / "mine").touch()
                target.mkdir()  # another checkout renames its install first
                (target / "theirs").touch()

            toolchain.staged_directory(target, overtaken)
            self.assertEqual([p.name for p in Path(tmp).iterdir()], ["tool"])
            self.assertEqual([p.name for p in target.iterdir()], ["theirs"])


class OracleChecks(unittest.TestCase):
    def test_fast_mode_keeps_regressions_and_skips_release_gates(self):
        with (
            patch.dict(os.environ, {"BF_SKIP_ORACLE": "1"}),
            patch.object(
                quality, "canonical_python", return_value=Path(sys.executable)
            ),
            patch.object(quality, "run") as run,
            patch.object(quality, "gradle") as gradle,
        ):
            quality.main(["test"])
        gradle.assert_not_called()
        commands = [call.args for call in run.call_args_list]
        self.assertTrue(any("unittest" in command for command in commands))
        # Server-side gates (room-close policy, U10 report, wire gate) live in
        # blockfield-server/mod-server: this repo never runs them.
        self.assertFalse(
            any(
                script in command
                for command in commands
                for script in (
                    "dev/test-room-close-policy.py",
                    "dev/anim_sync_report.py",
                    "dev/anim_sync_wire_gate.py",
                )
            )
        )

    def test_release_gates_run_by_default_and_with_zero(self):
        for value in (None, "0"):
            with (
                self.subTest(value=value),
                patch.dict(os.environ),
                patch.object(
                    quality, "canonical_python", return_value=Path(sys.executable)
                ),
                patch.object(quality, "run") as run,
                patch.object(quality, "gradle") as gradle,
            ):
                os.environ.pop("BF_SKIP_ORACLE", None)
                if value is not None:
                    os.environ["BF_SKIP_ORACLE"] = value
                quality.main(["test"])
                gradle.assert_called_once_with("--max-workers=2", ":client:oracleTest")
                commands = [call.args for call in run.call_args_list]
                for script in (
                    "dev/test-room-close-policy.py",
                    "dev/anim_sync_report.py",
                    "dev/anim_sync_wire_gate.py",
                ):
                    self.assertFalse(any(script in command for command in commands))


class Sources(unittest.TestCase):
    def test_deleted_tracked_and_ignored_outputs_are_not_checked(self):
        with TemporaryDirectory(prefix="bf quality ") as tmp:
            root = Path(tmp)
            subprocess.run(["git", "init", "--quiet", tmp], check=True)
            (root / ".gitignore").write_text("build/\n", encoding="utf-8")
            (root / "deleted.py").write_text("pass\n", encoding="utf-8")
            subprocess.run(["git", "add", "deleted.py"], cwd=root, check=True)
            (root / "deleted.py").unlink()
            (root / "new source.py").write_text("pass\n", encoding="utf-8")
            (root / "build").mkdir()
            (root / "build" / "generated.py").write_text("pass\n", encoding="utf-8")
            with patch.object(quality, "ROOT", root):
                self.assertEqual(
                    quality.source_files(), [".gitignore", "new source.py"]
                )

    def test_uniform_recipes_preserve_paths_and_set_only_the_requested_gate(self):
        with (
            TemporaryDirectory(prefix="bf uniform ") as tmp,
            patch.object(quality, "ROOT", Path(tmp)),
            patch.object(
                quality, "canonical_python", return_value=Path(sys.executable)
            ),
            patch.object(quality, "child_env", return_value={"PATH": "native tools"}),
            patch.object(quality.subprocess, "run") as execute,
        ):
            quality.main(["uniform-bake"])
            bake = execute.call_args
            self.assertEqual(
                bake.kwargs["env"], {"PATH": "native tools", "BF_UNIFORM_BAKE": "1"}
            )
            self.assertEqual(bake.args[0][-2:], ["--tests", "*UniformAtlasBaker*"])

            quality.main(["uniform-dump", "temp/quads with spaces"])
            dump = execute.call_args
            self.assertEqual(
                dump.kwargs["env"],
                {
                    "PATH": "native tools",
                    "BF_UNIFORM_DUMP": str(
                        (quality.ROOT / "temp/quads with spaces").resolve()
                    ),
                },
            )
            self.assertEqual(dump.args[0][-2:], ["--tests", "*UniformPreviewDump*"])
            self.assertIn("--rerun", dump.args[0])
            self.assertTrue((quality.ROOT / "temp/quads with spaces").is_dir())
            self.assertEqual(
                dump.args[0][0],
                str(
                    quality.ROOT
                    / ("gradlew.bat" if quality.os.name == "nt" else "gradlew")
                ),
            )

            with patch.object(quality, "native", side_effect=lambda name: name):
                quality.main(
                    [
                        "uniform-preview",
                        "temp/quads with spaces",
                        "temp/sheet with spaces.png",
                        "sniper_blue",
                    ]
                )
            self.assertEqual(
                execute.call_args.args[0],
                [
                    sys.executable,
                    "-I",
                    "-X",
                    "utf8",
                    "dev/uniforms/preview.py",
                    "temp/quads with spaces",
                    "temp/sheet with spaces.png",
                    "sniper_blue",
                ],
            )
            self.assertEqual(execute.call_args.kwargs["env"], {"PATH": "native tools"})


class JavaFormatScope(unittest.TestCase):
    def make_repo(self, root, branch="main"):
        root.mkdir(parents=True)

        def git(*args):
            return subprocess.run(
                ["git", *args], cwd=root, check=True, capture_output=True, text=True
            )

        git("init", "-b", branch)
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "test")
        (root / "common/src/main").mkdir(parents=True)
        (root / "common/src/main/Foo.java").write_text(
            "package m;\npublic class Foo {}\n", encoding="utf-8"
        )
        (root / "scripts").mkdir()
        (root / "scripts/Helper.java").write_text("class Helper {}\n", encoding="utf-8")
        git("add", ".")
        git("commit", "-m", "init")
        return git

    def test_all_returns_only_spotless_target_java(self):
        with TemporaryDirectory(prefix="bf java ") as tmp:
            root = Path(tmp) / "mod"
            self.make_repo(root)
            with patch.object(quality, "ROOT", root):
                self.assertEqual(
                    quality.java_files(all_files=True), ["common/src/main/Foo.java"]
                )

    def test_changed_returns_branch_diff_plus_untracked(self):
        with TemporaryDirectory(prefix="bf java ") as tmp:
            root = Path(tmp) / "mod"
            git = self.make_repo(root)
            git("checkout", "-b", "feature")
            (root / "common/src/main/Foo.java").write_text(
                "package m;\npublic class Foo { int x = 1; }\n", encoding="utf-8"
            )
            (root / "common/src/main/New.java").write_text(
                "class New {}\n", encoding="utf-8"
            )
            (root / "scripts/NewHelper.java").write_text(
                "class NewHelper {}\n", encoding="utf-8"
            )
            with patch.object(quality, "ROOT", root):
                self.assertEqual(
                    quality.java_files(),
                    ["common/src/main/Foo.java", "common/src/main/New.java"],
                )

    def test_unknown_base_checks_uncommitted_changes_only(self):
        with TemporaryDirectory(prefix="bf java ") as tmp:
            root = Path(tmp) / "mod"
            git = self.make_repo(root, branch="trunk")
            with patch.object(quality, "ROOT", root):
                self.assertIsNone(quality.change_base())
                self.assertEqual(quality.java_files(), [])
                (root / "common/src/main/Foo.java").write_text(
                    "package m;\npublic class Foo { int x = 1; }\n", encoding="utf-8"
                )
                self.assertEqual(quality.java_files(), ["common/src/main/Foo.java"])
                git("add", ".")
                git("commit", "-m", "format")
                self.assertEqual(quality.java_files(), [])

    def test_old_java_fails_fast_with_a_clear_error(self):
        # Standalone checkout (no workspace above us): old java still fails fast.
        with (
            patch.object(quality, "_workspace_toolchain", False),
            patch.object(
                quality.subprocess,
                "run",
                return_value=subprocess.CompletedProcess(
                    args=["java", "-version"],
                    returncode=0,
                    stdout="",
                    stderr='openjdk version "17.0.9" 2023-10-17\n',
                ),
            ),
        ):
            with self.assertRaisesRegex(SystemExit, "Java 21"):
                quality.java_version()

    def test_pinned_jdk_wins_over_old_system_java(self):
        # Wrong host java (JAVA_HOME=/wrong, PATH java 17): the workspace
        # Temurin 21 is exported and used instead, without manual steps.
        # Platform-independent: the pinned binary is never executed (an empty
        # file resolves via PATH; the `-version` probe stays mocked).
        from types import SimpleNamespace

        with TemporaryDirectory(prefix="bf jdk ") as tmp:
            home = Path(tmp)
            (home / "bin").mkdir()
            java = home / "bin" / ("java.exe" if os.name == "nt" else "java")
            java.write_bytes(b"")
            if os.name != "nt":
                java.chmod(0o755)
            pinned = SimpleNamespace(java_home=lambda: home)
            with (
                patch.object(quality, "_workspace_toolchain", pinned),
                patch.dict(
                    os.environ,
                    {
                        "JAVA_HOME": "/wrong/jdk",
                        "PATH": "/usr/bin:/bin",
                        # The Gradle guard below only runs on VMs/CI by design.
                        "BLOCKFIELD_BOAT_VM": "1",
                    },
                ),
                patch.object(quality.subprocess, "run") as run,
            ):
                # Mocked `-version` probe (the pinned binary is never executed);
                # the Gradle fork itself stays mocked (no wrapper execution).
                def fake_run(cmd, **kwargs):
                    if Path(cmd[0]).name in ("java", "java.exe"):
                        return subprocess.CompletedProcess(
                            cmd, 0, "", 'openjdk version "21.0.12" 2025-07-15\n'
                        )
                    return subprocess.CompletedProcess(cmd, 0, "", "")

                run.side_effect = fake_run
                quality.java_version()  # does not raise
                quality.gradle("--version")  # does not raise
                # java_version() + gradle() in one process must not stack bin/
                # in os.environ (child_env may prepend it once more for a child).
                own_path = os.environ["PATH"].split(os.pathsep)
                self.assertEqual(own_path[0], str(home / "bin"))
                self.assertEqual(own_path.count(str(home / "bin")), 1)
            calls = [call.args[0] for call in run.call_args_list]
            self.assertEqual(calls[0][0], str(java))
            gradle_env = run.call_args_list[1].kwargs["env"]
            self.assertEqual(gradle_env["JAVA_HOME"], str(home))
            self.assertEqual(gradle_env["PATH"].split(os.pathsep)[0], str(home / "bin"))

    def test_unrelated_toolchain_is_never_executed(self):
        with TemporaryDirectory(prefix="bf ws ") as tmp:
            root = Path(tmp)
            # A proper workspace layout resolves.
            good = root / "ws" / "localmods" / "blockfield-mod"
            good.mkdir(parents=True)
            (root / "ws" / "scripts").mkdir(parents=True)
            (root / "ws" / "bf.py").write_text("pass\n", encoding="utf-8")
            toolchain = root / "ws" / "scripts" / "toolchain.py"
            toolchain.write_text("MARKER = 1\n", encoding="utf-8")
            with (
                patch.object(quality, "_workspace_toolchain", None),
                patch.object(quality, "ROOT", good),
            ):
                self.assertEqual(quality._workspace_toolchain_module().MARKER, 1)
            # Same file without workspace markers is ignored, never executed.
            (root / "ws" / "bf.py").unlink()
            with (
                patch.object(quality, "_workspace_toolchain", None),
                patch.object(quality, "ROOT", good),
            ):
                self.assertIsNone(quality._workspace_toolchain_module())
            # Outside localmods/ it is ignored even with markers present.
            (root / "ws" / "bf.py").write_text("pass\n", encoding="utf-8")
            with (
                patch.object(quality, "_workspace_toolchain", None),
                patch.object(quality, "ROOT", root / "projects" / "mod"),
            ):
                self.assertIsNone(quality._workspace_toolchain_module())

    def test_fs_root_checkout_is_treated_as_standalone(self):
        # A checkout at the filesystem root (e.g. /app in Docker) has no
        # parents[1]: standalone fallback, never IndexError.
        with (
            patch.object(quality, "_workspace_toolchain", None),
            patch.object(quality, "ROOT", Path("/app")),
        ):
            self.assertIsNone(quality._workspace_toolchain_module())

    def test_setup_wires_versioned_hooks(self):
        with TemporaryDirectory(prefix="bf hooks ") as tmp:
            root = Path(tmp) / "mod"
            self.make_repo(root)
            (root / ".githooks").mkdir()
            with patch.object(quality, "ROOT", root):
                self.assertTrue(quality.wire_hooks(root))
            probe = subprocess.run(
                ["git", "config", "core.hooksPath"],
                cwd=root,
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertEqual(probe.stdout.strip(), ".githooks")

    def test_wire_hooks_reports_already_wired_as_success(self):
        with TemporaryDirectory(prefix="bf hooks ") as tmp:
            root = Path(tmp) / "mod"
            self.make_repo(root)
            (root / ".githooks").mkdir()
            subprocess.run(
                ["git", "config", "core.hooksPath", ".githooks"],
                cwd=root,
                check=True,
            )
            with patch.object(quality, "ROOT", root):
                self.assertTrue(quality.wire_hooks(root))

    def test_wire_hooks_leaves_explicit_choice_alone(self):
        with TemporaryDirectory(prefix="bf hooks ") as tmp:
            root = Path(tmp) / "mod"
            self.make_repo(root)
            (root / ".githooks").mkdir()
            subprocess.run(
                ["git", "config", "core.hooksPath", ".custom"],
                cwd=root,
                check=True,
            )
            with patch.object(quality, "ROOT", root):
                self.assertFalse(quality.wire_hooks(root))

    def test_wire_hooks_warns_every_time_it_supersedes_outer_hooks(self):
        with TemporaryDirectory(prefix="bf hooks ") as tmp:
            root = Path(tmp) / "mod"
            self.make_repo(root)
            (root / ".githooks").mkdir()
            subprocess.run(
                ["git", "config", "core.hooksPath", ".githooks"],
                cwd=root,
                check=True,
            )
            calls = []

            def fake_run(args, **kwargs):
                calls.append(args)
                if "--local" in args:
                    return subprocess.CompletedProcess(args, 0, stdout=".githooks\n")
                if "--global" in args:
                    return subprocess.CompletedProcess(args, 0, stdout=".outer\n")
                return subprocess.CompletedProcess(args, 1, stdout="")

            with (
                patch.object(quality, "ROOT", root),
                patch.object(quality.subprocess, "run", side_effect=fake_run),
            ):
                self.assertTrue(quality.wire_hooks(root))
            self.assertEqual(len(calls), 2)
            self.assertFalse(
                any("--local" in c and c[-1] == ".githooks" for c in calls)
            )

    def test_wire_hooks_leaves_outer_repo_alone_on_invalid_gitdir(self):
        with TemporaryDirectory(prefix="bf hooks ") as tmp:
            outer = Path(tmp)
            subprocess.run(["git", "init", "--quiet", tmp], check=True)
            subprocess.run(
                ["git", "config", "core.hooksPath", "/outer/hooks"],
                cwd=outer,
                check=True,
            )
            sub = outer / "sub"
            sub.mkdir()
            (sub / ".git").mkdir()  # invalid git dir: git would resolve upward
            (sub / ".githooks").mkdir()
            self.assertFalse(quality.wire_hooks(sub))
            probe = subprocess.run(
                ["git", "config", "--local", "--get", "core.hooksPath"],
                cwd=outer,
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertEqual(probe.stdout.strip(), "/outer/hooks")


class JavaImportOrderCheck(unittest.TestCase):
    def test_check_java_mirrors_spotless_import_order(self):
        # The AOSP pass skips import sorting, so an order-only-dirty file
        # passes it yet fails Spotless; the second pass must catch that.
        with (
            patch.object(quality, "java_version"),
            patch.object(quality, "download", return_value=Path("gjf.jar")),
            patch.object(quality, "each") as each,
        ):
            quality.check_java(["common/src/main/Foo.java"])
        self.assertEqual(each.call_count, 2)
        first, second = (call.args[0] for call in each.call_args_list)
        self.assertIn("--aosp", first)
        self.assertIn("--skip-sorting-imports", first)
        self.assertIn("--fix-imports-only", second)
        self.assertNotIn("--aosp", second)


class PreCommitHook(unittest.TestCase):
    def test_hook_formats_staged_files_without_gradle(self):
        root = quality.ROOT
        hook = root / ".githooks" / "pre-commit"
        self.assertTrue(hook.is_file())
        mode = subprocess.run(
            ["git", "ls-files", "-s", ".githooks/pre-commit"],
            cwd=root,
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        self.assertTrue(mode.startswith("100755"), mode)
        text = hook.read_text(encoding="utf-8")
        self.assertTrue(text.startswith("#!/bin/sh"))
        self.assertIn("just format-staged", text)
        commands = [
            line for line in text.splitlines() if line and not line.startswith("#")
        ]
        self.assertFalse(any("gradle" in line.lower() for line in commands))

    def test_commit_formats_staged_files(self):
        # Fail-before: without the hook + format-staged, the raw bytes commit.
        with TemporaryDirectory(prefix="bf precommit ") as tmp:
            root = Path(tmp)
            for name in (
                ".gitignore",
                "justfile",
                "dev/quality.py",
                "scripts/toolchain.py",
            ):
                src = quality.ROOT / name
                dst = root / name
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_bytes(src.read_bytes())
            (root / ".githooks").mkdir()
            (root / ".githooks" / "pre-commit").write_bytes(
                (quality.ROOT / ".githooks" / "pre-commit").read_bytes()
            )
            (root / ".githooks" / "pre-commit").chmod(0o755)

            def git(*args):
                return subprocess.run(
                    ["git", *args],
                    cwd=root,
                    check=True,
                    capture_output=True,
                    text=True,
                    timeout=120,
                ).stdout

            git("init", "--quiet")
            git("config", "user.email", "hook-test@example.com")
            git("config", "user.name", "hook-test")
            git("config", "core.hooksPath", str(root / ".githooks"))
            git("add", ".")
            git("commit", "--quiet", "-m", "fixture")
            (root / "probe.py").write_text("x=1\n", encoding="utf-8")
            (root / "probe.sh").write_text(
                "if [ 1 = 1 ] ; then echo hi ; fi\n", encoding="utf-8"
            )
            git("add", "probe.py", "probe.sh")
            git("commit", "--quiet", "-m", "probe")
            formatted_py = git("show", "HEAD:probe.py")
            formatted_sh = git("show", "HEAD:probe.sh")
            self.assertNotEqual(formatted_py, "x=1\n")
            self.assertNotEqual(formatted_sh, "if [ 1 = 1 ] ; then echo hi ; fi\n")
            self.assertEqual(
                subprocess.run(
                    ["just", "format-check"],
                    cwd=root,
                    capture_output=True,
                    text=True,
                    timeout=300,
                ).returncode,
                0,
            )

    def test_commit_leaves_partially_staged_files_alone(self):
        # Fail-before: the old hook formatted the worktree copy and staged it
        # whole, committing hunks the user never staged.
        with TemporaryDirectory(prefix="bf precommit ") as tmp:
            root = Path(tmp)
            for name in (
                ".gitignore",
                "justfile",
                "dev/quality.py",
                "scripts/toolchain.py",
            ):
                src = quality.ROOT / name
                dst = root / name
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_bytes(src.read_bytes())
            (root / ".githooks").mkdir()
            (root / ".githooks" / "pre-commit").write_bytes(
                (quality.ROOT / ".githooks" / "pre-commit").read_bytes()
            )
            (root / ".githooks" / "pre-commit").chmod(0o755)

            def git(*args):
                return subprocess.run(
                    ["git", *args],
                    cwd=root,
                    check=True,
                    capture_output=True,
                    text=True,
                    timeout=120,
                ).stdout

            git("init", "--quiet")
            git("config", "user.email", "hook-test@example.com")
            git("config", "user.name", "hook-test")
            git("config", "core.hooksPath", str(root / ".githooks"))
            git("add", ".")
            git("commit", "--quiet", "-m", "fixture")
            (root / "probe.py").write_text("x=1\n", encoding="utf-8")
            git("add", "probe.py")
            (root / "probe.py").write_text(
                "x=1\n# scratch, never staged\n", encoding="utf-8"
            )
            git("commit", "--quiet", "-m", "probe")
            self.assertEqual(git("show", "HEAD:probe.py"), "x=1\n")
            self.assertEqual(
                (root / "probe.py").read_text(encoding="utf-8"),
                "x=1\n# scratch, never staged\n",
            )


class WireHooksStdout(unittest.TestCase):
    def test_wiring_notice_stays_off_stdout(self):
        # CI captures `quality.py run ... ` stdout into $GITHUB_OUTPUT; a notice there broke it.
        import contextlib
        import io

        with TemporaryDirectory(prefix="bf hooks ") as tmp:
            subprocess.run(["git", "init", "--quiet", tmp], check=True)
            (Path(tmp) / ".githooks").mkdir()
            out = io.StringIO()
            with (
                contextlib.redirect_stdout(out),
                contextlib.redirect_stderr(io.StringIO()),
            ):
                self.assertTrue(quality.wire_hooks(Path(tmp)))
        self.assertEqual(out.getvalue(), "")


if __name__ == "__main__":
    unittest.main()
