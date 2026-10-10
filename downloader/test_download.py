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
    def test_s3_region_and_compatible_signature_configuration(self):
        with patch.dict(download.os.environ, {"S3_ENDPOINT": "https://storage.example", "AWS_REGION": "cn-north-4"}), patch.object(download.boto3, "client", create=True) as client:
            client.return_value.get_object.return_value = {"Body": io.BytesIO(b"data")}
            with download.open_source({"uri": "s3://bucket/path/job.jar"}) as stream:
                self.assertEqual(stream.read(), b"data")
            values = client.call_args.kwargs
            self.assertEqual(values["region_name"], "cn-north-4")
            self.assertEqual(values["endpoint_url"], "https://storage.example")
            config = values["config"]
            options = config if isinstance(config, dict) else config.__dict__
            self.assertEqual(options["signature_version"], "s3v4")
            self.assertEqual(options["response_checksum_validation"], "when_required")
            client.return_value.get_object.assert_called_once_with(Bucket="bucket", Key="path/job.jar")

    def test_plugin_bootstrap_needs_no_download(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(download, "BOOTSTRAP_ROOT", pathlib.Path(folder)), patch.dict(download.os.environ, {"ARTIFACT_MANIFEST": "[]", "FLINK_PLUGIN_BOOTSTRAP": "true"}), patch.object(download, "open_source") as transport:
            download.main()
            script = (pathlib.Path(folder) / "start-flink.sh").read_text()
            self.assertIn("/opt/flink/opt/flink-s3-fs-presto-*.jar", script)
            self.assertIn('exec "$entrypoint" "$@"', script)
            transport.assert_not_called()

    def test_download_without_checksum_is_atomic(self):
        manifest = '[{"uri":"s3://bucket/job.jar","filename":"job.jar"}]'
        with tempfile.TemporaryDirectory() as folder, patch.object(download, "ROOT", pathlib.Path(folder)), patch.dict(download.os.environ, {"ARTIFACT_MANIFEST": manifest}), patch.object(download, "open_source", return_value=io.BytesIO(b"data")):
            download.main()
            self.assertEqual((pathlib.Path(folder) / "job.jar").read_bytes(), b"data")
            self.assertFalse((pathlib.Path(folder) / "job.jar.part").exists())

    def test_size_limit_removes_partial(self):
        manifest = '[{"uri":"s3://bucket/job.jar","filename":"job.jar","sha256":"' + "0" * 64 + '"}]'
        with patch.object(download, "LIMIT", 2), tempfile.TemporaryDirectory() as folder, patch.object(download, "ROOT", pathlib.Path(folder)), patch.dict(download.os.environ, {"ARTIFACT_MANIFEST": manifest}), patch.object(download, "open_source", return_value=io.BytesIO(b"data")):
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

    def test_plugin_and_job_are_downloaded_to_separate_roots(self):
        manifest = '[{"uri":"s3://b/main.jar","filename":"main.jar"},{"uri":"s3://b/plugin.jar","filename":"flink-s3-fs-presto-1.20.5.jar","target":"s3-plugin"}]'
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            with patch.object(download, "ROOT", root / "user"), patch.object(download, "PLUGIN_ROOT", root / "plugin"), patch.dict(download.os.environ, {"ARTIFACT_MANIFEST": manifest}), patch.object(download, "open_source", side_effect=lambda item: io.BytesIO(b"content")):
                download.main()
            self.assertEqual([p.name for p in (root / "user").iterdir()], ["main.jar"])
            self.assertEqual([p.name for p in (root / "plugin").iterdir()], ["flink-s3-fs-presto-1.20.5.jar"])

    def test_unknown_destination_is_rejected_before_network_request(self):
        with patch.dict(download.os.environ, {"ARTIFACT_MANIFEST": '[{"uri":"s3://b/j","filename":"j.jar","target":"../../opt"}]'}), patch.object(download, "open_source") as transport:
            with self.assertRaises(ValueError):
                download.main()
            transport.assert_not_called()

if __name__ == "__main__":
    unittest.main()
