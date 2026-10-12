# 项目概览

更新：2026-10-12。依据仓库构建定义、入口和功能文档核实，不代表全部运行组合已认证。

Flink Web 在固定 namespace 中维护 FlinkDeployment 的声明与平台草稿，管理 Application 作业与 Session 集群。Operator 由运维安装，负责实际协调。支持 JAR/YAML、SQL 开发与验证、状态快照、S3 文件、数据源/Catalog、用户组与作业权限。

| 组件 | 实际技术/定义 | 职责 |
| --- | --- | --- |
| backend | [Spring Boot 3.5.16 / Java 17 / Maven](../../backend/pom.xml) | Spring MVC/Security、JdbcTemplate、SQLite/MySQL、Kubernetes HTTP、AWS SDK 2.55.2 |
| frontend | [Vue 3.5.43 / TypeScript 5.9.3 / Vite 8.3.0](../../frontend/package.json) | Vue Router、CodeMirror、自有样式与组件；无 Element Plus/Pinia 依赖 |
| sql-runner | [独立 Maven JAR](../../sql-runner/pom.xml) | 默认 Flink 1.20.3，编译 release 11，Flink API provided |
| sql-validator | [独立 Maven shaded JAR](../../sql-validator/pom.xml) | Java 17，默认精确 Flink 1.20.3/Calcite Parser |
| downloader | [Kylin / Python 3.12.15 / boto3 1.42.70](../../downloader/Dockerfile) | HTTP/S3 文件准备和 Flink 启动脚本 |
| deploy / scripts | Compose、Kubernetes 与维护检查 | 单副本 StatefulSet，两个镜像容器；平台元数据库 PVC |

没有顶层 Maven reactor；三个 Java 组件分别构建。源码按 backend 技术层/功能子包、frontend modules/shared 组织；保留现有路径。Parser 源码目录与 `sql-validators` 运行安装目录用途不同。

本地构建与启动、配置变量均复用 [部署指南](../deployment.md#2-本地开发启动)；不要复制或生成一份不同的部署说明。Windows 当前默认 Java 为 8，运行验证须显式使用已安装 JDK 17 和 Maven 3.8.7。Node v24.15.0 满足 engines。离线依赖当前位于 `.tools/m2`，不应无故删除。

外部依赖：Operator/CRD 与授权 ServiceAccount、Flink REST、S3 兼容服务、可选 MySQL；数据源连接可能涉及 JDBC/Kafka。平台 Kafka 探测仅查元数据，不消费业务消息。平台不支持多集群、多作业 namespace 或 FlinkSessionJob。

当前状态和验收边界见 [状态](current-state.md) 与 [待确认](open-questions.md)。
