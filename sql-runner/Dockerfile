# Flink distribution must contain the matching planner, connectors and S3 plugin.
ARG KYLIN_BASE=macrosan/kylin:v10-sp3-2403
ARG FLINK_IMAGE=flink:1.20.3-scala_2.12-java17
FROM ${FLINK_IMAGE} AS flink-dist
# Reuse the same Java 17 build distribution as the backend; only the JDK is copied.
FROM maven:3.9-eclipse-temurin-17 AS java
FROM ${KYLIN_BASE}
RUN yum install -y bash ca-certificates tzdata fontconfig libstdc++ zlib shadow-utils procps-ng hostname \
    && yum clean all && rm -rf /var/cache/dnf /var/cache/yum \
    && groupadd --gid 9999 flink \
    && useradd --uid 9999 --gid 9999 --home-dir /opt/flink --no-create-home flink
COPY --from=java /opt/java/openjdk /opt/java/openjdk
COPY --from=flink-dist --chown=flink:flink /opt/flink /opt/flink
COPY --from=flink-dist /docker-entrypoint.sh /docker-entrypoint.sh
ENV JAVA_HOME=/opt/java/openjdk FLINK_HOME=/opt/flink DISABLE_JEMALLOC=true
ENV PATH="/opt/java/openjdk/bin:/opt/flink/bin:${PATH}"
RUN java -version
COPY --chown=flink:flink target/flink-web-sql-runner.jar /opt/flink/lib/flink-web-sql-runner.jar
USER flink
WORKDIR /opt/flink
EXPOSE 8081 6123
ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["help"]
