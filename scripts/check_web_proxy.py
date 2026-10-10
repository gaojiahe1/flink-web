"""验证 Web 容器在后端缺失、上线和重建时的 DNS 恢复与代理契约。"""
import argparse
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True).strip()


def request(port, path, method="GET", data=None):
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}", method=method, data=data,
        headers={"X-CSRF-TOKEN": "test-token", "Cookie": "SESSION=test-session"},
    )
    try:
        response = urllib.request.urlopen(req, timeout=8)
    except urllib.error.HTTPError as error:
        response = error
    return response.status, response.read()


def await_response(port, path, expected, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            status, body = request(port, path)
            if status == expected:
                return body
        except OSError:
            pass
        time.sleep(1)
    raise AssertionError(f"{path} 未在 {timeout} 秒内返回 {expected}")


def check_startup_parameters(image):
    """真实容器检查显式参数，并模拟 Kubernetes Service 变量。"""
    base = ["docker", "run", "--rm", "--read-only", "--tmpfs", "/tmp:rw,uid=10001,gid=10001",
            "-e", "FLINK_WEB_BACKEND_PORT=tcp://10.96.0.20:8080"]
    for port in ("0", "65536", "tcp://10.96.0.20:8080", "abc"):
        result = subprocess.run(
            [*base, "-e", f"FLINK_WEB_PROXY_BACKEND_PORT={port}", image, "true"],
            capture_output=True, text=True,
        )
        assert result.returncode != 0 and "Invalid FLINK_WEB_PROXY_BACKEND_PORT" in result.stderr, result
    result = subprocess.run(
        [*base, "--dns-search", "qa.svc.cluster.local",
         "-e", "FLINK_WEB_PROXY_BACKEND_HOST=custom.other.svc.cluster.local",
         "-e", "FLINK_WEB_PROXY_BACKEND_PORT=18081", image,
         "cat", "/tmp/flink-web-upstream.conf"], capture_output=True, text=True,
    )
    assert result.returncode == 0, result.stderr
    assert 'http://custom.other.svc.cluster.local:18081' in result.stdout, result.stdout
    print("PASS: 独立端口参数与完整域名生效，非法端口被拒绝，Service 变量不干扰配置")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    args = parser.parse_args()
    check_startup_parameters(args.image)
    suffix = uuid.uuid4().hex[:10]
    network, web, backend = [f"flink-web-proxy-qa-{part}-{suffix}" for part in ("net", "web", "api")]
    containers = []
    docker("network", "create", network)
    try:
        containers.append(web)
        docker("run", "-d", "--name", web, "--network", network,
               "--dns-search", "qa.svc.cluster.local", "--read-only", "--tmpfs", "/tmp:rw,uid=10001,gid=10001",
               "-e", "FLINK_WEB_BACKEND_PORT=tcp://10.96.0.20:8080",
               "-e", "FLINK_WEB_BACKEND_SERVICE_HOST=10.96.0.20",
               "-e", "FLINK_WEB_BACKEND_SERVICE_PORT=8080",
               "-p", "127.0.0.1::8080", args.image)
        port = docker("port", web, "8080/tcp").rsplit(":", 1)[1]
        await_response(port, "/", 200)
        assert "flink-web-backend.qa.svc.cluster.local:8080" in docker("exec", web, "cat", "/tmp/flink-web-upstream.conf")
        await_response(port, "/api/absent", 502)
        assert docker("inspect", "-f", "{{.State.Running}}", web) == "true"
        print("PASS: Kubernetes Service 变量存在且后端不存在时 Web 仍可启动，首页正常，API 返回 502")
        containers.append(backend)
        code = (
            "require('http').createServer((req,res)=>{let body='';"
            "req.on('data',c=>body+=c);req.on('end',()=>{"
            "res.setHeader('Content-Type','application/json');"
            "res.setHeader('Set-Cookie','SESSION=reply; HttpOnly');"
            "res.end(JSON.stringify({path:req.url,method:req.method,body,"
            "csrf:req.headers['x-csrf-token'],cookie:req.headers.cookie}));});}).listen(8080)"
        )
        def start_backend():
            docker("run", "-d", "--name", backend, "--network", network,
                   "--network-alias", "flink-web-backend.qa.svc.cluster.local", "node:24-alpine", "node", "-e", code)
        start_backend()
        await_response(port, "/api/ready?key=folder%2Ffile", 200)
        status, body = request(port, "/api/write?value=one%20two", "POST", b'{"name":"qa"}')
        result = json.loads(body)
        assert status == 200 and result == {
            "path": "/api/write?value=one%20two", "method": "POST", "body": '{"name":"qa"}',
            "csrf": "test-token", "cookie": "SESSION=test-session",
        }, result
        status, body = request(port, "/flink-ui/job-qa/overview?x=1")
        assert status == 200 and json.loads(body)["path"] == "/flink-ui/job-qa/overview?x=1"
        print("PASS: 后端上线后恢复访问，路径、参数、请求体和登录/CSRF 头保持完整")
        old_ip = json.loads(docker("inspect", "-f", "{{json .NetworkSettings.Networks}}", backend))[network]["IPAddress"]
        docker("rm", "-f", backend)
        await_response(port, "/api/replaced", 502)
        holder = "flink-web-proxy-qa-holder-" + suffix
        containers.append(holder)
        docker("run", "-d", "--name", holder, "--network", network,
               "node:24-alpine", "node", "-e", "setInterval(()=>{},1000)")
        start_backend()
        new_ip = json.loads(docker("inspect", "-f", "{{json .NetworkSettings.Networks}}", backend))[network]["IPAddress"]
        assert old_ip != new_ip, "重建场景必须使用新的后端 IP"
        await_response(port, "/api/replaced", 200)
        print("PASS: 后端重建后无需重启 Web 即可恢复")
    finally:
        for container in reversed(containers):
            subprocess.run(["docker", "rm", "-f", container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["docker", "network", "rm", network], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
