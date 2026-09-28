"""Finite init-container downloader. No daemon, retries or background polling."""
import hashlib
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
                              config=Config(connect_timeout=10, read_timeout=60, retries={"max_attempts": 2}, s3={"addressing_style": "path"}))
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
    ROOT.mkdir(parents=True, exist_ok=True)
    for item in json.loads(os.environ["ARTIFACT_MANIFEST"]):
        filename = item["filename"]
        if pathlib.PurePath(filename).name != filename or filename in (".", ".."):
            raise ValueError("Invalid artifact filename")
        target, temporary = ROOT / filename, ROOT / (filename + ".part")
        digest, size = hashlib.sha256(), 0
        try:
            with open_source(item) as source, temporary.open("wb") as output:
                while True:
                    block = source.read(1024 * 1024)
                    if not block:
                        break
                    size += len(block)
                    if size > LIMIT:
                        raise ValueError("Artifact exceeds download size limit")
                    digest.update(block)
                    output.write(block)
            if digest.hexdigest().lower() != item["sha256"].lower():
                raise ValueError("Artifact checksum mismatch")
            temporary.replace(target)
            print("Downloaded and verified: " + filename, flush=True)
        finally:
            temporary.unlink(missing_ok=True)

if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Do not disclose signed URLs, tokens or SDK request details.
        print("Artifact download failed: " + type(error).__name__, flush=True)
        raise SystemExit(1)
