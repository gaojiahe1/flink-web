# 元数据、权限与数据管理

更新：2026-10-12。关联 [需求](../requirements/REQ-004-storage-security.md)、[决策](../decisions/ADR-003-storage-security.md)；来源：当前代码与 [功能说明](../../features.md)。

职责：数据库配置/迁移、JSON记录修订控制、登录/角色/用户组/作业ACL、普通配置/密钥、数据源与Catalog元数据。用户登录→一次性挑战→EncryptedLoginFilter/Session→CSRF+角色/ACL；数据修改带revision，用户相关修订使旧会话失效。

SQLite 默认按文件是否存在建库或迁移，单后端实例锁、连接池1、DELETE回滚日志；MySQL另方言和连接池。V1–V5/全量DDL均保留，不把schema.sql当可删除重复文件。配置切换不迁移数据。原主密钥必须保留才能解密配置及停止前实际部署配置。

浏览器敏感字段 RSA-OAEP-SHA256+AES-GCM，挑战绑定会话/用途/目标，120秒且一次消费；后端拒绝这些字段的明文提交。普通SQL/YAML手写内容不自动保护，字段加密不替代HTTPS。配置的加密值实际提交为env.value，不新建Secret；已有显式Secret引用保留，预览/发布历史各按规则脱敏。

角色与作业范围同时生效：ADMIN管理；DEVELOPER授权范围开发/部署；OPERATOR运行操作但不删平台草稿；VIEWER只读。新作业归创建者，旧无ACL沿用旧角色边界，不能擅自删除兼容路径。

数据源：MySQL/PostgreSQL JDBC、Kafka/UpsertKafka元数据探测及filesystem/DataGen/Print配置；Catalog内存/JDBC/Hive/Paimon/Iceberg按各自边界初始化。探测不读业务行，不提供远端建删表；JDBC一次最多200库/表、100字段。TCP探测仅管理员3秒超时，不能证明Flink网络连通。Kafka当前PLAINTEXT，SASL/SSL不是已实现能力。

已知边界：共享登记不是租户隔离；真实MySQL及提供商验收待做，固定部署初始化凭据须另作安全决策。

## 定位代码与验证

- [StorageConfiguration.java](../../../backend/src/main/java/com/flinkweb/datasource/metadata/StorageConfiguration.java)
- [SchemaInitializer.java](../../../backend/src/main/java/com/flinkweb/datasource/metadata/SchemaInitializer.java)
- [RecordRepository.java](../../../backend/src/main/java/com/flinkweb/repository/metadata/RecordRepository.java)
- [AccessServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/permission/impl/AccessServiceImpl.java)
- [SecretTransportServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/permission/impl/SecretTransportServiceImpl.java)
- [EncryptedLoginFilter.java](../../../backend/src/main/java/com/flinkweb/security/permission/EncryptedLoginFilter.java)
- [VariableRenderer.java](../../../backend/src/main/java/com/flinkweb/service/job/creation/support/VariableRenderer.java)
- [DataSourceProbeServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/datasource/impl/DataSourceProbeServiceImpl.java)
- [FlinkCatalogServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/sql/catalog/impl/FlinkCatalogServiceImpl.java)
- [PlatformIntegrationTest.java](../../../backend/src/test/java/com/flinkweb/PlatformIntegrationTest.java)
- [LocalStorageTest.java](../../../backend/src/test/java/com/flinkweb/datasource/metadata/LocalStorageTest.java)
