# 元数据、权限与数据管理

更新：2026-10-13。关联 [需求](../requirements/REQ-004-storage-security.md)、[决策](../decisions/ADR-003-storage-security.md)；来源：当前代码与 [功能说明](../../features.md)。

职责：数据库配置/迁移、JSON记录修订控制、登录/角色/用户组/作业ACL、普通配置/密钥、数据源与Catalog元数据。用户登录→一次性挑战→EncryptedLoginFilter/Session→CSRF+角色/ACL；数据修改带revision，用户相关修订使旧会话失效。

SQLite 默认按文件是否存在建库或迁移，单后端实例锁、连接池1、DELETE回滚日志；MySQL另方言和连接池。V1–V5/全量DDL均保留，不把schema.sql当可删除重复文件。配置切换不迁移数据。原主密钥必须保留才能解密配置及停止前实际部署配置。

浏览器敏感字段 RSA-OAEP-SHA256+AES-GCM，挑战绑定会话/用途/目标，120秒且一次消费；后端拒绝这些字段的明文提交。普通SQL/YAML手写内容不自动保护，字段加密不替代HTTPS。普通容器密钥引用实际提交为env.value或保留显式Secret引用；2026-10-12起Flink S3 AK/SK例外，直接在flinkConfiguration展开，既有Secret配置引用按请求读取。均不新建Secret，预览/发布历史各按规则脱敏。

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

## 启动与传输取证补充（2026-10-13）

已核对当前实现：缺失数据库且 initialize=false 才拒绝自动创建；SchemaInitializer 对已有库检查完整性并补 V1–V5 缺失迁移。管理员仅在用户库为空时初始化，密码至少 12 字符且 UTF-8 最多 72 字节；已有用户不会被启动环境变量覆盖。主清单已合并 Secret 和 initialize=true，不再依赖首次安装补丁。

敏感传输用途明确为 login、user-password、config-secret，前后端须配套；一次性挑战每会话最多 8 项。启动环境变量使用原始初始密码，不能填 fwenc1 信封。旧回复中“主密钥仍为空”仅是 09-29 当时观察，当前固定非空配置的待处理事项见 [风险清单](../open-questions.md)。

- [UserServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/permission/impl/UserServiceImpl.java)：空用户库初始化及密码校验；2026-10-13 启动提示已改为当前 StatefulSet flink-web 或后端实际工作负载，保留数据库/PVC 保护说明。
- [StartupFailureAnalyzer.java](../../../backend/src/main/java/com/flinkweb/config/platform/StartupFailureAnalyzer.java)：展开启动配置异常的原因与处理步骤。
- [BootstrapAdminTest.java](../../../backend/src/test/java/com/flinkweb/service/permission/impl/BootstrapAdminTest.java)：管理员初始化回归入口。
- [secretTransport.ts](../../../frontend/src/shared/api/secretTransport.ts)：三类敏感字段传输入口。
