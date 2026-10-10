#!/bin/sh
set -eu

# 从容器 DNS 配置生成运行时解析器，后端暂时不存在时仍允许前端启动。
# 独立前缀避免与 Kubernetes 为 flink-web-backend Service 注入的变量重名。
backend_host=${FLINK_WEB_PROXY_BACKEND_HOST:-flink-web-backend}
backend_port=${FLINK_WEB_PROXY_BACKEND_PORT:-8080}
case "$backend_host" in
    ''|*[!a-zA-Z0-9_.-]*) echo "Invalid FLINK_WEB_PROXY_BACKEND_HOST" >&2; exit 1 ;;
esac
# Nginx 内置解析器不使用系统 search 列表，单段 Service 名需要补全 namespace。
case "$backend_host" in
    *.*) ;;
    *)
        service_domain=$(awk '$1 == "search" { for (i=2; i<=NF; i++) if ($i ~ /^[^.]+[.]svc[.]/) { print $i; exit } }' /etc/resolv.conf)
        if [ -n "$service_domain" ]; then
            case "$service_domain" in
                *[!a-zA-Z0-9_.-]*) echo "Invalid Kubernetes DNS search domain" >&2; exit 1 ;;
            esac
            backend_host="$backend_host.$service_domain"
        fi
        ;;
esac
case "$backend_port" in
    ''|*[!0-9]*) echo "Invalid FLINK_WEB_PROXY_BACKEND_PORT" >&2; exit 1 ;;
esac
if [ "$backend_port" -lt 1 ] || [ "$backend_port" -gt 65535 ]; then
    echo "Invalid FLINK_WEB_PROXY_BACKEND_PORT" >&2
    exit 1
fi
resolvers=$(awk '$1 == "nameserver" { if (index($2, ":")) printf "[%s] ", $2; else printf "%s ", $2 }' /etc/resolv.conf)
if [ -z "$resolvers" ]; then
    echo "No DNS resolver configured in /etc/resolv.conf" >&2
    exit 1
fi
printf 'resolver %s valid=10s;\nresolver_timeout 3s;\nset $flink_web_backend "http://%s:%s";\n' "$resolvers" "$backend_host" "$backend_port" > /tmp/flink-web-upstream.conf
exec "$@"
