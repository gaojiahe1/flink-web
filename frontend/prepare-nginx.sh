#!/bin/sh
set -eu

# Export only the executable, configured MIME database and its extra runtime libraries.
# The remaining linked libraries are supplied by the same Kylin runtime base.
mkdir -p /out/usr/sbin /out/usr/lib64 /out/etc/nginx /out/usr/share/licenses
cp /usr/sbin/nginx /out/usr/sbin/
cp /etc/nginx/mime.types /out/etc/nginx/
for library in libprofiler.so.0 libunwind.so.8; do
    cp -L "/usr/lib64/$library" /out/usr/lib64/
done
# Keep installed package notices alongside the copied runtime components.
for package in nginx gperftools-libs libunwind; do
    rpm -ql "$package" | while IFS= read -r path; do
        case "$path" in
            /usr/share/licenses/*|/usr/share/doc/*)
                if [ -f "$path" ]; then
                    mkdir -p "/out$(dirname "$path")"
                    cp "$path" "/out$path"
                fi
                ;;
        esac
    done
done
