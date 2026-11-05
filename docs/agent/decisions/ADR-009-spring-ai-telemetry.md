# ADR-009 Spring AI与双模式OTLP观测

日期：2026-11-04；更新：2026-11-05。状态：接受。关联[AI模块](../modules/ai-assistant.md)、[Harness](../modules/agent-harness.md)、[REQ-003](../requirements/REQ-003-sql-session.md)。

用户要求全项目AI迁移Spring AI，并接入Langfuse，随后明确同时支持直连及经Collector转发。实际目标Langfuse4.70.1/Collector0.164.0，保留0.0.10及暂不发布镜像约定。

采用与Boot3.5匹配的Spring AI1.1.7 OpenAiChatModel替代旧手工Chat Completions/SSE解析。保留AiModelClient和AiHarnessModelClient作为领域契约适配；模型动态地址、密钥和用途来自原登记。框架内部工具执行关闭，最大尝试次数为1（只发送原请求），不迁移平台审批、幂等、身份/修订、未知结果与恢复状态机。保留网络目标限制、禁止重定向、响应预算、严格UTF-8、总超时、非流式兼容及请求链内推理回传。

使用Boot BOM管理OpenTelemetry SDK/exporter，不注册GlobalOpenTelemetry；AI专属chain/generation/tool/event，异步有界批量导出。总观测默认关闭。2026-11-05用户明确要求显示Input/Output后，正文开关默认true，观测启用时导出经统一脱敏、移除内部推理并限长的JSON；可用FLINK_WEB_AI_TELEMETRY_CAPTURE_CONTENT=false恢复仅元数据。密钥不进入观测；外部观测不能替代平台审计。direct模式后端持Langfuse项目认证，collector模式认证只在Collector，统一OTLP HTTP和v4接收头。两种配置由环境选择，不修改数据库或强制升级Boot4。

等待交互不持有开放span或工作线程；每轮trace经同一私有session关联。子span显式复制关联属性，避免Langfuse v4根属性不自动继承。真实Token按模型usage记录，不生成缺失用量或费用。

部署配置及限制见[接入说明](../../../deploy/observability/README.md)。本次使用合成轨迹完成实际Langfuse入库验证，未扩展到真实厂商模型质量、生产业务效果或现场集群验收。

2026-11-05正文决策：通过AiTelemetry.ContentSanitizer接口由配置层桥接AiContentGuard，避免client反向依赖service/repository。统一处理结构化及嵌套JSON字符串的凭据/推理字段，先脱敏后限长32768，截断保留可解析JSON与标记；不继承正文到子span，每个步骤记录自己的输入/输出。失败分类可导出，脱敏失败关闭该份正文且不影响领域执行。
