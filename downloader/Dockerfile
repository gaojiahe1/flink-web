ARG KYLIN_BASE=macrosan/kylin:v10-sp3-2403
FROM ${KYLIN_BASE} AS python-build
ARG PYTHON_VERSION=3.12.15
ARG PYTHON_SHA256=de1a241a519e0a3374fea98988d0b52c886743f9d953f23be8269cc7b59c5fab
RUN yum install -y gcc make curl tar gzip openssl-devel bzip2-devel libffi-devel zlib-devel xz-devel \
    && curl -fL --retry 3 "https://www.python.org/ftp/python/${PYTHON_VERSION}/Python-${PYTHON_VERSION}.tgz" -o /tmp/python.tgz \
    && echo "${PYTHON_SHA256}  /tmp/python.tgz" | sha256sum -c - \
    && mkdir /build && tar -xzf /tmp/python.tgz -C /build --strip-components=1 \
    && cd /build && ./configure --prefix=/opt/python --with-ensurepip=install \
    && make -j2 && make install \
    && /opt/python/bin/python3 -m pip install --no-cache-dir boto3==1.42.70

FROM ${KYLIN_BASE}
RUN yum install -y openssl-libs bzip2-libs libffi zlib xz-libs ca-certificates shadow-utils \
    && yum clean all && rm -rf /var/cache/dnf /var/cache/yum \
    && useradd --uid 9999 --create-home downloader
COPY --from=python-build /opt/python /opt/python
COPY download.py flink-bootstrap.sh /app/
RUN /opt/python/bin/python3 -c "import ssl, boto3; print(ssl.OPENSSL_VERSION)"
USER 9999
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENTRYPOINT ["/opt/python/bin/python3", "/app/download.py"]
