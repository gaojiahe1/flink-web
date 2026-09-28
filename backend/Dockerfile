FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
COPY sql-validator/pom.xml sql-validator/pom.xml
COPY sql-validator/src sql-validator/src
RUN mvn -B -f sql-validator/pom.xml -DskipTests package \
    && mvn -B -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --gid 10001 flinkweb && useradd --uid 10001 --gid 10001 --no-create-home flinkweb \
    && mkdir -p /app/data && chown 10001:10001 /app/data
WORKDIR /app
COPY --from=build /build/backend/target/flink-web-backend-0.1.0-SNAPSHOT.jar app.jar
COPY --from=build /build/sql-validator/target/flink-web-sql-validator.jar /opt/sql-validators/1.20.3/validator.jar
ENV FLINK_WEB_SQL_VALIDATOR_DIR=/opt/sql-validators
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
