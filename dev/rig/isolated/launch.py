#!/usr/bin/env python3
"""Portable offline smoke launcher. Needs Docker; no SSH, production sync, or global process helper."""

import argparse
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile
from zipfile import ZipFile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
LABEL = "io.blockfield.isolated-session"
DOCKER_ENDPOINT = None


def docker_args(*args):
    if args and args[0] == "docker" and DOCKER_ENDPOINT:
        return ["docker", "--host", DOCKER_ENDPOINT, *args[1:]]
    return list(args)


def child_env():
    return {
        k: v
        for k, v in os.environ.items()
        if k not in ("DOCKER_HOST", "DOCKER_CONTEXT")
    }


def execute(*args, timeout=60):
    return subprocess.run(
        docker_args(*args),
        env=child_env(),
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
    ).stdout.strip()


def ensure_local_engine():
    global DOCKER_ENDPOINT
    if DOCKER_ENDPOINT:
        return DOCKER_ENDPOINT
    host = os.environ.get("DOCKER_HOST")
    context = os.environ.get("DOCKER_CONTEXT")
    if context or not host:
        args = ["docker", "context", "inspect"]
        if context:
            args.append(context)
        data = json.loads(execute(*args))
        if len(data) != 1:
            raise ValueError("ambiguous Docker context")
        host = data[0]["Endpoints"]["docker"]["Host"]
    if (
        not host
        or not host.startswith("unix:///")
        or any(c in host for c in ("\n", "\r", "?", "#"))
    ):
        raise ValueError(
            "only a local Unix Docker socket is allowed; remote contexts are refused"
        )
    DOCKER_ENDPOINT = host
    return host


def new_directory(path):
    path = Path(os.path.abspath(path))
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError("symlink output paths are forbidden")
    if path.exists():
        raise ValueError("output directory must not exist")
    path.mkdir(
        mode=0o700
    )  # Parent must already exist; never guess/create another workspace.
    return path


def mod_jar(path, mod_id, side):
    path = Path(path)
    if (
        path.is_symlink()
        or not path.is_file()
        or path.stat().st_size > 128 * 1024 * 1024
    ):
        raise ValueError("expected a regular, bounded mod JAR")
    with ZipFile(path) as archive:
        info = archive.getinfo("fabric.mod.json")
        if info.file_size > 64 * 1024:
            raise ValueError("oversized mod metadata")
        data = json.loads(archive.read(info))
    if data.get("id") != mod_id or data.get("environment") != side:
        raise ValueError("wrong mod ID or client/server side")
    return data


def select_jar(directory, prefix, mod_id, side):
    jars = [
        p
        for p in directory.glob(prefix + "*.jar")
        if not p.name.endswith(("-sources.jar", "-dev.jar"))
    ]
    if len(jars) != 1:
        raise ValueError(f"expected exactly one {side} JAR in {directory}")
    mod_jar(jars[0], mod_id, side)
    return jars[0]


def ensure_testbot():
    """Fetch the pinned TestBot sources and rebuild the jar when stale.

    Runs on every isolated build: fetch-testbot.sh is a no-op when the
    unpacked marker matches the pin, so a stale upstream/ or jar from an
    older pin is never used silently.
    """
    upstream = HERE.parent / "testbot" / "upstream"
    subprocess.run(
        ["bash", str(HERE.parent / "testbot" / "fetch-testbot.sh")], check=True
    )
    marker = (upstream / ".upstream-sha").stat().st_mtime
    libs = upstream / "build" / "libs"
    jars = (
        [
            p
            for p in libs.glob("bftestbot-*.jar")
            if not p.name.endswith(("-sources.jar", "-dev.jar"))
        ]
        if libs.is_dir()
        else []
    )
    if len(jars) != 1 or jars[0].stat().st_mtime < marker:
        for stale in jars:
            stale.unlink()
        subprocess.run(
            [
                "just",
                "gradle",
                "--no-daemon",
                "--max-workers=2",
                "-p",
                str(upstream),
                "build",
            ],
            cwd=str(REPO),
            check=True,
        )


def build(output):
    ensure_local_engine()
    output = new_directory(output)
    sha = execute("git", "-C", str(REPO), "rev-parse", "HEAD")
    if not re.fullmatch("[0-9a-f]{40}", sha):
        raise ValueError("missing source identity")
    server = select_jar(
        REPO / "server/build/libs", "blockfield-server-", "blockfield", "server"
    )
    client = select_jar(
        REPO / "client/build/libs", "blockfield-client-", "blockfield", "client"
    )
    ensure_testbot()
    testbot = select_jar(
        HERE.parent / "testbot/upstream/build/libs", "bftestbot-", "bftestbot", "client"
    )
    if (
        mod_jar(server, "blockfield", "server")
        .get("custom", {})
        .get("blockfield:commit")
        != sha[:10]
    ):
        raise ValueError("server JAR must be built from this exact clean commit")
    # Only explicit files go into the build context. Never send a checkout/home directory to Docker.
    with tempfile.TemporaryDirectory(prefix="bf-rig-build-") as temporary:
        context = Path(temporary)
        (context / "jars").mkdir()
        for source, name in (
            (server, "server"),
            (client, "client"),
            (testbot, "testbot"),
        ):
            shutil.copyfile(source, context / "jars" / (name + ".jar"))
        for name in (
            "Containerfile",
            "provision.py",
            "session.py",
            "pvp_scenario.py",
            "dependencies.json",
        ):
            shutil.copyfile(HERE / name, context / name)
        shutil.copyfile(REPO / "gradle.properties", context / "gradle.properties")
        (context / "source-sha.txt").write_text(sha + "\n")
        with (output / "build.log").open("w") as log:
            subprocess.run(
                docker_args(
                    "docker",
                    "build",
                    "--iidfile",
                    str(output / "image-id.txt"),
                    "-f",
                    str(context / "Containerfile"),
                    str(context),
                ),
                env=child_env(),
                stdout=log,
                stderr=subprocess.STDOUT,
                check=True,
                timeout=900,
            )
    image = (output / "image-id.txt").read_text().strip()
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", image):
        raise ValueError("invalid built image identity")
    (output / "build-report.json").write_text(
        json.dumps({"source": sha, "image": image}, indent=2) + "\n"
    )
    print(image)


