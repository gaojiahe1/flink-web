# 在已有 Flink 镜像中加入 Runner；基础镜像需要提供 Table Planner、对应连接器与 S3 插件。
ARG FLINK_IMAGE=flink:1.20.3-scala_2.12-java17
FROM ${FLINK_IMAGE}
COPY --chown=flink:flink target/flink-web-sql-runner.jar /opt/flink/lib/flink-web-sql-runner.jar
