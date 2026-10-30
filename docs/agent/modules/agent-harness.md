# Flink AI Harness 工作台

2026-10-30恢复与跨场景扩展：会话类型用于组织历史，关联作业固定，模型通过每轮 `plan_update.intent` 选择 SQL开发/状态/诊断/修复/评测；实际使用状态/取证/修复工具同步本轮场景。SQL已关联作业时直接开放 `job_status`、`evidence_collect`、`repair_propose`，无需另建诊断会话。只问状态不会提供SQL/修复提案工具；修复仍沿用完整差异、恢复方式、UID/spec/修订及原请求号批准边界。

2026-10-30：SQL 开发可从空白需求进入，sqlId 可空，仍可选择已有草稿继续开发。未绑定时只构造内存空白基准，需求确认/SQL检查/候选生成不创建草稿；候选显示拟创建名称，明确批准后同一事务创建 SQL 草稿、绑定会话并结算应用，同号重试不会重复创建，名称冲突拒绝覆盖。删除私有会话保留已创建草稿。取消 Future 同时 executor.remove，及时释放排队容量，保留 done 清理及运行中断语义。

2026-10-30补充：取消强制需求确认与调用次数上限。sql_propose 从首轮开放，可选 imageId 只能引用新环境下的登记镜像，按用户要求选择实际 Flink 版本；运行配置由服务端生成并审阅。模型仅返回 SQL 文本或 sql_check 时，控制程序提醒登记真实候选，仍缺失则明确失败，不冒充已保存。提示词采用 [Flink1.20官方DataGen说明](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/connectors/table/datagen/) 的选项，缺口一次合并且不反复询问已回答问题。

更新：2026-10-30。关联 [REQ-003](../requirements/REQ-003-sql-session.md)、[原 AI 助手](ai-assistant.md)、[ADR-008](../decisions/ADR-008-agent-harness.md)。首期覆盖作业诊断/最小参数修复、SQL 开发、真实执行轨迹结构评测；尚不支持通用代码仓库开发、任意 Shell、分布式工作流或自动业务验收。

## 页面与确认

独立 `/ai`，菜单“AI 工作台”；ADMIN/DEVELOPER/OPERATOR 可进入，SQL 开发和应用候选要求 ADMIN/DEVELOPER。私有会话列表可收起，正文13px、辅助12px、说明11px。提交后先显示“正在调用模型制定执行计划”，收到模型计划后展示，真实模型内容由 SSE 快照持续更新；工具输入输出、事件和上下文清单默认折叠，结果和关键信息留在正文，不使用对话表格。

审阅完整 SQL/YAML 差异并明确确认后才调用应用。诊断可仅保存，或选择恢复方式、接受影响后保存/发布/启动或重启，沿用原修订、UID、spec 指纹、dry-run、原操作号。SQL 可仅保存，或在运行配置可用、无已发布作业且使用 stateless 时确认创建/发布/首次运行；运行配置与实际镜像展示在候选中，首次运行需明确确认集群资源与 INSERT 影响。已有发布作业可在同一SQL会话使用取证/修复工具，审阅后保存或选择恢复方式启动/重启；普通 sql_propose 仍只保存已有作业的SQL草稿。ACCEPTED 不等于 RUNNING；三秒手动核验须匹配操作注解、保存修订、资源归属及协调代际，业务效果另外验收。

## 六层实现

- 上下文：每轮冻结最新草稿、共享总结、版本/摘要及来源清单，初始用户目标单独保留，保留轮次的全部用户输入继续传递，助手回复最多四轮；草稿变化使执行失效。共享总结和私有记忆不是已验证事实。评测冻结来源会话实际结构轨迹，不重放工具。原始上下文不返回浏览器。
- 工具：按场景/角色发现 `plan_update`、`job_status`、`context_read`、`memory_update`、`evidence_collect`、`repair_propose`、`requirements_propose`、`sql_check`、`sql_propose`、`trace_inspect`。服务端重验白名单及参数，目标来自会话，模型没有改参数/重启写工具。
- 编排：不限制模型请求、工具动作及单步原生调用次数，工具串行；最近12步检测规范化输入/结果，相同失败或读取重复3次、连续失败6次、无实质进展12步会STALLED，第二次重复先给恢复提示；记忆/计划改写不构成推进，有新检查/证据/候选可持续执行，不设总次数。SQL失败提供行号和附近片段。上下文超过180000字符时保留初始目标、用户历史、当前产物及完整最新工具块后压缩，单块仍超限明确失败；过程滚动保留100项，统计独立累计。两名本地工作线程、六个排队位置、每账号一个活动任务。计划由模型首先调用 plan_update 提供1–6个具体步骤，不执行其他未开放工具；兼容模型编号/项目符号计划，但无固定文本兜底。场景意图不是写授权，实际调用由控制程序记录，不把模型文字当执行证明。
- 记忆状态：四个私有集合保存会话/轮次/候选/应用；压缩记忆为 PROPOSED 并带来源轮次，不回写共享总结。owner/epoch/用户及模型修订防迟到响应复活。网络不入数据库事务，生命周期锁先于短事务。
- 评估观测：真实事件包含模型步骤、工具结果、计划、恢复/停止和上下文清单；会话每次用户消息对关联作业读取轻量当前状态，job_status与三秒手动刷新按需补充Operator错误、Pod/容器和近期告警，读取失败显式UNKNOWN、缺失MISSING，不退回旧成功。原操作证明还核对采集前后UID/spec/generation/操作注解/状态和草稿修订，变化则不确认。结构评测统计实际调用/失败/状态，明确不测结论正确率和生产业务结果。SSE 断线重连原任务，页面不轮询集群。
- 约束恢复：沿用模型地址限制、不跟随重定向；完整帧/完整参数/终止状态和大小预算通过后才执行。输出按登记凭据和常见格式脱敏，尾部缓冲保护分片凭据。供应商内部推理仅当前请求链内传回，不进入页面或持久化；网络回调恢复发起者安全上下文并重新核对身份。重启仅标记中断/原申请待核对，不重放模型或部署。

