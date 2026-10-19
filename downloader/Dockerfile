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
COPY prepare-runtime.py /tmp/prepare-runtime.py
RUN /opt/python/bin/python3 /tmp/prepare-runtime.py /opt/python \
    && strip --strip-unneeded /opt/python/bin/python3.12 \
    && find /opt/python/lib/python3.12/lib-dynload -name '*.so' -exec strip --strip-unneeded {} +

FROM scratch AS python-artifacts
COPY --from=python-build /opt/python /opt/python
COPY --from=python-build /usr/lib64/libffi.so.7* /usr/lib64/
COPY --from=python-build /usr/share/licenses/libffi /usr/share/licenses/libffi

FROM ${KYLIN_BASE} AS runtime
COPY --from=python-artifacts / /
RUN useradd --uid 9999 --no-create-home downloader
COPY download.py flink-bootstrap.sh /app/
RUN /opt/python/bin/python3 -c "import ssl, boto3, bz2, lzma, ctypes; assert ssl.get_default_verify_paths().cafile; print(ssl.OPENSSL_VERSION)"
USER 9999
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENTRYPOINT ["/opt/python/bin/python3", "/app/download.py"]
