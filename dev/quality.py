"""Portable project checks behind the native Just entrypoints."""

import argparse
import importlib.util
import os
import re
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from toolchain import canonical_python, child_env, download, native, tool  # noqa: E402

_workspace_toolchain = None


def _workspace_toolchain_module():
    """Workspace scripts/toolchain.py when this checkout lives under one
    (localmods/blockfield-mod); None for standalone checkouts (CI pins Java)."""
    global _workspace_toolchain
    if _workspace_toolchain is False:
        return None
    if _workspace_toolchain is not None:
        return _workspace_toolchain
    # Never execute an unrelated toolchain.py: the checkout must sit at
    # <workspace>/localmods/blockfield-mod and the workspace must carry its
    # own markers next to scripts/toolchain.py.
    if len(ROOT.parents) < 2:
        _workspace_toolchain = False
        return None
    workspace = ROOT.parents[1]
    if (
        ROOT.parent.name != "localmods"
        or ROOT.name != "blockfield-mod"
        or not (workspace / "scripts" / "toolchain.py").is_file()
        or not (workspace / "bf.py").is_file()
    ):
        _workspace_toolchain = False
        return None
    candidate = workspace / "scripts" / "toolchain.py"
    if str(candidate.parent) not in sys.path:
        sys.path.insert(0, str(candidate.parent))
    spec = importlib.util.spec_from_file_location(
        "blockfield_workspace_toolchain", candidate
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    _workspace_toolchain = module
    return module


def ensure_pinned_jdk():
    """Point JAVA_HOME/PATH at the workspace Temurin 21 so Gradle and GJF run
    on the pinned JDK no matter what host java the VM carries; None standalone."""
    module = _workspace_toolchain_module()
    if module is None:
        return None
    home = str(module.java_home())
    os.environ["JAVA_HOME"] = home
    entries = [str(Path(home) / "bin"), *os.environ.get("PATH", "").split(os.pathsep)]
    os.environ["PATH"] = os.pathsep.join(
        dict.fromkeys(part for part in entries if part)
    )
    return home


# Same formatter Spotless runs (build.gradle): google-java-format 1.36.1, AOSP style.
GJF_JAR = "google-java-format-1.36.1-all-deps.jar"
GJF_URL = (
    "https://repo.maven.apache.org/maven2/com/google/googlejavaformat/"
    "google-java-format/1.36.1/google-java-format-1.36.1-all-deps.jar"
)
GJF_SHA256 = "25b400f003089d23cc5320cdaf1a16cabee19b8aa3434d0ff021b3d9f42154b4"
# javac internals GJF touches on JDK 16+; without them it dies with LinkageError.
GJF_EXPORTS = [
    "--add-exports",
    "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "--add-exports",
    "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "--add-exports",
    "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "--add-exports",
    "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-exports",
    "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
]
# Spotless `java { target ... }` in build.gradle: only these trees are Java-formatted.
JAVA_FORMAT_ROOTS = (
    "common/src",
    "client/src",
    "dev/rig/testbot/src",
    "dev/test",
    "dev/oracleTest",
)


def run(command, *args):
    executable = (
        str(tool(command))
        if command in ("ruff", "actionlint", "shellcheck", "shfmt", "prettier")
        else native(command)
    )
    subprocess.run(
        [executable, *map(str, args)],
        cwd=ROOT,
        check=True,
        env=child_env(),
        shell=os.name == "nt" and Path(executable).suffix.lower() in (".bat", ".cmd"),
    )


def each(command, files):
    batch, length = [], 0
    for name in files:
        size = len(name.encode("utf-16-le")) // 2 + 3
        if batch and length + size > 5000:
            run(*command, *batch)
            batch, length = [], 0
        batch.append(name)
        length += size
    if batch:
        run(*command, *batch)


def source_files():
    output = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        cwd=ROOT,
        env=child_env(),
    )
    return sorted(
        {
            os.fsdecode(name)
            for name in output.split(b"\0")
            if name and (ROOT / os.fsdecode(name)).is_file()
        }
    )


