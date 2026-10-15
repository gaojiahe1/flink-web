# AI 助手与模型服务

更新：2026-10-15。关联 [REQ-003](../requirements/REQ-003-sql-session.md)、[元数据权限](metadata-security.md)。用户接受侧栏设计，并明确要求作业报错分析和模型添加；本模块为第一阶段实现，不包含自动执行、生产审批或完整 Planner。

## 入口与代码

- SQL 开发右侧「AI 助手」：生成、解释、修复、数据定义；选登记数据源或编辑当前作业字段/Connector 草稿，确认需求后调用。建议先看差异再显式应用，重新检查/验证；原有保存、发布流程保留。
- 作业列表：本次观测状态含错误/FAILED 时显示「AI 分析」，主动读取有权访问的诊断及可选脱敏日志，展示原因、证据、未知项和解决步骤；不执行运行操作。
- 系统设置 → 模型服务：仅管理员增删改、测试；按用途选模型，外部发送须每次明确允许。支持兼容 Chat Completions 的非流式 JSON 响应；Base URL 填到 `/v1` 等接口前缀，程序追加 `/chat/completions`。未配置真实模型时不能生成。
- [模型客户端](../../../backend/src/main/java/com/flinkweb/client/ai/AiModelClient.java)、[模型服务](../../../backend/src/main/java/com/flinkweb/service/ai/impl/AiModelServiceImpl.java)、[任务服务](../../../backend/src/main/java/com/flinkweb/service/ai/impl/AiTaskServiceImpl.java)、[内容防护](../../../backend/src/main/java/com/flinkweb/service/ai/support/AiContentGuard.java)。
- [前端模块](../../../frontend/src/modules/ai/api/ai.ts)、[侧栏](../../../frontend/src/modules/ai/components/AiSqlAssistant.vue)、[字段表单](../../../frontend/src/modules/ai/components/AiDataDefinitionEditor.vue)、[模型页面](../../../frontend/src/modules/system/pages/ModelsView.vue)。

## 契约与边界

| 接口 | 权限与行为 |
| --- | --- |
| GET `/api/ai/models` | 登录用户仅见启用模型的名称、实际模型标识与用途；`management=true` 仅管理员读取配置，无密钥正文 |
| PUT/DELETE `/api/ai/models/{id}` | 管理员，更新/删除带 revision；新增密钥复用 config-secret 一次性加密信封，target 为模型 id；空值保留，clearKey 显式清除 |
| POST `/api/ai/models/{id}/test` | 管理员，固定非业务提示，验证兼容 JSON 响应 |
| POST `/api/ai/tasks` | ADMIN/DEVELOPER 可 SQL/definition，OPERATOR 仅 diagnosis；requestId 幂等，返回 202 任务；外部模型需要 allowExternal |
| GET `/api/ai/tasks/{id}`；POST `/{id}/cancel` | 仅创建用户且重新核验 SQL/作业权限；取消先持久化，再中断调用，迟到完成不能覆盖终态 |
| GET `/api/ai/tasks?sqlId=...` | 当前用户该 SQL 最近二十条生成/解释/修复/定义任务；不提供跨用户历史 |
| POST `/api/ai/definitions/compile` | ADMIN/DEVELOPER，仅编译当前定义并列待补项，不写共享数据源 |

模型/任务复用 `RecordRepository` 的 `ai_models`/`ai_tasks` JSON 记录，无新表迁移；SQLite 仍限定单后端实例。密钥用现有主密钥 AES-GCM 加密，AAD 绑定模型 id；没有新主密钥或明文配置副本。禁止将实际凭据写入文档。

并发为两线程、等待六项，每账号一个活动任务；重启将未完成任务置失败，不重放计费请求。客户端禁止重定向，校验目标地址；内网/本机和 HTTP 需要管理员显式允许，链路本地/元数据地址仍拒绝。请求有超时与响应上限。模型不持有平台写工具。

2026-10-15：定义输入/模型输出均逐项验证对象、字符串表名/Connector、列数组及列名称/类型、可选布尔属性；不要求连接参数已齐，仍由编译给待补项。前端合并前对现有及建议完整检查，异常保留原文。AiContentGuard 每次公开 redact/sanitize 建立一次局部密钥快照，所有文本叶子复用并保留既有替换顺序；无进程级明文缓存，下次调用重新读取/解密。

useAiTask 保留原 requestId 和不可变请求副本：创建失败先 GET 原任务，存在则继续观察；未确认时禁新建并提供核对/取消。主动重试再 GET，仅 404 才用同号原请求重发；取消失败保留身份并可重试，迟到活动响应继续取消，不覆盖新上下文。SQL 和作业分析共用该逻辑。生成/解释/修复模式变化立即失效；编译用单调上下文/请求版本，历史还核对 SQL ID，组件销毁不回填，未增加后台模型任务。

上下文只含选中定义/当前 SQL 或归属核验后的诊断、最多两个容器各 120 行日志；不发送完整运行 YAML/env。已知配置/模型密钥及常见凭据模式遮蔽，输入与输出拒绝疑似明文凭据；自由文本脱敏不是任意业务敏感信息的完整识别，外部发送仍需用户确认。

确定性编译复用 DataSourceService；模型只生成临时 VIEW/INSERT 转换逻辑，拒绝其他 DDL/SET 与 WITH 参数覆盖。确认的 Kafka 位点作用于定义副本；事件时间须匹配水位线。BASIC 检查不等于语义或运行成功。应用前核对上下文版本，原有手动参数优先，编辑 SQL/需求/定义使旧建议失效；不自动保存、发布或启动。当前定义属于侧栏草稿，应用生成 SQL 后随 SQL 正常保存，不单独登记共享数据源。

## 验证入口

[后端集成](../../../backend/src/test/java/com/flinkweb/AiAssistantIntegrationTest.java)、[页面回归](../../../frontend/tests/ai-assistant.spec.ts)。本地协议替身用于验证模型链路、权限、加密、取消和诊断；不能据此宣称某个真实模型厂商或生产 Kubernetes 已认证。实际结果见 [验收记录](../validation.md)。

[快照计数回归](../../../backend/src/test/java/com/flinkweb/service/AiContentGuardTest.java) 验证多叶子仅解密各密钥一次、结果等价及后续轮换可见；异常定义、断响应恢复、同号重试、取消失败及旧异步回填由上述用例覆盖。
