set quiet
set dotenv-load := false
set positional-arguments
set default-script
set script-interpreter := ["python", "-I", "-X", "utf8"]

default:
    import subprocess
    raise SystemExit(subprocess.call(["just", "--list"]))

setup *args:
    import runpy, sys
    sys.argv = ["dev/quality.py", "setup", *sys.argv[1:]]
    runpy.run_path("dev/quality.py", run_name="__main__")

lint *args:
    import runpy, sys
    sys.argv = ["dev/quality.py", "lint", *sys.argv[1:]]
    runpy.run_path("dev/quality.py", run_name="__main__")

format *args:
    import runpy, sys
    sys.argv = ["dev/quality.py", "format", *sys.argv[1:]]
    runpy.run_path("dev/quality.py", run_name="__main__")

format-staged *args:
    import runpy, sys
    sys.argv = ["dev/quality.py", "format-staged", *sys.argv[1:]]
    runpy.run_path("dev/quality.py", run_name="__main__")

format-check *args:
    import runpy, sys
    sys.argv = ["dev/quality.py", "format-check", *sys.argv[1:]]
    runpy.run_path("dev/quality.py", run_name="__main__")

test *args:
    import runpy, sys
    sys.argv = ["dev/quality.py", "test", *sys.argv[1:]]
    runpy.run_path("dev/quality.py", run_name="__main__")

check *args:
    import runpy, sys
    sys.argv = ["dev/quality.py", "check", *sys.argv[1:]]
    runpy.run_path("dev/quality.py", run_name="__main__")

build *args:
    import runpy, sys
    sys.argv = ["dev/quality.py", "build", *sys.argv[1:]]
    runpy.run_path("dev/quality.py", run_name="__main__")

java-check *args:
    import runpy, sys
    sys.argv = ["dev/quality.py", "java-check", *sys.argv[1:]]
    runpy.run_path("dev/quality.py", run_name="__main__")

java-format *args:
    import runpy, sys
    sys.argv = ["dev/quality.py", "java-format", *sys.argv[1:]]
    runpy.run_path("dev/quality.py", run_name="__main__")

gradle *args:
    import runpy, sys
    sys.argv = ["dev/quality.py", "gradle", *sys.argv[1:]]
    runpy.run_path("dev/quality.py", run_name="__main__")

uniform-bake:
    import runpy, sys
    sys.argv = ["dev/quality.py", "uniform-bake"]
    runpy.run_path("dev/quality.py", run_name="__main__")

uniform-dump output:
    import runpy, sys
    sys.argv = ["dev/quality.py", "uniform-dump", *sys.argv[1:]]
    runpy.run_path("dev/quality.py", run_name="__main__")

uniform-preview *args:
    import runpy, sys
    sys.argv = ["dev/quality.py", "uniform-preview", *sys.argv[1:]]
    runpy.run_path("dev/quality.py", run_name="__main__")

rig-check *args:
    import runpy, sys
    sys.argv = ["dev/quality.py", "rig-check", *sys.argv[1:]]
    runpy.run_path("dev/quality.py", run_name="__main__")

# Linux container profile; Windows development uses blockfield-workspace.
isolated *args:
    import runpy, sys
    sys.argv = ["dev/quality.py", "isolated", *sys.argv[1:]]
    runpy.run_path("dev/quality.py", run_name="__main__")
