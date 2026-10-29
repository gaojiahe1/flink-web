# Flink AI Harness 工作台

更新：2026-10-29。关联 [REQ-003](../requirements/REQ-003-sql-session.md)、[原 AI 助手](ai-assistant.md)、[ADR-008](../decisions/ADR-008-agent-harness.md)。首期覆盖作业诊断/最小参数修复、SQL 开发、真实执行轨迹结构评测；尚不支持通用代码仓库开发、任意 Shell、分布式工作流或自动业务验收。

## 页面与确认

独立 `/ai`，菜单“AI 工作台”；ADMIN/DEVELOPER/OPERATOR 可进入，SQL 开发和应用候选要求 ADMIN/DEVELOPER。私有会话列表可收起，正文13px、辅助12px、说明11px。提交后先显示服务端场景计划，真实模型内容由 SSE 快照持续更新；工具输入输出、事件和上下文清单默认折叠，结果和关键信息留在正文，不使用对话表格。

审阅完整 SQL/YAML 差异并明确确认后才调用应用。诊断可仅保存，或选择恢复方式、接受影响后保存/发布/启动或重启，沿用原修订、UID、spec 指纹、dry-run、原操作号。SQL 入口仅保存草稿。ACCEPTED 不等于 RUNNING；三秒手动核验须匹配操作注解、保存修订、资源归属及协调代际，业务效果另外验收。

## 六层实现

- 上下文：每轮冻结最新草稿、共享总结、版本/摘要及来源清单，历史最多四轮；草稿变化使执行失效。共享总结和私有记忆不是已验证事实。评测冻结来源会话实际结构轨迹，不重放工具。原始上下文不返回浏览器。
- 工具：按场景/角色/确认阶段发现 `context_read`、`memory_update`、`evidence_collect`、`repair_propose`、`requirements_propose`、`sql_check`、`sql_propose`、`trace_inspect`。服务端重验白名单及参数，目标来自会话，模型没有改参数/重启写工具。
- 编排：最多四次模型请求、八次工具动作，每步最多六个原生调用，工具串行；两名本地工作线程、六个排队位置、每账号一个活动任务。计划是固定场景纲要，实际调用由控制程序记录，不把模型文字当执行证明。
- 记忆状态：四个私有集合保存会话/轮次/候选/应用；压缩记忆为 PROPOSED 并带来源轮次，不回写共享总结。owner/epoch/用户及模型修订防迟到响应复活。网络不入数据库事务，生命周期锁先于短事务。
- 评估观测：真实事件包含模型步骤、工具结果和上下文清单；结构评测统计实际调用/失败/状态，明确不测结论正确率和生产业务结果。SSE 断线重连原任务，页面不轮询集群。
- 约束恢复：沿用模型地址限制、不跟随重定向；完整帧/完整参数/终止状态和大小预算通过后才执行。输出按登记凭据和常见格式脱敏，尾部缓冲保护分片凭据。供应商内部推理仅当前请求链内传回，不进入页面或持久化；网络回调恢复发起者安全上下文并重新核对身份。重启仅标记中断/原申请待核对，不重放模型或部署。

## 删除与保留

每用户最多20个会话，每会话保留最近10轮；裁剪最旧轮次时清理关联方案/应用。删除带会话修订，事务内清理正文、候选、应用和直接关联评测，取消本地调用/关闭流；保留共享草稿、总结、运行审计与保存点。作业/SQL 实际删除时清理所有用户关联会话；旧助手清轮和实际领域删除使用不同事件。

SAVING/SUBMITTING/UNKNOWN、重启的 SAVED、缺少意图记录的中断原申请，必须先核对/继续原号，不删除恢复入口或另起方案。明确在保存意图前校验失败可重新审阅。原申请不可改内容；保存完成但运行未提交不显示应用成功。旧助手与 Harness 对未结算关联运行提交互相阻挡。

## 接口与代码

根 `/api/ai/harness/sessions`：GET 列表、POST 创建、GET `/{id}`、DELETE `/{id}?revision=`；POST `/{id}/turns` 返回已持久化计划及任务；GET `/{id}/turns/{turnId}/events` 为 SSE（no-store/禁代理缓冲）；POST 同轮前缀 `/cancel`、`/apply`、`/observe`。登录/CSRF/角色及私有归属保护全部入口，管理员不能读取他人会话；UUID 与冻结内容保护重复受理。外部模型每轮明确授权最多四次请求，日志另行授权。

- [执行服务](../../../backend/src/main/java/com/flinkweb/service/ai/impl/AiHarnessServiceImpl.java)、[工具系统](../../../backend/src/main/java/com/flinkweb/service/ai/support/AiHarnessTools.java)、[流协议](../../../backend/src/main/java/com/flinkweb/client/ai/AiHarnessModelClient.java)。
- [提示词](../../../backend/src/main/resources/ai/harness-core.txt) 版本 `flink-harness-v1` 随制品发布，原设计 JSON 不自动加载为运行配置。
- [页面](../../../frontend/src/modules/ai/pages/AiHarnessView.vue)、[轮次与确认](../../../frontend/src/modules/ai/components/AiHarnessTurn.vue)、[API](../../../frontend/src/modules/ai/api/harness.ts)。
- [集成回归](../../../backend/src/test/java/com/flinkweb/AiHarnessIntegrationTest.java)、[HTTP 协议回归](../../../backend/src/test/java/com/flinkweb/AiHarnessModelClientTest.java)、[页面回归](../../../frontend/tests/ai-harness.spec.ts)。

无需数据库迁移或新增必填部署变量，仍限单后端实例。发布范围见 [验收](../validation.md#flink-ai-harness2026-10-29)、[部署](../../deployment.md#flink-ai-harness2026-10-29)。