def eol():
    output = subprocess.check_output(
        ["git", "ls-files", "--eol"], cwd=ROOT, env=child_env(), text=True
    )
    bad = []
    for line in output.splitlines():
        fields = line.split("\t")
        if len(fields) != 4:
            continue
        index, _worktree, attr, path = fields
        if "attr/-text" in attr:
            continue  # stored byte-for-byte, Git never converts
        if index in ("i/crlf", "i/mixed") or path.lower().endswith((".bat", ".cmd")):
            bad.append(line.split("\t", 1)[1])
    if bad:
        raise SystemExit(
            "CRLF in the Git index or .bat/.cmd without -text:\n"
            + "\n".join(sorted(bad))
            + "\nFix with: git add --renormalize ."
        )


def require_remote_gradle(command):
    """Refuse Gradle on the owner workstation: builds belong on a Boat VM or CI."""
    if os.environ.get("BF_ALLOW_LOCAL_GRADLE") == "1":
        return  # owner-only escape hatch; agents never set it
    if os.environ.get("GITHUB_ACTIONS") == "true" or os.environ.get("CI") == "true":
        return
    if os.environ.get("BLOCKFIELD_BOAT_VM") == "1":
        return
    raise SystemExit(
        "Gradle is not allowed on the owner PC: run this on your Boat VM "
        f"(just boat-build -- '{command}'). Owner override: BF_ALLOW_LOCAL_GRADLE=1."
    )


def gradle(*args, environment=None):
    require_remote_gradle(" ".join(["./gradlew", *args]))
    ensure_pinned_jdk()
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    subprocess.run(
        [str(wrapper), "-Dorg.gradle.daemon.idletimeout=600000", *args],
        cwd=ROOT,
        env={**child_env(), **(environment or {})},
        check=True,
        shell=os.name == "nt",
    )


def java_version():
    """Fail fast when PATH java cannot run GJF 1.36.1 (needs 21+): otherwise it
    dies with `LinkageError ... UnsupportedClassVersionError` (schematics CI)."""
    ensure_pinned_jdk()
    probe = subprocess.run(
        [native("java"), "-version"],
        check=True,
        capture_output=True,
        text=True,
        env=child_env(),
    )
    match = re.search(r'version "(\d+)(?:\.(\d+))?', probe.stderr + probe.stdout)
    if not match:
        raise SystemExit("could not parse `java -version` output")
    major = int(match.group(1))
    if major == 1:
        major = int(match.group(2))
    if major < 21:
        raise SystemExit(
            f"google-java-format 1.36.1 needs Java 21+, PATH has {major}; "
            "run inside the blockfield-workspace checkout (localmods/) so the "
            "pinned Temurin 21 is used, or set JAVA_HOME to a JDK 21 and put "
            "its bin/ first on PATH"
        )


def in_java_roots(name):
    return any(
        name == root or name.startswith(root + "/") for root in JAVA_FORMAT_ROOTS
    )


def change_base():
    """Merge-base of this checkout against main: what the Java check compares."""
    for ref in ("origin/main", "main"):
        try:
            return subprocess.check_output(
                ["git", "merge-base", "HEAD", ref],
                cwd=ROOT,
                env=child_env(),
                text=True,
            ).strip()
        except subprocess.CalledProcessError:
            continue
    return None


def git_names(*args):
    output = subprocess.check_output(
        ["git", args[0], "-z", *args[1:]], cwd=ROOT, env=child_env()
    )
    return {
        os.fsdecode(name)
        for name in output.split(b"\0")
        if name and (ROOT / os.fsdecode(name)).is_file()
    }


def java_files(all_files=False):
    """Spotless-target Java files: all with --all, else changed vs the branch base
    (plus new untracked files, plus uncommitted edits when no base is found)."""
    if all_files:
        return sorted(
            name
            for name in source_files()
            if name.endswith(".java") and in_java_roots(name)
        )
    base = change_base()
    if base:
        names = git_names("diff", "--name-only", base, "--")
    else:
        names = git_names("diff", "--name-only", "HEAD", "--")
    names |= git_names("ls-files", "--others", "--exclude-standard")
    return sorted(
        name for name in names if name.endswith(".java") and in_java_roots(name)
    )


