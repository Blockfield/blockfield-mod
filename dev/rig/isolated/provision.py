"""Build-time only: prepare versioned Fabric runtimes; never copy existing worlds."""

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import urllib.request
from zipfile import ZipFile

ROOT = Path("/opt/bf")


def digest(path, algorithm="sha256"):
    with path.open("rb") as handle:
        value = hashlib.new(algorithm)
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(block)
        return value.hexdigest()


def fetch(url, dest):
    dest.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(url, timeout=90) as response, dest.open("xb") as handle:
        shutil.copyfileobj(response, handle)


def main():
    # Version strings come from the reviewed repository, not a live latest endpoint.
    props = dict(
        line.split("=", 1)
        for line in (ROOT / "gradle.properties").read_text().splitlines()
        if "=" in line and not line.startswith("#")
    )
    mc, loader, api = (
        props[k] for k in ("minecraft_version", "loader_version", "fabric_version")
    )
    server = ROOT / "server"
    server.mkdir()
    launch = server / "fabric-server-launch.jar"
    fetch(
        f"https://meta.fabricmc.net/v2/versions/loader/{mc}/{loader}/1.0.3/server/jar",
        launch,
    )
    # The bootstrap downloads its versioned dependencies. --help exits before world creation.
    subprocess.run(
        ["java", "-jar", str(launch), "--help"], cwd=server, check=True, timeout=240
    )
    assert not (server / "world").exists(), "preparation must not create/copy a world"
    api_jar = ROOT / "fabric-api.jar"
    fetch(
        f"https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{api}/fabric-api-{api}.jar",
        api_jar,
    )
    (server / "mods").mkdir(exist_ok=True)
    shutil.copy2(api_jar, server / "mods/fabric-api.jar")
    shutil.copy2(ROOT / "jars/server.jar", server / "mods/blockfield-server.jar")

    # Reuse PortableMC's API, as in ../install-client.py; do not implement another launcher.
    from portablemc.standard import Context, replace_list_vars
    from portablemc.fabric import FabricVersion

    context = Context(ROOT / "minecraft", Path("/work/client"))
    version = FabricVersion.with_fabric(mc, loader, context=context)
    version.set_auth_offline("RigPlayer", None)
    version.jvm_path = Path(shutil.which("java"))
    version.resolution = (800, 450)
    env = version.install()
    env.jvm_args[1:1] = [
        "-Xms256M",
        "-Xmx1536M",
        "-XX:+UseG1GC",
        "-Dbf.testbot.port=47777",
    ]
    natives = ROOT / "natives"
    natives.mkdir()
    for src in env.native_libs:
        if src.name.endswith((".zip", ".jar")):
            with ZipFile(src) as archive:
                for info in archive.infolist():
                    if info.filename.endswith(".so"):
                        name = info.filename.rsplit("/", 1)[-1]
                        data = archive.read(info)
                        target = natives / name
                        if target.exists() and target.read_bytes() != data:
                            raise ValueError("conflicting native library: " + name)
                        target.write_bytes(data)
        else:
            name = src.name
            end = name.rfind(".so")
            name = name[: end + 3] if end >= 0 else name
            shutil.copy2(src, natives / name)
    replacements = dict(env.args_replacements)
    replacements["natives_directory"] = str(natives)
    args = [
        *replace_list_vars(env.jvm_args, replacements),
        env.main_class,
        *replace_list_vars(env.game_args, replacements),
    ]
    if args[0].startswith("-"):
        args.insert(0, shutil.which("java"))
    if not Path(args[0]).is_absolute() or not Path(args[0]).is_file():
        raise ValueError("missing provisioned Java executable")
    (ROOT / "client-args.json").write_text(json.dumps(args))
    mods = ROOT / "client-mods"
    mods.mkdir()
    for source, name in (
        (api_jar, "fabric-api.jar"),
        (ROOT / "jars/client.jar", "blockfield-client.jar"),
        (ROOT / "jars/testbot.jar", "bftestbot.jar"),
    ):
        shutil.copy2(source, mods / name)
    # Real runtime requirements, pinned to the modpack's reviewed artifact hashes.
    dependencies = json.loads((ROOT / "dependencies.json").read_text())
    for entry in dependencies["mods"]:
        target = mods / entry["file"]
        fetch(entry["url"], target)
        if digest(target, "sha512") != entry["sha512"]:
            raise ValueError("dependency digest mismatch: " + entry["id"])
        with ZipFile(target) as archive:
            metadata = json.loads(archive.read("fabric.mod.json"))
        if metadata["id"] != entry["id"] or metadata["version"] != entry["version"]:
            raise ValueError("dependency identity/version mismatch")
        if (
            entry["id"] == "accessories"
            and entry["version"] != props["accessories_version"]
        ):
            raise ValueError("accessories lock must match build configuration")
        shutil.copy2(target, server / "mods" / entry["file"])
    # No credentials, host data or generated worlds are part of the image.
    if Path("/work").exists():
        shutil.rmtree("/work")
    inventory = {
        str(p.relative_to(ROOT)): digest(p)
        for p in sorted(ROOT.rglob("*"))
        if p.is_file()
    }
    (ROOT / "runtime-manifest.json").write_text(
        json.dumps(
            {
                "schema": 1,
                "profile": "minimal-blockfield-smoke",
                "minecraft": mc,
                "loader": loader,
                "fabricApi": api,
                "portablemc": "4.4.1",
                "source": (ROOT / "source-sha.txt").read_text().strip(),
                "files": inventory,
                "coverage": "Blockfield + Fabric API + Accessories/owo + testbot; NOT the full production modpack",
            },
            indent=2,
        )
        + "\n"
    )


if __name__ == "__main__":
    main()
