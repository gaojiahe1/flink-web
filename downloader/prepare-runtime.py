"""Remove build/install tools and CPython's own tests from a dedicated runtime prefix."""
import pathlib
import shutil
import sys

prefix = pathlib.Path(sys.argv[1]).resolve()
stdlib = prefix / "lib/python3.12"
if not (prefix / "bin/python3.12").is_file() or not stdlib.is_dir():
    raise SystemExit("Expected a dedicated Python 3.12 installation prefix")

# Retain runtime modules, boto3/botocore service data, package metadata and licenses.
for relative in ("include", "share/man", "lib/pkgconfig", "lib/libpython3.12.a",
                 "lib/python3.12/test", "lib/python3.12/ensurepip",
                 "lib/python3.12/idlelib", "lib/python3.12/turtledemo"):
    path = prefix / relative
    if path.is_dir():
        shutil.rmtree(path)
    else:
        path.unlink(missing_ok=True)
for path in stdlib.glob("config-*"):
    if path.is_dir():
        shutil.rmtree(path)
for path in (stdlib / "site-packages").glob("pip*"):
    if path.is_dir():
        shutil.rmtree(path)
    else:
        path.unlink(missing_ok=True)
for path in (prefix / "bin").iterdir():
    if path.name not in ("python3", "python3.12") and not path.is_dir():
        path.unlink()
for path in list(prefix.rglob("__pycache__")):
    shutil.rmtree(path)
for path in prefix.rglob("*.pyc"):
    path.unlink()
