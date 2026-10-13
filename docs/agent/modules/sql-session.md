# SQL 与 Session

更新：2026-10-13。关联 [需求](../requirements/REQ-003-sql-session.md)、[决策](../decisions/ADR-001-on-demand.md)；来源：当前代码与 [功能说明](../../features.md)。

职责：SQL 草稿/修订/历史、基础与精确 Parser 检查、Application 发布、Session 配置启停、限额运行验证。SqlJobService 处理草稿关联和生成；SqlExecutionService 重新检查并提交固定 JobID，SessionClusterService 保证专用 sql-validation 唯一。

SqlParserServiceImpl 按精确版本读取 validator.jar，独立进程限制并发/内存/时间，清除业务凭据环境；缺失时明确 BASIC，不能报告 PARSER。Parser 不连接 Catalog、不加载 Connector。SqlRunner 先执行允许的初始化语句，再把 INSERT 合并 StatementSet；环境变量在容器中转义展开。

2026-10-12：Runner 的磁盘配置去除 pipeline.jars/classpaths 覆盖项，用户代码路径沿用 Flink 已解析的提交上下文，避免 Application 中 local URI 进入 Java URL 解码器；其余状态/S3/业务配置保留。Runner/Parser 使用 major52，已实测 JDK8/17；Parser 限制输入、数组与脚本，输出固定UTF-8。镜像与本地安装提供精确1.20.3/1.20.5 Parser；Runner下载接口固定读取安装文件、仅管理员/开发者允许。SQL/Session仍显式上传选择Runner，旧文件不自动替换。

验证将原 Sink 换为 Print，最多100条/两分钟，行只留在进程内；验证标识过滤日志，未知提交仍按固定JobID清理。截止取消由单次安排的后台任务完成并重试/重启恢复，这是手动查询规则的明确例外。浏览器显式验证期间每2秒查状态，离页/终态停止。

普通 Session 可多个，验证 Session 仅一个；运行中不得改配置。管理员配置、管理员/运维启停；开发者需 SQL 编辑权限。Runner 必须手动选具体 JAR，验证和 Application 必须选；运行 Session 镜像须与 SQL 相同。动态用户 bundle 合并服务发现、拒绝冲突类，不能删除看似重复的 META-INF 服务内容。

SQL 限32 KiB/100语句，无交互式 SELECT/SQL Gateway；SQL SET 填 Flink 配置区域。删除部署草稿需清关联并迁移仍被 SQL 草稿引用的专属文件。Catalog 删除有引用保护。

已知边界：实际 Planner/Connector 语义由 Flink 执行；普通 Session 停止可能等待其任务结束；本轮 Parser1.20.3/1.20.5、Runner1.20.3 API、Flink1.20.5/JDK17 Application/Session 实测不代表其他版本、生产集群或所有连接器已验证。

## 定位代码与验证

- [SqlJobServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/sql/impl/SqlJobServiceImpl.java)
- [SqlParserServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/sql/impl/SqlParserServiceImpl.java)
- [SqlExecutionServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/sql/impl/SqlExecutionServiceImpl.java)
- [SessionClusterServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/session/impl/SessionClusterServiceImpl.java)
- [ValidationJarBundle.java](../../../backend/src/main/java/com/flinkweb/service/sql/support/ValidationJarBundle.java)
- [SqlRunner.java](../../../sql-runner/src/main/java/com/flinkweb/sql/SqlRunner.java)
- [Application 回归](../../../scripts/check_sql_application.py)、[JDK/JAR 回归](../../../scripts/check_sql_jars.py)
- [ParserMain.java](../../../sql-validator/src/main/java/com/flinkweb/validation/ParserMain.java)
- [SqlStudioView.vue](../../../frontend/src/modules/sql/pages/SqlStudioView.vue)
- [SessionsView.vue](../../../frontend/src/modules/session/pages/SessionsView.vue)
- [SessionDevelopmentIntegrationTest.java](../../../backend/src/test/java/com/flinkweb/SessionDevelopmentIntegrationTest.java)
- [SqlParserServiceTest.java](../../../backend/src/test/java/com/flinkweb/service/SqlParserServiceTest.java)

2026-10-13 归档补核：SqlJobServiceImpl.deploymentLink 在读取时兼容失效关联；JobServiceImpl.delete 先迁移仍被 SQL 草稿引用的专属依赖，再清原目录并解除 jobId/publishedAt。SQL 有有效部署关联时不能直接删草稿，JAR 开发映射只在新建或首次编辑时登记。页面检查状态绑定当前 SQL/配置，后端提交再次检查；验证 JAR 分发不能热更新 Session 环境、非 JAR 文件或 Flink 插件。Session 分区表单与草稿删除入口已有实现/测试定义，本轮未运行。来源和历史验收见 [归档核对](../validation.md#sql-与-session-开发聊天归档核对2026-10-13)。