def check_java(files):
    if not files:
        return
    java_version()
    jar = download(GJF_JAR, GJF_URL, GJF_SHA256)
    each(
        [
            "java",
            *GJF_EXPORTS,
            "-jar",
            str(jar),
            "--aosp",
            # Parity with Spotless `googleJavaFormat('1.36.1').aosp()`: its defaults
            # skip import sorting and long-string reflows (unused imports are still
            # removed), so the sorting check below needs `--fix-imports-only`.
            "--skip-sorting-imports",
            "--skip-reflowing-long-strings",
            "--dry-run",
            "--set-exit-if-changed",
        ],
        files,
    )
    # Spotless still orders imports (ASCII single block, unlike the CLI --aosp
    # grouping) and removes unused ones: mirror it with the pinned jar's import
    # fixer (no --aosp, so no regrouping).
    each(
        [
            "java",
            *GJF_EXPORTS,
            "-jar",
            str(jar),
            "--fix-imports-only",
            "--dry-run",
            "--set-exit-if-changed",
        ],
        files,
    )


def staged_files():
    """Staged, still-present worktree files, repo-relative (empty outside commits)."""
    try:
        staged = subprocess.check_output(
            [
                "git",
                "diff",
                "--cached",
                "--name-only",
                "-z",
                "--diff-filter=ACMR",
                "--",
            ],
            cwd=ROOT,
            env=child_env(),
        )
    except subprocess.CalledProcessError:
        return []
    return sorted(
        os.fsdecode(name)
        for name in staged.split(b"\0")
        if name and (ROOT / os.fsdecode(name)).is_file()
    )


def format_staged():
    """Format the staged files with the same pinned formatters `format-check`
    uses, and re-stage them. Files with unstaged edits are skipped with a
    warning (formatting the worktree copy would stage unreviewed hunks), so
    only fully staged files are re-added. No Gradle (unlike `format`, which
    runs spotlessApply for Java).
    """
    files = staged_files()
    if not files:
        return
    # Fail closed: if the worktree diff cannot be read, the hook must stop
    # the commit rather than risk staging unreviewed hunks.
    dirty = git_names("diff", "--name-only") & set(files)
    if dirty:
        print(
            "pre-commit: skipping partially staged files "
            "(format them after staging, or unstage first):\n"
            + "\n".join(f"  {n}" for n in sorted(dirty))
        )
        files = [f for f in files if f not in dirty]
        if not files:
            return
    eol()
    python = [name for name in files if name.endswith(".py")]
    shell = [name for name in files if name.endswith(".sh")]
    yaml = [name for name in files if name.endswith((".yml", ".yaml"))]
    java = [name for name in files if name.endswith(".java") and in_java_roots(name)]
    if python:
        each(["ruff", "format"], python)
    if shell:
        each(["shfmt", "-i", "4", "-ci", "-w"], shell)
    if yaml:
        each(["prettier", "--write"], yaml)
    if java:
        java_version()
        jar = download(GJF_JAR, GJF_URL, GJF_SHA256)
        each(
            [
                "java",
                *GJF_EXPORTS,
                "-jar",
                str(jar),
                "--aosp",
                # Same Spotless parity as check_java: skip import sorting and
                # long-string reflows here, order imports with the fixer below.
                "--skip-sorting-imports",
                "--skip-reflowing-long-strings",
                "--replace",
            ],
            java,
        )
        each(
            [
                "java",
                *GJF_EXPORTS,
                "-jar",
                str(jar),
                "--fix-imports-only",
                "--replace",
            ],
            java,
        )
    run("git", "add", "--", *files)


def _git(root, *args):
    """git bound to this checkout's git dir.

    An invalid `.git` entry makes git fail instead of silently resolving to
    an outer repository (whose config we must never touch).
    """
    return ["git", f"--git-dir={root / '.git'}", *args]


