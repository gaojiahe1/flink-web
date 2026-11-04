"""Read-only verification of local single-platform images against a Docker V2 registry."""
import argparse
import gzip
import hashlib
import json
import subprocess
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", required=True, help="Explicit registry URL, including http/https")
    parser.add_argument("--image", action="append", required=True, help="Full local image name and tag")
    args = parser.parse_args()
    registry = urllib.parse.urlsplit(args.registry)
    if (registry.scheme not in {"http", "https"} or not registry.hostname
            or registry.username or registry.password or registry.query or registry.fragment
            or registry.path not in {"", "/"}):
        parser.error("Registry must be an explicit root URL without credentials")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    base = args.registry.rstrip("/")
    verified_layers = {}
    results = []

    def get(path, limit):
        req = urllib.request.Request(base + path, headers={"Accept":
            "application/vnd.docker.distribution.manifest.v2+json, application/vnd.oci.image.manifest.v1+json"})
        with opener.open(req, timeout=30) as response:
            body = response.read(limit + 1)
            if len(body) > limit:
                raise RuntimeError("Registry metadata exceeds its size budget")
            return body, response.headers

    def digest(value):
        return "sha256:" + hashlib.sha256(value).hexdigest()

    for image in args.image:
        host, repository_tag = image.split("/", 1)
        if host != registry.netloc or "@" in repository_tag or ":" not in repository_tag:
            parser.error("Each image must use the selected registry and an explicit tag")
        repository, tag = repository_tag.rsplit(":", 1)
        prefix = "/v2/" + urllib.parse.quote(repository, safe="/")
        local = json.loads(subprocess.check_output(["docker", "image", "inspect", image], text=True))[0]
        body, headers = get(prefix + "/manifests/" + urllib.parse.quote(tag, safe=""), 1024 * 1024)
        manifest_digest = digest(body)
        if headers.get("Docker-Content-Digest") != manifest_digest:
            raise RuntimeError("Manifest response digest mismatch: " + image)
        manifest = json.loads(body)
        if manifest.get("schemaVersion") != 2 or "layers" not in manifest:
            raise RuntimeError("Expected a single-platform schema-2 manifest: " + image)
        descriptor = manifest["config"]
        config_body, _ = get(prefix + "/blobs/" + descriptor["digest"], 1024 * 1024)
        if (digest(config_body) != descriptor["digest"] or len(config_body) != descriptor["size"]
                or descriptor["digest"] != local["Id"]):
            raise RuntimeError("Local/remote image config mismatch: " + image)
        config = json.loads(config_body)
        expected = local["RootFS"]["Layers"]
        if config["rootfs"]["diff_ids"] != expected or len(expected) != len(manifest["layers"]):
            raise RuntimeError("Root filesystem layer ordering mismatch: " + image)
        compressed_bytes = 0
        for layer, diff_id in zip(manifest["layers"], expected):
            layer_digest = layer["digest"]
            compressed_bytes += layer["size"]
            cached = verified_layers.get(layer_digest)
            if cached:
                if cached != (layer["size"], diff_id):
                    raise RuntimeError("Shared layer descriptor mismatch")
                continue
            if "gzip" not in layer["mediaType"]:
                raise RuntimeError("This verifier expects gzip layers: " + image)
            compressed_hash, uncompressed_hash = hashlib.sha256(), hashlib.sha256()
            with opener.open(base + prefix + "/blobs/" + layer_digest, timeout=60) as response:
                reader = HashingReader(response, compressed_hash, layer["size"])
                with gzip.GzipFile(fileobj=reader) as archive:
                    while block := archive.read(1024 * 1024):
                        uncompressed_hash.update(block)
                # Include trailing bytes in the compressed digest, if any.
                while reader.read(65536):
                    pass
            if (reader.count != layer["size"] or "sha256:" + compressed_hash.hexdigest() != layer_digest
                    or "sha256:" + uncompressed_hash.hexdigest() != diff_id):
                raise RuntimeError("Registry layer content mismatch: " + image)
            verified_layers[layer_digest] = (reader.count, diff_id)
        results.append({"image": image, "manifest": manifest_digest, "config": local["Id"],
                        "layers": len(expected), "compressedBytes": compressed_bytes,
                        "logicalBytes": local["Size"], "platform": local["Os"] + "/" + local["Architecture"],
                        "user": local["Config"]["User"]})
    print(json.dumps(results, indent=2))


class HashingReader:
    """Count and hash compressed bytes while gzip streams bounded output chunks."""
    def __init__(self, response, checksum, limit):
        self.response, self.checksum, self.limit = response, checksum, limit
        self.count = 0

    def read(self, size=-1):
        data = self.response.read(min(size, 65536) if size >= 0 else 65536)
        self.count += len(data)
        if self.count > self.limit:
            raise RuntimeError("Registry layer exceeds its declared size")
        self.checksum.update(data)
        return data


if __name__ == "__main__":
    main()
