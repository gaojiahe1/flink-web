# Spring AI 与 Langfuse 接入

2026-11-04：平台0.0.10，Spring Boot 3.5.16 / Java17，Spring AI 1.1.7，OpenTelemetry Java 1.49.0（Boot BOM）。实际联调Langfuse 4.70.1与Collector Contrib 0.164.0。

后端AI调用统一经过Spring AI OpenAiChatModel，当前仍使用已登记的OpenAI兼容模型地址。Harness负责计划、工具权限、用户交互、审批、幂等与恢复，框架不自动执行工具或重试。MCP业务契约和前端SSE契约保持，非AI的Flink/Kubernetes/存储模块继续使用原领域服务。

## 选择接入模式

默认关闭观测，不需要Langfuse即可启动。两种模式都通过OTLP/HTTP protobuf导出traces，使用同一套采集。改变环境变量后重启后端，不修改数据库。.env文件只由Compose通过--env-file读取；后端需要单独注入进程环境变量，Spring Boot不会自动读取该文件。

| 环境变量 | 直连模式 | Collector模式 |
| --- | --- | --- |
| FLINK_WEB_AI_TELEMETRY_ENABLED | true | true |
| FLINK_WEB_AI_TELEMETRY_MODE | direct | collector |
| LANGFUSE_TRACING_ENVIRONMENT | default（可设production/test/dev等） | 同左；在平台后端设置 |
| LANGFUSE_BASE_URL | http://127.0.0.1:3000（Langfuse根地址） | 后端无需设置 |
| LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY | 设置在后端进程环境 | 只设置在Collector环境 |
| FLINK_WEB_OTEL_TRACES_ENDPOINT | 忽略 | http://127.0.0.1:14318/v1/traces（完整信号端点） |

直连后端自动拼接/api/public/otel/v1/traces，计算Basic认证并添加x-langfuse-ingestion-version: 4。Collector模式后端不携带Langfuse认证；Collector负责认证和转发。

2026-11-05：Langfuse列表的Environment由平台后端环境变量`LANGFUSE_TRACING_ENVIRONMENT`控制，默认`default`。例如设为`production`并重启后端，之后的新轨迹及其子观测都携带OTel resource属性`langfuse.environment=production`；直连和Collector使用同一值，已有轨迹不回填。名称需1–40个小写字母、数字、连字符或下划线，不能以`langfuse`开头；开启观测时非法值拒绝启动。此字段是环境标识，不是性能指标。参见[Langfuse环境规范](https://langfuse.com/docs/observability/features/environments)。程序独立构建SDK，不自动消费任意`OTEL_RESOURCE_ATTRIBUTES`，请使用上述显式变量。Collector若自行覆盖环境属性，以其实际导出为准。

localhost示例适用于本机Java进程。后端在容器/Kubernetes内时改成Collector或Langfuse实际可达的服务DNS/网络地址，不能把localhost当宿主机。Langfuse接收OTLP HTTP，不直接发送gRPC到Langfuse。

## 运行独立Collector

现有用户Collector只输出debug，本目录配置额外提供真实Langfuse导出，未覆盖原挂载文件。可把所需导出器合入现有Collector，或启动本目录的独立Compose。

1. 从.env.example建立仅本机使用的环境文件，在其中填入项目Public/Secret Key；禁止提交真实凭据。
2. 设置LANGFUSE_OTLP_BASE_URL为Langfuse根地址加/api/public/otel。示例http://host.docker.internal:3000/api/public/otel适用于Docker Desktop；若加入原Langfuse Docker网络，可用其服务名。
3. 执行：

```powershell
docker compose --project-name flink-web-observability --env-file <私有环境文件绝对路径> -f deploy/observability/compose.yaml up -d
```

默认只绑定宿主机127.0.0.1的14318/14317/14133端口，避免占用现有Collector；13133为容器健康端口。配置包含memory_limiter、batch、有界发送队列及重试；仅导出traces，没有debug全文导出。生产网络、TLS和Collector访问范围按实际部署配置。

## 观测范围

- 新Harness：每轮chain，模型请求generation，工具tool；用户回答、批准、取消与手动核验为独立event。等待用户时结束当前执行span，后续轮次通过同一Langfuse session关联，不把人的等待计入模型耗时。
- 旧SQL/作业助手和模型连接测试也通过Spring AI；任务与模型generation进入观测。
- 会话、用户、任务、release属性复制到子span。记录状态、模型、真实usage；缺失usage不补造，不自行计算费用。平台启用时保存traceId用于关联。
- 当前仅采集元数据，不上传提示词、用户消息、SQL/YAML、工具输入输出正文、日志、供应商内部推理或凭据。库的正文日志关闭，业务错误只记机器分类。
- 后端异步有界批量导出，Collector不可用不阻断业务；崩溃前未发送的内存队列可能丢失，不能替代平台持久化审计和应用记录。关闭时flush/shutdown。

## 验证

常规回归：AiHarnessModelClientTest、AiModelClientTest、AiTelemetryIntegrationTest及现有AI集成测试。真实联调脚本需要Java17（JAVA_HOME）、Maven及Docker：

```powershell
# 在进程环境中配置项目凭据，脚本不会输出或持久化它们。
python scripts/check_ai_observability.py
```

该脚本面向当前本地Docker命名：Langfuse Web为langfuse-langfuse-web-1；创建独立临时Collector，顺序验证Java直连和Java→Collector两条链路，并通过Observations API v2核对chain/generation/tool、父子关系、模型和12输入/3输出Token。结束自动删除测试Collector，保留合成测试轨迹；不修改现有Collector配置、Langfuse数据卷或业务作业。其他部署可使用liveLangfuse条件测试配合实际端点。

Spring AI迁移阶段未发布镜像；2026-11-04整体回归后0.0.10已发布，摘要见[部署指南](../../docs/deployment.md#整体回归与0010发布2026-11-04)。未更新实际Flink/Kubernetes工作负载。验收记录见[项目验收](../../docs/agent/validation.md#spring-ai与langfuse双模式2026-11-04)。