def wire_hooks(root, strict=False):
    """Point this checkout at the versioned hooks in `.githooks`.

    Wires only when git would run no other hooks here: core.hooksPath unset at
    every scope and no active hook in the hooks dir. Otherwise warns and leaves
    git alone. Returns True when `.githooks` is the hooks path afterwards.
    With strict, failing to read or write git config exits: `just setup`
    must not pass without the hooks (pre-push runs the secret scan).
    Notices go to stderr: callers capture the stdout of `quality.py run`.
    """
    if not (root / ".git").exists() or not (root / ".githooks").is_dir():
        return False

    def git(*args, check=False):
        return subprocess.run(
            _git(root, *args),
            cwd=root,
            env=child_env(),
            stdout=subprocess.PIPE,
            text=True,
            errors="replace",  # a non-UTF-8 hooksPath must not crash every just call
            check=check,
        )

    try:
        current = git("config", "--get", "core.hooksPath").stdout.strip()
        if current == ".githooks":
            return True
        if current:
            print(
                f"warning: keeping core.hooksPath {current!r}; "
                "versioned .githooks not wired",
                file=sys.stderr,
            )
            return False
        hooks = (
            root / git("rev-parse", "--git-path", "hooks", check=True).stdout.strip()
        )
        active = (
            sorted(p.name for p in hooks.iterdir() if not p.name.endswith(".sample"))
            if hooks.is_dir()
            else []
        )
        if active:
            print(
                f"warning: keeping active hooks {', '.join(active)} in {hooks}; "
                "versioned .githooks not wired",
                file=sys.stderr,
            )
            return False
        git("config", "--local", "core.hooksPath", ".githooks", check=True)
    except (OSError, subprocess.CalledProcessError) as error:
        if strict:
            raise SystemExit(
                f"just setup: cannot wire versioned hooks in {root}: {error}"
            )
        print(
            f"warning: cannot wire versioned hooks in {root}: {error}", file=sys.stderr
        )
        return False
    print(
        "commits and pushes now run the versioned .githooks (bypass: --no-verify)",
        file=sys.stderr,
    )
    return True