## 删除与保留

每用户最多20个会话，每会话保留最近10轮；裁剪最旧轮次时清理关联方案/应用。删除带会话修订，事务内清理正文、候选、应用和直接关联评测，取消本地调用/关闭流；保留共享草稿、总结、运行审计与保存点。作业/SQL 实际删除时清理所有用户关联会话；旧助手清轮和实际领域删除使用不同事件。

SAVING/SUBMITTING/UNKNOWN、重启的 SAVED、缺少意图记录的中断原申请，必须先核对/继续原号，不删除恢复入口或另起方案。明确在保存意图前校验失败可重新审阅。原申请不可改内容；保存完成但运行未提交不显示应用成功。旧助手与 Harness 对未结算关联运行提交互相阻挡。

## 接口与代码

根 `/api/ai/harness/sessions`：GET 列表、POST 创建、GET `/{id}`、DELETE `/{id}?revision=`；POST `/{id}/turns` 返回已持久化计划及任务；GET `/{id}/turns/{turnId}/events` 为 SSE（no-store/禁代理缓冲）；POST 同轮前缀 `/cancel`、`/apply`、`/observe`。登录/CSRF/角色及私有归属保护全部入口，管理员不能读取他人会话；UUID 与冻结内容保护重复受理。按最新用户要求，选择模型并发送即使用该模型，外部模型保留就近范围说明，取消逐轮授权勾选；日志仍单独授权。

- [执行服务](../../../backend/src/main/java/com/flinkweb/service/ai/impl/AiHarnessServiceImpl.java)、[工具系统](../../../backend/src/main/java/com/flinkweb/service/ai/support/AiHarnessTools.java)、[流协议](../../../backend/src/main/java/com/flinkweb/client/ai/AiHarnessModelClient.java)。
- [SQL批准与首次运行](../../../backend/src/main/java/com/flinkweb/service/ai/support/AiHarnessSqlCoordinator.java)：保存/绑定/发布和应用记录在同一事务，集群 dry-run 在事务前，提交后未知结果保留原操作号和保存修订；SAVED/run 同样阻挡删除与新任务。
- [提示词](../../../backend/src/main/resources/ai/harness-core.txt) 版本 `flink-harness-v3` 随制品发布，原设计 JSON 不自动加载为运行配置。
- [页面](../../../frontend/src/modules/ai/pages/AiHarnessView.vue)、[轮次与确认](../../../frontend/src/modules/ai/components/AiHarnessTurn.vue)、[API](../../../frontend/src/modules/ai/api/harness.ts)。
- [集成回归](../../../backend/src/test/java/com/flinkweb/AiHarnessIntegrationTest.java)、[HTTP 协议回归](../../../backend/src/test/java/com/flinkweb/AiHarnessModelClientTest.java)、[页面回归](../../../frontend/tests/ai-harness.spec.ts)。

新建Harness SQL候选默认带 `{namespace}-flink` ServiceAccount；已有显式账号保留。修复白名单只允许为缺失账号补同一namespace默认值，不接受任意账号或替换已有账号。账号及RBAC仍需预先准备。

前端“查看作业”进入 `/jobs?job=` 并定位列表，SQL工作台仍进入 `/sql?draft=`；观测结果直接列出问题和时间。AiRichText用既有Flink/Lezer解析器对SQL/YAML/JSON生成安全文本节点高亮，行内代码改用主题浅底，代码12px，三主题颜色对比度回归至少4.5；不插入模型HTML。

- [无进展检测](../../../backend/src/main/java/com/flinkweb/service/ai/support/AiHarnessProgress.java)、[实时观测](../../../backend/src/main/java/com/flinkweb/service/ai/support/AiHarnessObservation.java)、[回归](../../../backend/src/test/java/com/flinkweb/AiHarnessProgressTest.java)。
- [对话语法高亮](../../../frontend/src/shared/utils/codeHighlight.ts)、[作业列表定位](../../../frontend/src/modules/job-management/pages/JobsView.vue)。

无需数据库迁移或新增必填部署变量，仍限单后端实例。发布范围见 [验收](../validation.md#flink-ai-harness2026-10-29)、[部署](../../deployment.md#flink-ai-harness2026-10-29)。