def container_args(image, out, token, mode):
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", image):
        raise ValueError("run requires an immutable local image ID, not a mutable tag")
    if not re.fullmatch("[0-9a-f]{32}", token) or mode not in (
        "server",
        "client",
        "pvp",
    ):
        raise ValueError("invalid session identity/mode")
    if "," in str(out):
        raise ValueError("commas in mount paths are unsupported")
    return [
        "docker",
        "create",
        "--init",
        "--network",
        "none",
        "--read-only",
        "--cap-drop",
        "ALL",
        "--security-opt",
        "no-new-privileges",
        "--pids-limit",
        "512",
        "--memory",
        "6g" if mode == "pvp" else "5g",
        "--memory-swap",
        "6g" if mode == "pvp" else "5g",
        "--cpus",
        "2",
        "--shm-size",
        "256m",
        "--user",
        f"{os.getuid()}:{os.getgid()}",
        "--label",
        LABEL + "=" + token,
        "--tmpfs",
        "/tmp:rw,exec,nosuid,nodev,size=512m,mode=1777",
        "--tmpfs",
        "/work:rw,nosuid,size=1g,mode=1777",
        "--mount",
        f"type=bind,src={out},dst=/out",
        image,
        "--mode",
        mode,
        "--accept-eula",
    ]


def owned_info(cid, token):
    if not re.fullmatch("[0-9a-f]{64}", cid):
        raise ValueError("invalid container identity")
    data = json.loads(execute("docker", "inspect", cid))[0]
    if (
        data.get("Id") != cid
        or data.get("Config", {}).get("Labels", {}).get(LABEL) != token
    ):
        raise ValueError("container ownership mismatch; refusing cleanup")
    return data


def verify_isolation(info, output):
    host = info["HostConfig"]
    mounts = info.get("Mounts", [])
    if (
        host.get("NetworkMode") != "none"
        or not host.get("ReadonlyRootfs")
        or host.get("Privileged")
        or host.get("PortBindings")
        or host.get("Devices")
        or host.get("PidMode")
        or host.get("IpcMode") == "host"
    ):
        raise ValueError("unsafe container configuration")
    binds = [m for m in mounts if m.get("Type") == "bind"]
    if (
        len(binds) != 1
        or binds[0].get("Source") != str(output)
        or binds[0].get("Destination") != "/out"
    ):
        raise ValueError("unexpected host mount")
    if any(m.get("Type") not in ("bind", "tmpfs") for m in mounts):
        raise ValueError("unexpected persistent volume")
    return True


def run(image, output, mode, accept_eula):
    if not accept_eula:
        raise ValueError("explicit --accept-eula is required")
    # Validate all arguments before creating host state or processes.
    token = secrets.token_hex(16)
    container_args(image, Path(os.path.abspath(output)), token, mode)
    if os.getuid() == 0:
        raise ValueError("run from a non-root host account with Docker access")
    endpoint = ensure_local_engine()
    output = new_directory(output)
    cid = None
    report = {
        "status": "failed",
        "image": image,
        "mode": mode,
        "dockerEndpoint": endpoint,
    }
    try:
        cid = execute(*container_args(image, output, token, mode))
        info = owned_info(cid, token)
        verify_isolation(info, output)
        report["container"] = cid
        report["isolationVerified"] = True
        with (output / "container.log").open("w") as log:
            subprocess.run(
                docker_args("docker", "start", "-a", cid),
                env=child_env(),
                check=True,
                stdout=log,
                stderr=subprocess.STDOUT,
                timeout=1080,
            )
        info = owned_info(cid, token)
        evidence = json.loads((output / "report.json").read_text())
        if info["State"]["ExitCode"] != 0 or evidence.get("status") != "passed":
            raise RuntimeError(
                "isolated smoke failed; inspect report.json and process logs"
            )
        report["status"] = "passed"
    except (Exception, KeyboardInterrupt) as error:
        report["error"] = type(error).__name__ + ": " + str(error)
    finally:
        if cid:
            try:
                info = owned_info(cid, token)
                if info["State"]["Running"]:
                    execute("docker", "stop", "--time", "60", cid, timeout=75)
                execute("docker", "rm", cid)
                report["containerRemoved"] = True
            except Exception as error:
                report["status"] = "failed"
                report["cleanupError"] = str(error)
        (output / "launch-report.json").write_text(json.dumps(report, indent=2) + "\n")
    if report["status"] != "passed":
        raise RuntimeError("isolated smoke did not pass; see " + str(output))
    print("PASS: " + str(output))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    builder = sub.add_parser("build")
    builder.add_argument("--output", required=True, type=Path)
    runner = sub.add_parser("run")
    runner.add_argument("--image", required=True)
    runner.add_argument("--output", required=True, type=Path)
    runner.add_argument("--mode", choices=("server", "client", "pvp"), default="client")
    runner.add_argument("--accept-eula", action="store_true")
    args = parser.parse_args()
    try:
        if args.action == "build":
            build(args.output)
        else:
            run(args.image, args.output, args.mode, args.accept_eula)
    except (Exception, KeyboardInterrupt) as error:
        parser.exit(1, type(error).__name__ + ": " + str(error) + "\n")


if __name__ == "__main__":
    main()
