"""Finite init-container downloader. No daemon, retries or background polling."""
import ipaddress
import json
import os
import pathlib
import socket
import urllib.parse
import urllib.request
import boto3
from botocore.config import Config

ROOT = pathlib.Path("/artifacts")
PLUGIN_ROOT = pathlib.Path("/s3-plugin")
BOOTSTRAP_ROOT = pathlib.Path("/flink-bootstrap")
LIMIT = int(os.getenv("ARTIFACT_MAX_BYTES", str(1024 * 1024 * 1024)))
ALLOWED = {h.strip().lower() for h in os.getenv("ARTIFACT_ALLOWED_HOSTS", "").split(",") if h.strip()}

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Redirects are disabled for artifact downloads")

def open_source(item):
    uri = urllib.parse.urlsplit(item["uri"])
    if uri.username or uri.password or uri.query or uri.fragment:
        raise ValueError("Credentials and query parameters are not allowed in artifact URI")
    if uri.scheme == "s3":
        client = boto3.client("s3", endpoint_url=os.getenv("S3_ENDPOINT") or None,
                              region_name=os.getenv("AWS_REGION") or os.getenv("AWS_DEFAULT_REGION") or "us-east-1",
                              config=Config(signature_version="s3v4", request_checksum_calculation="when_required",
                                            response_checksum_validation="when_required", connect_timeout=10, read_timeout=60,
                                            retries={"max_attempts": 2}, s3={"addressing_style": "path"}))
        return client.get_object(Bucket=uri.hostname, Key=uri.path.lstrip("/"))["Body"]
    if uri.scheme not in ("http", "https") or uri.netloc.lower() not in ALLOWED:
        raise ValueError("Artifact host is not explicitly allowed")
    for result in socket.getaddrinfo(uri.hostname, uri.port or (443 if uri.scheme == "https" else 80)):
        addr = ipaddress.ip_address(result[4][0])
        if addr.is_loopback or addr.is_link_local or addr.is_multicast or addr.is_unspecified:
            raise ValueError("Unsafe artifact address")
    headers = {}
    if item.get("tokenEnv"):
        if uri.scheme != "https":
            raise ValueError("Credential-bearing HTTP requests require HTTPS")
        headers["Authorization"] = "Bearer " + os.environ[item["tokenEnv"]]
    opener = urllib.request.build_opener(NoRedirect(), urllib.request.ProxyHandler({}))
    return opener.open(urllib.request.Request(item["uri"], headers=headers), timeout=60)

def main():
    if os.getenv("FLINK_PLUGIN_BOOTSTRAP") == "true":
        root = BOOTSTRAP_ROOT
        root.mkdir(parents=True, exist_ok=True)
        target = root / "start-flink.sh"
        target.write_bytes(pathlib.Path(__file__).with_name("flink-bootstrap.sh").read_text(encoding="utf-8").encode("utf-8"))
        target.chmod(0o755)
        print("Prepared runtime S3 plugin bootstrap", flush=True)
    seen = set()
    for item in json.loads(os.environ["ARTIFACT_MANIFEST"]):
        filename = item["filename"]
        if pathlib.PurePath(filename).name != filename or filename in (".", ".."):
            raise ValueError("Invalid artifact filename")
        destination = item.get("target", "artifacts")
        if destination not in ("artifacts", "s3-plugin"):
            raise ValueError("Invalid download target")
        root = PLUGIN_ROOT if destination == "s3-plugin" else ROOT
        if destination == "s3-plugin" and not (filename.startswith("flink-s3-fs-presto-") and filename.endswith(".jar")):
            raise ValueError("Invalid S3 plugin filename")
        if (destination, filename) in seen:
            raise ValueError("Duplicate download target")
        seen.add((destination, filename))
        root.mkdir(parents=True, exist_ok=True)
        target, temporary = root / filename, root / (filename + ".part")
        size = 0
        print("Download started: " + repr(filename), flush=True)
        try:
            with open_source(item) as source, temporary.open("wb") as output:
                while True:
                    block = source.read(1024 * 1024)
                    if not block:
                        break
                    size += len(block)
                    if size > LIMIT:
                        raise ValueError("Artifact exceeds download size limit")
                    output.write(block)
            temporary.chmod(0o644)
            temporary.replace(target)
            print("Downloaded: " + repr(filename), flush=True)
        finally:
            temporary.unlink(missing_ok=True)

if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Do not disclose signed URLs, tokens or SDK request details.
        print("Artifact download failed: " + type(error).__name__, flush=True)
        raise SystemExit(1)
