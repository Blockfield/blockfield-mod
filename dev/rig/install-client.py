"""Ставит Fabric-клиент 1.21.1 через portablemc (кэш ~/.cache/bf-mc) и пишет ~/.cache/bf-rig/run-client.sh.
Сервер не зашит: rig.sh start передаёт --quickPlayMultiplayer при запуске.
Запуск: ../with-mojang-dns.sh ~/.cache/pmc/bin/python install-client.py"""

import os
import shlex
import shutil
from pathlib import Path
from zipfile import ZipFile
from portablemc.standard import Context, replace_list_vars
from portablemc.fabric import FabricVersion

H = Path.home()
ctx = Context(H / ".cache/bf-mc", H / ".cache/bf-test-client")
v = FabricVersion.with_fabric("1.21.1", "0.19.3", context=ctx)
v.set_auth_offline(os.environ.get("BF_RIG_NAME", "Player115"), None)
v.resolution = (1280, 720)
env = v.install()
env.jvm_args[1:1] = ["-Xmx4G", "-XX:+UseG1GC", "-Dbf.testbot.port=47777"]
natives = H / ".cache/bf-natives"
shutil.rmtree(natives, ignore_errors=True)
natives.mkdir()
for src in env.native_libs:
    if src.name.endswith((".zip", ".jar")):
        with ZipFile(src) as z:
            for i in z.infolist():
                if i.filename.endswith((".so", ".dll", ".dylib")):
                    (natives / i.filename.rsplit("/", 1)[-1]).write_bytes(z.read(i))
    else:
        n = src.name
        k = n.rfind(".so")
        n = n[: k + 3] if k >= 0 else n
        shutil.copyfile(src, natives / n)
rep = dict(env.args_replacements)
rep["natives_directory"] = str(natives)
args = [
    *replace_list_vars(env.jvm_args, rep),
    env.main_class,
    *replace_list_vars(env.game_args, rep),
]
out = H / ".cache/bf-rig/run-client.sh"
out.parent.mkdir(exist_ok=True)
out.write_text(
    "#!/bin/sh\ncd "
    + str(H / ".cache/bf-test-client")
    + "\nexec "
    + " ".join(shlex.quote(a) for a in args)
    + ' "$@"\n'
)
out.chmod(0o755)
print("INSTALLED", out)