def main(argv=None):
    python = canonical_python()
    if Path(sys.executable).resolve() != python.resolve():
        raise SystemExit(
            subprocess.run(
                [str(python), "-I", "-X", "utf8", __file__, *sys.argv[1:]],
                env=child_env(),
            ).returncode
        )
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=(
            "setup",
            "lint",
            "format",
            "format-check",
            "test",
            "check",
            "gradle",
            "build",
            "format-staged",
            "java-check",
            "java-format",
            "uniform-bake",
            "uniform-dump",
            "uniform-preview",
            "rig-check",
            "isolated",
        ),
    )
    parser.add_argument("args", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    # Every fast entry point wires the versioned hooks first, so a fresh
    # clone gets them on first use even when `just setup` never ran.
    # Only sets core.hooksPath when it is unset; never fails the command.
    if args.command in (
        "lint",
        "format",
        "format-check",
        "format-staged",
        "test",
        "check",
    ):
        wire_hooks(ROOT)
    if args.command == "format-staged":
        format_staged()
        return
    if args.command == "setup":
        wire_hooks(ROOT, strict=True)
        for name in ("ruff", "actionlint", "shellcheck", "shfmt", "prettier"):
            tool(name)
        return
    if args.command == "check":
        main(["lint"])
        main(["format-check"])
        return
    if args.command in ("build", "java-check", "java-format"):
        tasks = {
            "build": ["build"],
            "java-check": ["spotlessCheck", "lintJava", "build"],
            "java-format": ["spotlessApply"],
        }
        # Oracle and test JVMs must not overlap within java-check's 2 GiB build budget.
        workers = 1 if args.command == "java-check" else 2
        gradle(f"--max-workers={workers}", *tasks[args.command], *args.args)
        return
    if args.command == "gradle":
        gradle(*args.args)
        return
    if args.command in ("uniform-bake", "uniform-dump"):
        expected = 1 if args.command == "uniform-dump" else 0
        if len(args.args) != expected:
            parser.error(f"{args.command} expects {expected} arguments")
        environment = (
            {"BF_UNIFORM_DUMP": str((ROOT / args.args[0]).resolve())}
            if expected
            else {"BF_UNIFORM_BAKE": "1"}
        )
        if expected:
            Path(environment["BF_UNIFORM_DUMP"]).mkdir(parents=True, exist_ok=True)
        test = "UniformPreviewDump" if expected else "UniformAtlasBaker"
        gradle(
            "--max-workers=2",
            ":client:test",
            "--rerun",
            "--tests",
            f"*{test}*",
            environment=environment,
        )
        return
    if args.command == "uniform-preview":
        run(sys.executable, "-I", "-X", "utf8", "dev/uniforms/preview.py", *args.args)
        return
    if args.command == "rig-check":
        # Isolated Python omits sibling module lookup; rcon is project-local.
        run(
            sys.executable,
            "-I",
            "-X",
            "utf8",
            "-c",
            "import runpy, sys; sys.path.insert(0, 'dev/rig'); "
            "sys.argv=sys.argv[1:]; runpy.run_path(sys.argv[0], run_name='__main__')",
            "dev/rig/check-gameplay.py",
            *args.args,
        )
        return
    if args.command == "isolated":
        if sys.platform != "linux":
            parser.error("isolated requires Linux and a local Docker Unix socket")
        run(
            sys.executable, "-I", "-X", "utf8", "dev/rig/isolated/launch.py", *args.args
        )
        return
    all_java = False
    if args.command in ("format", "format-check") and args.args == ["--all"]:
        all_java = True
        args.args = []
    if args.args:
        parser.error("extra arguments are supported only for gradle")
    if args.command == "test":
        run(
            sys.executable,
            "-I",
            "-X",
            "utf8",
            "-m",
            "unittest",
            "discover",
            "-s",
            "dev/rig/tests",
            "-v",
        )
        if sys.platform == "linux":
            run(
                sys.executable,
                "-I",
                "-X",
                "utf8",
                "-m",
                "unittest",
                "discover",
                "-s",
                "dev/rig/isolated",
                "-p",
                "test_*.py",
                "-v",
            )
        else:
            print("The isolated Linux container rig is checked by Linux CI.")
        if os.environ.get("BF_SKIP_ORACLE") == "1":
            print("Oracle checks must run locally before release.")
            return
        # Server-side oracle, U10 and wire gates live in blockfield-server
        # (mod-server/): this repo only covers the client oracle.
        gradle("--max-workers=2", ":client:oracleTest")
        return
    files = source_files()
    python = [name for name in files if name.endswith(".py")]
    shell = [name for name in files if name.endswith(".sh")]
    yaml = [name for name in files if name.endswith((".yml", ".yaml"))]
    if args.command == "lint":
        # actionlint only shellchecks run: blocks when it finds shellcheck; the pinned one is not on PATH.
        run("actionlint", "-shellcheck", tool("shellcheck"))
        # Thin Workshop layer gate (S233-U1): the thin wiring javac cannot see
        # (closure itself is enforced by compilation, no sibling dep).
        run(
            sys.executable,
            "-I",
            "-X",
            "utf8",
            "dev/workshop-thin-check.py",
        )
        if python:
            each(["ruff", "check"], python)
        if shell:
            each(["shellcheck", "--severity=error"], shell)
        return
    eol()
    check = args.command == "format-check"
    if python:
        each(["ruff", "format", *(["--check"] if check else [])], python)
    if shell:
        each(["shfmt", "-i", "4", "-ci", "-d" if check else "-w"], shell)
    if yaml:
        each(["prettier", "--check" if check else "--write"], yaml)
    if check:
        check_java(java_files(all_java))
    if not check:
        gradle("--max-workers=2", "spotlessApply")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from error
