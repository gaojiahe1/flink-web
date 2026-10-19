ARG KYLIN_BASE=macrosan/kylin:v10-sp3-2403
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY backend/pom.xml backend/pom.xml
COPY backend/vendor-repository backend/vendor-repository
COPY backend/src backend/src
COPY sql-validator/pom.xml sql-validator/pom.xml
COPY sql-validator/src sql-validator/src
COPY sql-runner/pom.xml sql-runner/pom.xml
COPY sql-runner/src sql-runner/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -f sql-validator/pom.xml -DskipTests package \
    && mkdir -p /out/validators/1.20.3 /out/validators/1.20.5 \
    && cp sql-validator/target/flink-web-sql-validator.jar /out/validators/1.20.3/validator.jar \
    && mvn -B -f sql-validator/pom.xml -Dflink.version=1.20.5 clean package \
    && cp sql-validator/target/flink-web-sql-validator.jar /out/validators/1.20.5/validator.jar \
    && mvn -B -f sql-runner/pom.xml -DskipTests package \
    && cp sql-runner/target/flink-web-sql-runner.jar /out/runner.jar \
    && mvn -B -f backend/pom.xml -DskipTests package \
    && cp backend/target/flink-web-backend-0.1.0-SNAPSHOT.jar /out/app.jar

# Offline releases override this stage with --build-context artifacts=<verified-directory>.
# Always rebuild the final image from KYLIN_BASE; never inherit the previous app image.
FROM scratch AS artifacts
COPY --from=build /out/ /

FROM eclipse-temurin:17-jre-jammy AS java
WORKDIR /extract
COPY --from=artifacts /app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination layers

FROM ${KYLIN_BASE} AS runtime
# The supported Kylin base includes CA certificates, tzdata, libstdc++, zlib,
# groupadd and useradd. No compiler, package cache or font stack is installed here.
COPY --from=java /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"
RUN java -version \
    && test -r /etc/pki/tls/certs/ca-bundle.crt \
    && groupadd --gid 10001 flinkweb && useradd --uid 10001 --gid 10001 --no-create-home flinkweb \
    && mkdir -p /app/data && chown 10001:10001 /app/data \
    && ln -s /tmp /app/appserver
WORKDIR /app
COPY --from=java /extract/layers/dependencies/ ./
COPY --from=java /extract/layers/spring-boot-loader/ ./
COPY --from=java /extract/layers/snapshot-dependencies/ ./
COPY --from=java /extract/layers/application/ ./
COPY --from=artifacts /validators/ /opt/sql-validators/
COPY --from=artifacts /runner.jar /opt/sql-runner/flink-web-sql-runner.jar
ENV FLINK_WEB_SQL_VALIDATOR_DIR=/opt/sql-validators
ENV FLINK_WEB_SQL_RUNNER_JAR=/opt/sql-runner/flink-web-sql-runner.jar
ENV FLINK_WEB_LOCAL_PATH=/app/data/flink-web.db
# BES 11.5 creates a document base below user.dir/appserver regardless of basedir.
# The symlink above redirects only those writes; /app and relative path semantics stay intact.
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
