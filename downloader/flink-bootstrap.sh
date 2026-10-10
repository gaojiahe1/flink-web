#!/bin/sh
set -eu
# Discover the plugin from this runtime image, never from the downloader or S3.
plugin=
for candidate in /opt/flink/opt/flink-s3-fs-presto-*.jar; do
  [ -f "$candidate" ] || continue
  if [ -n "$plugin" ]; then
    echo "Multiple Flink S3 Presto plugins found in /opt/flink/opt" >&2
    exit 1
  fi
  plugin="$candidate"
done
if [ -z "$plugin" ]; then
  echo "Flink S3 Presto plugin missing from /opt/flink/opt" >&2
  exit 1
fi
destination=/opt/flink/plugins/s3-fs-presto
mkdir -p "$destination"
# This dedicated emptyDir is shared by both container restarts.
for previous in "$destination"/flink-s3-fs-presto-*.jar; do
  [ ! -f "$previous" ] || rm -f "$previous"
done
cp "$plugin" "$destination/"
echo "Installed runtime S3 plugin: ${plugin##*/}"
entrypoint=${FLINK_WEB_ORIGINAL_ENTRYPOINT:-/docker-entrypoint.sh}
if [ "$entrypoint" = /flink-bootstrap/start-flink.sh ]; then
  echo "Recursive Flink entrypoint rejected" >&2
  exit 1
fi
exec "$entrypoint" "$@"
