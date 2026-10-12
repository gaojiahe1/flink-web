# SQL 与 Session

更新：2026-10-12。关联 [需求](../requirements/REQ-003-sql-session.md)、[决策](../decisions/ADR-001-on-demand.md)；来源：当前代码与 [功能说明](../../features.md)。

职责：SQL 草稿/修订/历史、基础与精确 Parser 检查、Application 发布、Session 配置启停、限额运行验证。SqlJobService 处理草稿关联和生成；SqlExecutionService 重新检查并提交固定 JobID，SessionClusterService 保证专用 sql-validation 唯一。

SqlParserServiceImpl 按精确版本读取 validator.jar，独立进程限制并发/内存/时间，清除业务凭据环境；缺失时明确 BASIC，不能报告 PARSER。Parser 不连接 Catalog、不加载 Connector。SqlRunner 先执行允许的初始化语句，再把 INSERT 合并 StatementSet；环境变量在容器中转义展开。

验证将原 Sink 换为 Print，最多100条/两分钟，行只留在进程内；验证标识过滤日志，未知提交仍按固定JobID清理。截止取消由单次安排的后台任务完成并重试/重启恢复，这是手动查询规则的明确例外。浏览器显式验证期间每2秒查状态，离页/终态停止。

普通 Session 可多个，验证 Session 仅一个；运行中不得改配置。管理员配置、管理员/运维启停；开发者需 SQL 编辑权限。Runner 必须手动选具体 JAR，验证和 Application 必须选；运行 Session 镜像须与 SQL 相同。动态用户 bundle 合并服务发现、拒绝冲突类，不能删除看似重复的 META-INF 服务内容。

SQL 限32 KiB/100语句，无交互式 SELECT/SQL Gateway；SQL SET 填 Flink 配置区域。删除部署草稿需清关联并迁移仍被 SQL 草稿引用的专属文件。Catalog 删除有引用保护。

已知边界：实际 Planner/Connector 语义由 Flink 执行；普通 Session 停止可能等待其任务结束；Parser1.20.3 本轮实测不代表其余版本已验证。

## 定位代码与验证

- [SqlJobServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/sql/impl/SqlJobServiceImpl.java)
- [SqlParserServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/sql/impl/SqlParserServiceImpl.java)
- [SqlExecutionServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/sql/impl/SqlExecutionServiceImpl.java)
- [SessionClusterServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/session/impl/SessionClusterServiceImpl.java)
- [ValidationJarBundle.java](../../../backend/src/main/java/com/flinkweb/service/sql/support/ValidationJarBundle.java)
- [SqlRunner.java](../../../sql-runner/src/main/java/com/flinkweb/sql/SqlRunner.java)
- [ParserMain.java](../../../sql-validator/src/main/java/com/flinkweb/validation/ParserMain.java)
- [SqlStudioView.vue](../../../frontend/src/modules/sql/pages/SqlStudioView.vue)
- [SessionsView.vue](../../../frontend/src/modules/session/pages/SessionsView.vue)
- [SessionDevelopmentIntegrationTest.java](../../../backend/src/test/java/com/flinkweb/SessionDevelopmentIntegrationTest.java)
- [SqlParserServiceTest.java](../../../backend/src/test/java/com/flinkweb/service/SqlParserServiceTest.java)
