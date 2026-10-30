# AI 助手与模型服务

2026-10-30：TaskManager JVM/GC指标在每个实例内部隔离异常，一个实例失败继续读取后续实例；提示带实例ID，不把失败/空值当0。仍仅取前2个并保留截断提示，JM/列表读取失败逻辑不变。

2026-10-29新增独立 [Flink AI Harness 工作台](agent-harness.md)：原生工具循环、SSE对话、诊断/SQL开发/实际轨迹结构评测、批准实施及私有会话删除。原抽屉继续兼容；新工作台模型也没有改参数/重启写工具，须人批准后由平台运行服务实施。

2026-10-26：日志摘录总长最多10000字符（包含行号、换行和省略标记），为最新尾部最多20行预留至多4000字符，其余预算用于异常上下文；超长行保留头尾并避免拆开代理对。JVM 指标列表为空、无匹配项及值为空分别明确 unavailable，读取失败保留原因，实际零值仍为零。SQL 证据显示及脱敏重编码共用严格 Base64/UTF-8 解码，坏编码标为不可读取，禁止替换字符文本参与原文核对。见 [本轮验收](../validation.md#pod诊断与证据边界修复2026-10-26)。

更新：2026-10-29。关联 [REQ-003](../requirements/REQ-003-sql-session.md)、[元数据权限](metadata-security.md)。SQL创作采用三步“总结内嵌”；作业诊断采用用户选择的方案一侧栏，底层按证据、会话和修复编排拆分，见[ADR-006](../decisions/ADR-006-job-ai-repair.md)。模型无写工具，作业修复须用户确认后由平台校验执行。下方旧四步记录仅为历史。

## 抽屉高度与等待反馈（2026-10-27）

作业AI抽屉以实际clientHeight设置CSS变量，固定height/min-height/max-height，窗口改变时同步；回退100vh，不依赖dvh支持。等待提示固定在输入区上方，不随长历史滚出可视区；从现有任务状态展示保存/提交/排队/取证分析/采集/应用及待核对提示，正常模式持续转圈并显示实际等待秒数，减少动画模式保留文字与计时。未确认任务不假装运行；完成/取消清提示，关闭组件释放计时器。原任务同号、模型调用/外发授权及修复确认规则不变。见 [本轮验收](../validation.md#ai抽屉高度与等待反馈2026-10-27)。

## 紧凑布局与视口滚动（2026-10-25）

模型/取证紧凑排版，异常默认折叠并可展开全文。对话/修复默认55/45可拖动，网格行显式允许收缩，消息不挤压；输入、授权、错误和页脚分别限制高度并可滚动，窄屏可访问应用操作。外发确认及任务同号规则不变；模型失败后继续对话已用协议替身验证，现场厂商失败根因未证实。[本轮验收](../validation.md#ai-与-sql-视口布局2026-10-25)。

## 并发完成与日志来源（2026-10-21）

任务创建/拒绝/完成统一先取AiJobState.monitor，再取taskLock，随后才进入短数据库事务。两类会话complete内部调用commitTask回调，让回复、规范总结、方案/产物引用和成功终态一起提交；候选验证和模型/取证网络在事务外。日志最多4次读取，每次600行：当前running/terminated优先current，已重启waiting优先previous，失败显式回退并标明来源/时间，不能因restartCount>0长期只看旧日志。精确配置键的字面点号转义，近似键拒绝。见[并发回归](../../../backend/src/test/java/com/flinkweb/service/AiTaskConcurrencyTest.java)及[验收](../validation.md#ai-六项优化与版本发布2026-10-21)。

## 作业持续诊断与修复（2026-10-20）

后续五项反馈：[AiSharedSummary](../../../backend/src/main/java/com/flinkweb/service/ai/support/AiSharedSummary.java)提供作业唯一总结，SQL/作业两入口共享总结和待核对项、保留私有消息隔离；summaryRevision保护手工更新、确认、模型完成和修复应用。总结独立于会话正文，旧本地总结按需兼容；JAR以作业生命周期、SQL以草稿生命周期定位，不按名称关联。总结最大8000字符；跨入口更新使旧确认/方案失效，实际SQL变化仍需重新整理核对。

最新用户规则改为重启受理即结束旧轮次：[AiJobState](../../../backend/src/main/java/com/flinkweb/service/ai/support/AiJobState.java)在会话读取/写入时以最新ACCEPTED的deploy/start/restart/restore操作号和已保存SQL/YAML内容摘要同步ai_job_runtime。内容变化或新操作后清诊断消息/证据及当前方案指针，保留规范总结和SQL需求；已应用方案、终态应用/任务按原预算保留以核对原号。原应用未确认不清轮；运行成功后不会再次清掉已开始的新对话。助手内重启ACCEPTED关闭并定位作业，实际状态仍手动刷新。GET助手只读元数据和最新草稿，不额外查询集群。

新接口POST `/api/ai/jobs/{jobId}/evidence`，body.category白名单auto/configuration/pods/podMetrics/logs/flinkExceptions/flinkCheckpoints/flinkMetrics/flinkLogs。每次重新采集单个完整快照，不把旧类别数据冒充本轮；任务可带evidenceCategory，否则按当前错误/用户消息选类。模型失败也保留本次已采集证据。GET新增current（最新草稿SQL/YAML、已发布SQL、baseline）及round；证据documents另含实际集群YAML/解码SQL，三处来源明确。普通分析可直接附候选，成功回复后展示SQL/配置修改及待确认状态；非法候选仅警告、不写草稿、不丢分析回复。人工总结可显式提交unknowns数组（最多20项、每项2000字符），不传继续保留；SQL总结标题标明草稿，事项不跨作业。

分类实现：[AiJobEvidenceDetails](../../../backend/src/main/java/com/flinkweb/service/ai/support/AiJobEvidenceDetails.java)、[AiEvidenceDocuments](../../../backend/src/main/java/com/flinkweb/service/ai/support/AiEvidenceDocuments.java)、[Flink REST只读客户端](../../../backend/src/main/java/com/flinkweb/client/flink/AiFlinkEvidenceClient.java)。Pod配置/用量最多4个，requests/limits与timestamp/window/实际usage分开；metrics-server缺失或RBAC不足明确不可用。Flink REST只读概览、异常链、Checkpoint、Operator保存点/快照、JM及前2个TM的实际注册JVM/GC指标、JM日志关键摘录。使用[Flink 1.20官方REST契约](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/ops/rest_api/)及[Kubernetes资源指标接口](https://kubernetes.io/docs/tasks/debug/debug-cluster/resource-metrics-pipeline/)；版本/指标缺失不伪造值。完整文档只供授权页面，模型接收脱敏spec投影及本类结果；编码SQL去重、环境字面值/凭据隐藏，平台引用保留。Flink原始异常/日志先核验工作负载/Pod引用Secret并逐叶遮蔽，失败则不外发原文。

助手默认1180px，左边缘可拖动或方向键调整，Home恢复默认、End扩至导航栏右侧；最大宽度实时按导航栏边界计算，窗口变窄同步收缩。对话/修复中轴可指针拖动与方向键/Home调整（28–72%），助手本身宽度1000px以下上下排列。当前总结直接打开大视图并在弹窗内修正。证据以[AiEvidenceView](../../../frontend/src/modules/ai/components/AiEvidenceView.vue)分为Pod/事件、配置、容器日志；[AiContentDialog](../../../frontend/src/modules/ai/components/AiContentDialog.vue)提供证据/总结/完整对比大视图，长代码可上下对比自动换行。[AiRichText](../../../frontend/src/modules/ai/components/AiRichText.vue)以Vue文本节点安全显示标题、粗体、列表、代码、表格/引用及HTTP链接，不执行HTML/脚本链接。SQL总结压成一行预览及紧凑操作区，对话保留主要空间，授权问号移出checkbox label并对齐。

2026-10-20再次反馈：AiSqlAssistant保持单一aside根节点，总结大视图置于根内，使父页v-show可真实隐藏；反复切换不重建会话、不残留网格项。作业助手外部模型每轮仍需明确授权；未勾选时点击分析/发送/生成方案，在输入区展示“允许并发送本轮”或取消，确认前不追加消息或调用模型，取消保留输入。已受理重启仍失败可继续取新证据，不因旧已应用方案锁住对话；未确认应用与活动任务保护沿用原规则。

- [会话契约](../../../backend/src/main/java/com/flinkweb/service/ai/AiJobService.java)/[实现](../../../backend/src/main/java/com/flinkweb/service/ai/impl/AiJobServiceImpl.java)、[控制器](../../../backend/src/main/java/com/flinkweb/controller/ai/AiJobController.java)、[取证](../../../backend/src/main/java/com/flinkweb/service/ai/support/AiJobEvidenceCollector.java)、[修复](../../../backend/src/main/java/com/flinkweb/service/ai/support/AiJobRepairCoordinator.java)、[生命周期清理](../../../backend/src/main/java/com/flinkweb/service/ai/support/AiJobState.java)、[配置指纹](../../../backend/src/main/java/com/flinkweb/service/job/creation/support/JobResourceFingerprint.java)。[原抽屉](../../../frontend/src/modules/ai/components/AiJobAnalysis.vue)增加持续对话/总结、证据、完整SQL/YAML对比和显式应用，复用现有设计系统；原SQL创作流程不变。
- GET `/api/ai/jobs/{jobId}/conversation`只恢复元数据；POST `.../messages`带messageId/revision/content/mode(chat或repair)；PUT `.../summary`带revision/summary。新诊断任务仍走`/api/ai/tasks`、kind=diagnosis，增加jobConversationVersion/mode/includeLogs及每次allowExternal。上下文从服务端读总结与近期消息，不接受浏览器伪造历史/回复。
- POST `.../plans/{planId}/apply`仅ADMIN/DEVELOPER，带requestId、mode(save或restart)、confirmed；restart还需restoreMode、acceptImpact，可带savepointPath。GET `.../applications/{requestId}`核对原号。重新核验作业/SQL权限、会话/草稿/SQL基准；restart另核对UID及配置摘要，由原OperationService使用当前resourceVersion/dry-run提交。JSON整数按数值比较，资源摘要忽略对象字段顺序和数值表示差异。
- 总结8000/回复4000字符；单条输入8000字符。未成功总结的原文最多26条/36000字符，超限拒绝追加、允许重试本轮；成功后超过24条裁至16条，再施加28000字符预算。失败/取消不裁剪。当前证据1份，方案2份、终态应用3份、终态任务5份；归属及删除规则集中见ADR。容器日志只允许operate权限，最多4份、各取末尾600行并提取异常邻近和尾部到10000字符；主容器优先，按重启状态补当前/上次实例，普通已完成下载器不挤占预算。Flink REST JSON最多64KiB，JM日志总传输4MiB/保留64KiB尾部再摘录；模型证据85K字符、页面文档512K上限，超预算明确说明。
- SQL候选与配置由同一原文生成，经既有边界/Parser/发布链路；仅保存SQL不隐式发布，restart提交最新完整配置。保存与阶段状态同事务提交，运行阶段使用确定性原操作号；不确定提交不能以新号重复，原应用未确认时拒绝新消息/模型上下文，保留原恢复入口，同号完成后允许继续对话。受理不等于RUNNING。生命周期锁先于SQLite事务取得，避免单连接锁反转。
- [后端回归](../../../backend/src/test/java/com/flinkweb/AiJobIntegrationTest.java)、[页面回归](../../../frontend/tests/ai-job.spec.ts)覆盖有界记忆、SQL/配置应用、幂等、权限、过期与删除迟到回复；替身和现场验收分别记录。

## 入口与代码

- SQL 开发「AI 助手」：确认需求→查看 SQL→检查和保存，无选择数据步骤。首步约70%对话/30%当前SQL，可拖动或收起SQL；模型区紧凑、总结内嵌、输入区独立，窄屏上下布局。对话后保存完整会话总结，用户核对确认才生成完整SQL；审阅后显式应用，再检查/验证/保存。可随时补充，旧SQL可解释整理，报错可加入对话再确认修复。问号解释保存、授权和确认边界。
- 作业列表：正常作业为「AI 助手」，错误/FAILED显示「AI 分析」；持续诊断和明确确认后的修复应用见上方新流程，替代原单次只读窗口。
- 系统设置 → 模型服务：仅管理员增删改、测试；按用途选模型，外部发送须每次明确允许。支持兼容 Chat Completions 的非流式 JSON 响应；Base URL 填到 `/v1` 等接口前缀，程序追加 `/chat/completions`。未配置真实模型时不能生成。
- [模型客户端](../../../backend/src/main/java/com/flinkweb/client/ai/AiModelClient.java)、[模型服务](../../../backend/src/main/java/com/flinkweb/service/ai/impl/AiModelServiceImpl.java)、[任务服务](../../../backend/src/main/java/com/flinkweb/service/ai/impl/AiTaskServiceImpl.java)、[内容防护](../../../backend/src/main/java/com/flinkweb/service/ai/support/AiContentGuard.java)。
- [前端模块](../../../frontend/src/modules/ai/api/ai.ts)、[侧栏](../../../frontend/src/modules/ai/components/AiSqlAssistant.vue)、[字段表单](../../../frontend/src/modules/ai/components/AiDataDefinitionEditor.vue)、[模型页面](../../../frontend/src/modules/system/pages/ModelsView.vue)。
- [会话控制器](../../../backend/src/main/java/com/flinkweb/controller/ai/AiConversationController.java)、[会话服务](../../../backend/src/main/java/com/flinkweb/service/ai/impl/AiConversationServiceImpl.java)、[三步页面回归](../../../frontend/tests/ai-conversation.spec.ts)。旧字段/定义接口保留兼容，不在新SQL助手中显示。

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
| GET `/api/ai/conversations/{sqlId}` | ADMIN/DEVELOPER，重新核对SQL读取权限，当前用户及草稿生命周期私有会话 |
| POST `.../messages` | SQL编辑权限，messageId/revision/content/mode；同号同内容幂等，补充使确认失效 |
| PUT `.../summary`；POST `.../confirm` | revision保护；总结修改带summary/sql，确认带sql，完整总结/当前SQL摘要匹配；存在待核对项时须显式acceptCurrentScope=true确认已知范围 |

模型/任务/会话复用 `RecordRepository` 的 `ai_models`/`ai_tasks`/`ai_conversations` JSON记录；`job_names`占用记录保护SQL/JAR跨入口唯一名称，无新表迁移，SQLite仍限定单后端实例。会话ID绑定用户、SQL ID和草稿createdAt，其他用户及同名重建不恢复旧记录。密钥用现有主密钥AES-GCM加密，AAD绑定模型id；禁止将实际凭据写入文档。

新任务携带conversationVersion，使用持久化全部消息与人工总结，chat/explain只整理，generate/repair要求总结已确认且绑定当前SQL SHA-256。内容版本和任务终态锁拒绝取消/并发补充后的迟到回复；应用还核对前端上下文版本及SQL摘要。编辑SQL、配置或补充会话使旧建议失效，单独保存修订号不失效。lastSqlTaskId恢复可审阅建议，完整对比不截断1000行之后内容。

单条用户消息8000字符，总结8000字符，模型回复4000字符；追加前最多117条已有消息及36000字符，模型回复后总文本最多40000字符。达到上限明确拒绝本条，原记录保留，不静默截断。未知事项可继续对话澄清，也可明确确认已知范围；待核对项仍保留，不当作已回答，未确定条件保持原状且不得编造参数。手动改总结不能清掉待核对事项；补充或重写总结撤销scopeAccepted/confirmedVersion。模型上下文仍有总预算保护；记录持久化在当前平台元数据库，离页可恢复。

并发为两线程、等待六项，每账号一个活动任务；重启将未完成任务置失败，不重放计费请求。客户端禁止重定向，校验目标地址；内网/本机和 HTTP 需要管理员显式允许，链路本地/元数据地址仍拒绝。请求有超时与响应上限。模型不持有平台写工具。

2026-10-15：定义输入/模型输出均逐项验证对象、字符串表名/Connector、列数组及列名称/类型、可选布尔属性；不要求连接参数已齐，仍由编译给待补项。前端合并前对现有及建议完整检查，异常保留原文。AiContentGuard 每次公开 redact/sanitize 建立一次局部密钥快照，所有文本叶子复用并保留既有替换顺序；无进程级明文缓存，下次调用重新读取/解密。

useAiTask 保留原 requestId 和不可变请求副本：创建失败先 GET 原任务，存在则继续观察；未确认时禁新建并提供核对/取消。主动重试再 GET，仅 404 才用同号原请求重发；取消失败保留身份并可重试，迟到活动响应继续取消，不覆盖新上下文。SQL 和作业分析共用该逻辑。生成/解释/修复模式变化立即失效；编译用单调上下文/请求版本，历史还核对 SQL ID，组件销毁不回填，未增加后台模型任务。

当前SQL创作对话仍包含全部会话/总结与当前SQL；旧定义及未带jobConversationVersion的诊断接口保留兼容。新作业会话使用有界总结/近期消息、必要SQL和配置投影及受限日志，不发送完整运行YAML/env。已知配置/模型密钥及常见凭据模式遮蔽，输入输出拒绝疑似明文凭据；自由文本防护不能识别任意业务敏感信息，外部发送仍需逐次明确允许。

当前对话生成允许完整CREATE TABLE/VIEW、INSERT和查询，经验证入口的基础边界检查，拒绝CTAS、SET、DROP/ALTER、Catalog和函数注册。提示保留已有非空连接参数，不编造未知连接信息，生成不等于Planner/运行成功。旧workflowVersion=2接口仍保留DataSourceService确定性编译及临时VIEW/INSERT、WITH覆盖拦截约束，不将旧客户端规则套入新完整SQL对话。

2026-10-20：总结当前且待确认时可直接点击确认；明确接受已知范围后允许生成，提示词区分必要缺口与可选建议。模型选择/授权同基线，总结默认两行预览、展开统一滚动；对话区域自适应、输入固定可见，短屏压缩预览与输入高度。请求期间顶部状态及对话内均显示旋转图标，完成明确显示“AI已回答”，不靠静默等待猜测。

## 验证入口

[后端集成](../../../backend/src/test/java/com/flinkweb/AiAssistantIntegrationTest.java)、[页面回归](../../../frontend/tests/ai-assistant.spec.ts)。本地协议替身用于验证模型链路、权限、加密、取消和诊断；不能据此宣称某个真实模型厂商或生产 Kubernetes 已认证。实际结果见 [验收记录](../validation.md)。

[快照计数回归](../../../backend/src/test/java/com/flinkweb/service/AiContentGuardTest.java) 验证多叶子仅解密各密钥一次、结果等价及后续轮换可见；异常定义、断响应恢复、同号重试、取消失败及旧异步回填由上述用例覆盖。

## 需求向导与模型诊断（2026-10-15）

向导复用原任务/同号核对/取消、上下文版本、数据定义和差异应用；确认需求含事件时间、窗口、消费起点及重复处理。生成不直接改编辑器，显式应用后进入检查与保存；仍由原 Parser、草稿保存和独立运行验证处理。桌面可拖动分栏，1000px 以下上下布局，390px 可操作。参考用户指定原型聊天 01a13a0d-fe51-74a1-8d56-881f8eb7570b；未发送消息或发布原型站点。

本聊天2026-10-15将向导升级为实际约束流程：先明确登记/本地表的输入、维表、输出角色，再确认清洗转换、JDBC维表打宽或事件时间窗口场景。transform 不携带隐藏窗口；window 仅可选输入表已登记水位线，确认1–1440分钟和窗口结束输出。DataGen仅输入、Print仅输出、JDBC MySQL/PostgreSQL可作维表，重复表名/角色缺失拦截。需求补充只更新确认单，不自动请求模型；生成保留确认单快照，应用成功回执后才进第四步，配置冲突留在审阅。关闭/重开保持向导内容，活动任务取消及同号核对规则保留。

任务使用可选 spec.workflowVersion=2，含 bindings/pattern/timezone 及场景参数；后端 [AiWizardContract](../../../backend/src/main/java/com/flinkweb/service/ai/support/AiWizardContract.java) 在调用模型前校验，并限制生成 INSERT 只写已确认的输出表。旧任务未带版本时兼容原规则。Kafka起始位点只作用输入，时区按确认单编译；[前端契约](../../../frontend/src/modules/ai/utils/wizard.ts) 与集成/页面用例覆盖输入、失效和步骤保护。仍不等于 Planner 或业务语义认证。

模型服务改为列表筛选、启用概览、配置表单与逐项测试结果；ModelScope 预设仅填写地址/模型，不包含密钥。客户端接受根地址并补 `/v1`，Qwen3 系列非流式请求显式关闭 thinking；401/403/404/429/400 返回脱敏分类，不能回显厂商正文。保留 HTTPS/内网授权、禁重定向和有界响应。

2026-10-15历史：用户指定的 Qwen/Qwen3.5-27B 认证调用返回HTTP403，要求账号邮箱验证；该次没有成功结果。2026-10-20已在用户授权现场使用现有模型配置完成真实调度故障分析及第二轮SQL/YAML修复建议，不能继续把旧403记为当前阻塞；详见[当前验收](../validation.md#作业-ai-持续诊断与确认修复2026-10-20)。本库不保存密钥。[客户端回归](../../../backend/src/test/java/com/flinkweb/client/ai/AiModelClientTest.java)覆盖根地址、非流式参数、错误分类及正文不回显。

## 五项反馈中的 AI 修复（2026-10-16）

需求、定义、模型等上下文失效同步清除 appliedTask/appliedVersion，正常应用回执仍在父编辑器完成后登记。补充后第四步禁用，重新生成并成功应用后恢复。对比仍展示前1000行，额外统计尾部行数与修改数，并提供当前/建议完整SQL的只读编辑器。后端对已拆分、去注释的语句先用 SqlScriptLexical 遮蔽引用内容，再检查 WITH 参数覆盖，双写引号和引用标识符不误报，实际覆盖仍拒绝。回归范围见 [本轮验收](../validation.md#ai-与-sql-五项修复2026-10-16)。

## 准备阶段取消边界（2026-10-19）

诊断准备使用可控闸门暂停，取消后恢复，分别模拟协作者吞掉/保留线程中断。原实现两种情况均进入模型客户端，故在密钥和请求上下文准备完毕后、调用模型之前，于taskLock短临界区重新检查RUNNING状态及中断标志。该锁不覆盖网络请求；检查后已开始的外发仍使用原中断机制，不能保证撤回已发请求。集成测试等待工作线程实际退出，再验证客户端及本机HTTP零调用、CANCELED和无结果，并验证新任务正常。参见 [本次验收](../validation.md#ai-准备阶段取消与结果标识优化2026-10-19)。

2026-10-25：作业助手模型和取证方向相邻，生成候选后设置/上下文/对话留在左侧，右侧审阅，中轴可拖动；SQL/YAML 使用共用 CodeDiff，行级和行内修改红绿高亮，完整内容可滚动。SQL 助手与作业诊断带入选中 UDF 的静态签名/调用线索和业务说明，以及已选 Catalog 的非敏感语义投影；不自动查询远程目录、不编造函数源码或运行结论。验证入口为 frontend/tests/ai-job.spec.ts、ai-conversation.spec.ts 和 catalog-workbench.spec.ts。
