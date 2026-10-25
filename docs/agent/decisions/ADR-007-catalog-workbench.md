# ADR-007 Catalog 工作台与运行依赖

2026-10-25，状态：已采用，用户确认优先 JDBC + Hive；实际验证层级见验收，不能把支持目标写成全环境验收。

Catalog 管理改为目录列表、数据库/对象浏览、对象详情三栏，SQL 工作台共用浏览组件。平台登记与远端对象分离；删除登记不删除远端表，修改通过修订号保护。JDBC 复用已登记数据源及密钥，Hive 上传 hive-site.xml 并选择运行依赖，统一下载到作业 usrlib，CREATE CATALOG 使用同一路径。旧内存/湖仓登记保留兼容。

浏览只按用户操作读取：POST `/api/sql/catalogs/{id}/metadata` 的 database/table 继续兼容；POST `.../functions` 使用 database/name；GET `.../usage` 返回当前引用草稿。JSON 成功/错误封装沿用项目约定，写入仅管理员，元数据/函数探测仅管理员及开发者。每次回复携带 checkedAt/truncated，远端库表/函数最多200项、字段100项，不读业务行、不在平台加载用户 Catalog 插件。Hive 使用官方 Thrift 只读方法，2MiB/8秒预算；当前平台浏览范围为非 SASL/TLS Binary Thrift，安全协议不能降级，须明确提示。

SQL 多目录初始化按选择顺序生成 CREATE，最后 USE 主目录/数据库；默认内存目录仅会话期有效。Catalog 插件/驱动与 UDF 统一进入现有下载器依赖，不在后端加载。连接检测层级明确区分配置、协议、运行时，协议成功不证明 Flink Connector 及存储访问已通过。UDF 静态继承/签名/调用线索与业务说明用于 AI；不能当成完整源码语义。

参考：[Flink Catalog](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/dev/table/catalogs/)、[Ververica 目录与数据库](https://docs.ververica.com/docs/vvp/user-guide/sql-development/catalogs-databases?v=2.10)、[Hive 官方 Thrift 定义](https://github.com/apache/hive/blob/rel/release-3.1.3/standalone-metastore/src/main/thrift/hive_metastore.thrift)。
