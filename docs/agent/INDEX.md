# 项目知识索引

更新：2026-10-23。按下表关键词选一行，再核对模块列出的代码与测试；不默认读取所有行。详细业务规则仍以 [功能说明](../features.md) 为入口，环境参数、镜像摘要和维护步骤查 [部署指南](../deployment.md)。

| 关键词/任务 | 需求 | 模块与代码导航 | 长期决策 |
| --- | --- | --- | --- |
| savepoint、停止、启动、快照、UID、纳管、删除草稿、日志、Flink UI、前端启动、Nginx、代理 | [REQ-001](requirements/REQ-001-lifecycle.md) | [作业生命周期](modules/job-lifecycle.md) | [身份与安全停止](decisions/ADR-002-safe-stop.md) |
| 刷新、轮询、Operator、状态、ACCEPTED | [REQ-001](requirements/REQ-001-lifecycle.md) | [作业生命周期](modules/job-lifecycle.md) | [按需观测](decisions/ADR-001-on-demand.md) |
| logConfiguration、日志配置、Log4j2、JAR、命名弹窗、拖动分栏、历史5次、左侧草稿列表、YAML、表单、ServiceAccount、PodTemplate、模板、JM/TM | [REQ-002](requirements/REQ-002-authoring.md) | [作业配置](modules/job-authoring.md) | [模板分层](decisions/ADR-004-templates.md) |
| SQL、核对部署SQL、启动旧SQL、AI 助手、自然语言生成、需求确认、修改对比、Session、验证、Runner、Parser、Catalog、StatementSet、TaskManager 数量、固定副本、格式化、Connector 提示、logConfiguration、RowKind、事件游标、取消延迟、共享配置编辑 | [REQ-003](requirements/REQ-003-sql-session.md) | [SQL 与 Session](modules/sql-session.md) | [按需观测与验证例外](decisions/ADR-001-on-demand.md) |
| S3、目录标记、UUID清理、文件、目录、制品、复制、下载器、插件、Kylin、镜像、Dockerfile、BES、Tomcat、OBS、checksum | [REQ-004](requirements/REQ-004-storage-security.md) | [制品与运行文件](modules/storage-runtime.md) | [存储与安全边界](decisions/ADR-003-storage-security.md) |
| AI、队列锁顺序、完成事务、日志current/previous、作业助手、统一总结、重启受理清轮、最新SQL/配置、分类取证、Pod资源、Flink异常/JVM/GC、Secret引用、可拖动分栏、大视图、Markdown、持续对话、SQL/YAML修复、确认应用、同号恢复、删除清理、ModelScope | [REQ-003](requirements/REQ-003-sql-session.md) | [AI 助手与模型服务](modules/ai-assistant.md) | [作业AI与受控修复](decisions/ADR-006-job-ai-repair.md) |
| 权限、登录、密钥、模型服务配置、SQLite、MySQL、迁移、数据源、JDBC、Kafka | [REQ-004](requirements/REQ-004-storage-security.md)；AI 新需求见 [REQ-003](requirements/REQ-003-sql-session.md) | [元数据与权限](modules/metadata-security.md) | [存储与安全边界](decisions/ADR-003-storage-security.md) |
| 治理、清理、记忆、新会话、增量、索引 | [REQ-005](requirements/REQ-005-governance.md) | [工作说明](workflow.md) | [轻量记忆](decisions/ADR-005-memory.md) |

- 项目不熟悉时：[概览](project-overview.md)、[架构](architecture.md)。
- 续接工作：[当前状态](current-state.md)、[待确认事项](open-questions.md)。
- 历史问题：[任务摘要](task-log.md)、[清理报告](cleanup-report.md)。
- 专项导航：[需求](requirements/INDEX.md)、[模块](modules/INDEX.md)、[决策](decisions/INDEX.md)。
- 如何使用与验证：[工作说明](workflow.md)、[验收记录](validation.md)。

来源标记：代码事实＝路径/方法可核对；用户要求＝明确指令或已确认功能文档；历史报告＝以前运行结果，本轮未复测时不得升级为本轮验证；推断/待确认＝不能直接实施的假设。新要求与旧记录冲突时更新原记录，不复制一份“当前版本”。
