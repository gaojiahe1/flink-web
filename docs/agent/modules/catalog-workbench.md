# Catalog 工作台

2026-10-26：Hive 元数据多地址回退共用8秒单调时钟预算；创建 socket 前及地址解析后再次检查剩余时间，连接上限取2秒与剩余预算较小值，连接完成及读取超时同样受剩余预算约束。不足1毫秒不再启动新连接；地址解析本身仍由系统/JVM控制，不承诺包含系统 DNS 阻塞的严格8秒墙钟上限。受控时钟/socket与现有 TCP Thrift 回归通过，未验证目标真实 Hive 环境。见 [本轮验收](../validation.md#pod诊断与证据边界修复2026-10-26)。

2026-10-25后续：主路由/catalogs，旧/datasources跳转；移除数据源页签和SQL数据源插入。CatalogConnections在工作台或JDBC配置内弹出，内层关闭保留未保存Catalog配置，连接保存后刷新候选项；管理员维护MySQL/PostgreSQL，复用原密码引用、修订和引用删除保护。兼容原存储所需的表/列占位字段不用于Catalog，不建远端表。

更新：2026-10-26。关联 [REQ-003](../requirements/REQ-003-sql-session.md)、[ADR-007](../decisions/ADR-007-catalog-workbench.md)。

管理页使用目录列表、数据库/对象列表和对象详情三栏；SQL 开发共用 CatalogExplorer。读取数据库、表/视图、字段和 Hive 函数均由用户触发，切换目录/修订或销毁组件取消旧请求，不复用迟到结果。当前账号可读的引用草稿单独展示，删除保护核对全部草稿。修改登记不修改远端对象或运行中的作业。

新登记优先 JDBC（MySQL/PostgreSQL）和 Hive；原内存/Paimon/Iceberg 登记保留兼容。JDBC 复用数据源和 Secret 引用，生成的密码为环境变量引用；当前 JDBC Catalog 适配仅接受 PREFERRED TLS 模式，不能丢弃显式 TLS 要求。PostgreSQL 浏览限登记数据库和 schema，`schema.table` 在 Flink 引用中作为单个表名引用。

Hive 可上传 hive-site.xml、选择 Hive 2.3.9/3.1.3 及匹配的 Connector/Hadoop JAR。服务端校验文件名、128KiB、XML 根节点、重复属性与外部实体，提取最多三个无凭据 Metastore URI。原 XML 不进入元数据库；运行依赖经现有下载器装入 `/opt/flink/usrlib`，CREATE CATALOG 的 hive-conf-dir 对应同一路径。旧容器目录可兼容登记，但平台不能据此声称挂载文件已验证。

平台只读浏览使用 Hive 官方 Binary Thrift 方法 get_all_databases/get_all_tables/get_table/get_functions/get_function；每次查询一层，2MiB、深度24、集合10000、对象200项/字段100项，并报告 checkedAt/truncated。当前浏览不支持 SASL/TLS/Compact/Framed，发现启用时明确拒绝，不能降级连接；Flink 运行时认证仍由上传配置、依赖、镜像和集群决定。协议连接成功不代表 Flink 插件、Hadoop 或存储权限验证通过。

SQL runtime.catalogIds 与主 catalogId 合计最多8个且去重；依次 CREATE，最后 USE 主目录/数据库。插入对象 SQL 会选择对应附加目录；设置主目录同步默认数据库。发布和专用 Session 验证共用依赖装配。Session 的非 JAR 配置须预先在该 Session 挂载并显式重启，不能仅上传验证 bundle 就假定 Hive XML 可见。

接口沿用 ApiResponse：`POST /api/sql/catalogs/{id}/metadata`（database/table）、`POST .../functions`（database/name）、`GET .../usage`，登记/删除/测试仅管理员，浏览仅管理员/开发者。Catalog 与 SQL 保存的文件识别在数据库提交前执行，草稿、历史、权限与审计保持短事务提交。

实现及验证入口：

- [FlinkCatalogServiceImpl](../../../backend/src/main/java/com/flinkweb/service/sql/catalog/impl/FlinkCatalogServiceImpl.java)、[HiveCatalogConfiguration](../../../backend/src/main/java/com/flinkweb/service/sql/catalog/support/HiveCatalogConfiguration.java)、[HiveMetastoreClient](../../../backend/src/main/java/com/flinkweb/client/catalog/HiveMetastoreClient.java)。
- [管理页](../../../frontend/src/modules/flink-catalog/pages/FlinkCatalogsView.vue)、[共用浏览组件](../../../frontend/src/modules/flink-catalog/components/CatalogExplorer.vue)。
- [集成测试](../../../backend/src/test/java/com/flinkweb/FlinkCatalogIntegrationTest.java)、[Thrift 协议测试](../../../backend/src/test/java/com/flinkweb/HiveMetastoreClientTest.java)、[XML 边界测试](../../../backend/src/test/java/com/flinkweb/HiveCatalogConfigurationTest.java)、[页面测试](../../../frontend/tests/catalog-workbench.spec.ts)。

实际验证层级和未验证范围见 [本轮验收](../validation.md#catalogudfsql与日志2026-10-25)，不能把协议替身写成真实 Hive 服务验收。
