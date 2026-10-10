ARG KYLIN_BASE=macrosan/kylin:v10-sp3-2403
FROM eclipse-temurin:17-jre-jammy AS java
FROM ${KYLIN_BASE}
RUN yum install -y ca-certificates tzdata fontconfig libstdc++ zlib shadow-utils \
    && yum clean all && rm -rf /var/cache/dnf /var/cache/yum
COPY --from=java /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"
RUN java -version
RUN groupadd --gid 10001 flinkweb && useradd --uid 10001 --gid 10001 --no-create-home flinkweb \
    && mkdir -p /app/data && chown 10001:10001 /app/data
WORKDIR /app
COPY app.jar app.jar
COPY validator.jar /opt/sql-validators/1.20.3/validator.jar
ENV FLINK_WEB_SQL_VALIDATOR_DIR=/opt/sql-validators
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
