import importlib.util
import io
import pathlib
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

# Unit-test filesystem and transport policy without requiring an installed AWS SDK.
# The container installs the pinned real boto3 dependency.
try:
    import boto3
except ImportError:
    sys.modules["boto3"] = types.ModuleType("boto3")
    sys.modules["botocore"] = types.ModuleType("botocore")
    config = types.ModuleType("botocore.config")
    config.Config = lambda **kwargs: kwargs
    sys.modules["botocore.config"] = config
spec = importlib.util.spec_from_file_location("download", pathlib.Path(__file__).with_name("download.py"))
download = importlib.util.module_from_spec(spec)
spec.loader.exec_module(download)

class DownloadTest(unittest.TestCase):
    def test_verified_download_is_atomic(self):
        manifest = '[{"uri":"s3://bucket/job.jar","filename":"job.jar","sha256":"3a6eb0790f39ac87c94f3856b2dd2c5d110e6811602261a9a923d3bb23adc8b7"}]'
        with tempfile.TemporaryDirectory() as folder, patch.object(download, "ROOT", pathlib.Path(folder)), patch.dict(download.os.environ, {"ARTIFACT_MANIFEST": manifest}), patch.object(download, "open_source", return_value=io.BytesIO(b"data")):
            download.main()
            self.assertEqual((pathlib.Path(folder) / "job.jar").read_bytes(), b"data")
            self.assertFalse((pathlib.Path(folder) / "job.jar.part").exists())

    def test_bad_checksum_removes_partial(self):
        manifest = '[{"uri":"s3://bucket/job.jar","filename":"job.jar","sha256":"' + "0" * 64 + '"}]'
        with tempfile.TemporaryDirectory() as folder, patch.object(download, "ROOT", pathlib.Path(folder)), patch.dict(download.os.environ, {"ARTIFACT_MANIFEST": manifest}), patch.object(download, "open_source", return_value=io.BytesIO(b"data")):
            with self.assertRaises(ValueError):
                download.main()
            self.assertEqual(list(pathlib.Path(folder).iterdir()), [])

    def test_unlisted_host_and_metadata_address_are_blocked(self):
        with patch.object(download, "ALLOWED", set()):
            with self.assertRaises(ValueError):
                download.open_source({"uri":"https://unlisted.example/job.jar"})
        with patch.object(download, "ALLOWED", {"169.254.169.254"}):
            with self.assertRaises(ValueError):
                download.open_source({"uri":"http://169.254.169.254/job.jar"})

    def test_path_traversal_and_redirect_are_blocked(self):
        with patch.dict(download.os.environ, {"ARTIFACT_MANIFEST": '[{"filename":"../escape"}]'}):
            with self.assertRaises(ValueError):
                download.main()
        with self.assertRaises(ValueError):
            download.NoRedirect().redirect_request(None,None,302,None,None,"https://elsewhere.example")

if __name__ == "__main__":
    unittest.main()
