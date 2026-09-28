# Flink Web

Spring Boot / JDK 17 + Vue 3 / TypeScript 的 Flink 管理平台。通过运维已部署的 Flink Kubernetes Operator，在固定 namespace 管理 Application 作业；前后端分离、容器部署，默认 SQLite/PVC，也支持 MySQL。

## 文档

- [功能说明](docs/features.md)：作业管理、JAR/YAML/SQL、数据源与 Catalog、权限、密钥、Operator 版本筛选及功能边界。
- [部署指南](docs/deployment.md)：本地启动、环境变量、容器/RBAC、数据库、SQL 运行镜像、S3、备份与排障。

作业列表首次进入查询一次，之后 3 秒手动刷新；不后台轮询。当前 FlinkDeployment YAML 随刷新获取，点击只展示本页脱敏快照。平台和只读 Flink UI 共用 IP:NodePort。

## 项目目录

| 目录 | 内容 |
| --- | --- |
| backend / frontend | Spring Boot 后端与 Vue 前端 |
| sql-runner / sql-validator | SQL 作业入口与独立语法校验器 |
| downloader | HTTP/S3 制品下载器 |
| deploy | 容器及 Kubernetes 部署清单 |
| docs | 功能说明、部署指南 |

建表脚本：[MySQL](backend/src/main/resources/db/mysql/schema.sql)、[SQLite](backend/src/main/resources/db/sqlite/schema.sql)。部署前按指南准备运行镜像、凭据和权限；页面可选版本不代表全部运行组合已经验收。
