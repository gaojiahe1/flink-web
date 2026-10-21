"""Exercise destructive-tool guards only inside isolated fixtures."""
import pathlib
import tempfile
import unittest
from unittest.mock import patch
import importlib.util

spec = importlib.util.spec_from_file_location(
    "prepare_runtime", pathlib.Path(__file__).with_name("prepare-runtime.py"))
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimePreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        self.prefix = self.root / "python"
        for name in ("bin/python3.12", "lib/python3.12/test/remove.py",
                     "lib/python3.12/site-packages/boto3/keep.py", "include/remove.h"):
            path = self.prefix / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture", encoding="utf-8")
        (self.prefix / runtime.MARKER).write_text(runtime.MARKER_CONTENT, encoding="utf-8")

    def snapshot(self):
        return {str(p.relative_to(self.root)): p.read_bytes()
                for p in self.root.rglob("*") if p.is_file()}

    def test_marked_prefix_prunes_build_files_and_retains_runtime(self):
        runtime.prepare(self.prefix)
        self.assertFalse((self.prefix / "include").exists())
        self.assertFalse((self.prefix / "lib/python3.12/test").exists())
        self.assertTrue((self.prefix / "bin/python3.12").is_file())
        self.assertTrue((self.prefix / "lib/python3.12/site-packages/boto3/keep.py").is_file())

    def test_unmarked_prefix_rejected_without_deletion(self):
        (self.prefix / runtime.MARKER).unlink()
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, "marked"):
            runtime.prepare(self.prefix)
        self.assertEqual(before, self.snapshot())

    def test_protected_home_rejected_even_with_marker_without_deletion(self):
        before = self.snapshot()
        with patch.object(pathlib.Path, "home", return_value=self.prefix):
            with self.assertRaisesRegex(ValueError, "protected"):
                runtime.prepare(self.prefix)
        self.assertEqual(before, self.snapshot())

    def test_escaping_link_rejected_before_any_deletion(self):
        outside = self.root / "outside"
        outside.mkdir()
        (outside / "keep").write_text("keep", encoding="utf-8")
        try:
            (self.prefix / "escape").symlink_to(outside, target_is_directory=True)
        except OSError:
            self.skipTest("Host does not allow symlink creation; also run in Linux image")
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, "escaping"):
            runtime.prepare(self.prefix)
        self.assertEqual(before, self.snapshot())
