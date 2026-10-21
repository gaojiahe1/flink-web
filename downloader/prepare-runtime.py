"""Prune only a marked, dedicated Python 3.12 build prefix after a full preflight."""
import pathlib
import shutil
import sys

MARKER = ".flink-web-runtime-prefix"
MARKER_CONTENT = "flink-web dedicated Python runtime v1\n"


def prepare(prefix):
    """Reject system prefixes and escaping links before deleting any build files."""
    original = pathlib.Path(prefix).absolute()
    prefix = original.resolve()
    root = pathlib.Path(prefix.anchor)
    protected = [root / name for name in
                 ("usr", "bin", "sbin", "lib", "lib64", "etc", "var",
                  "Windows", "Program Files", "Program Files (x86)")]
    if (original != prefix or prefix == root or prefix == pathlib.Path.home().resolve()
            or any(prefix == path or path in prefix.parents for path in protected)
            or prefix.name != "python"):
        raise ValueError("Refusing non-dedicated or protected runtime prefix")
    marker = prefix / MARKER
    stdlib = prefix / "lib/python3.12"
    if (not marker.is_file() or marker.is_symlink()
            or marker.read_text(encoding="utf-8") != MARKER_CONTENT
            or not (prefix / "bin/python3.12").is_file() or not stdlib.is_dir()):
        raise ValueError("Expected marked dedicated Python 3.12 runtime prefix")
    # Validate the entire tree first; a late escaping link must not cause partial pruning.
    contents = list(prefix.rglob("*"))
    for path in contents:
        if not path.resolve().is_relative_to(prefix):
            raise ValueError("Runtime prefix contains an escaping link")
    targets = [prefix / name for name in (
        "include", "share/man", "lib/pkgconfig", "lib/libpython3.12.a",
        "lib/python3.12/test", "lib/python3.12/ensurepip",
        "lib/python3.12/idlelib", "lib/python3.12/turtledemo")]
    targets += [p for p in stdlib.glob("config-*") if p.is_dir()]
    targets += list((stdlib / "site-packages").glob("pip*"))
    targets += [p for p in (prefix / "bin").iterdir()
                if p.name not in ("python3", "python3.12") and not p.is_dir()]
    targets += [p for p in contents if p.name == "__pycache__" or p.suffix == ".pyc"]
    for path in targets:
        if path.is_symlink():
            path.unlink()
        elif path.is_dir():
            shutil.rmtree(path)
        else:
            path.unlink(missing_ok=True)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: prepare-runtime.py DEDICATED_PREFIX")
    try:
        prepare(sys.argv[1])
    except ValueError as error:
        raise SystemExit(str(error)) from error
