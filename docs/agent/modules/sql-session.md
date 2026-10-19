# SQL 与 Session

更新：2026-10-19。关联 [需求](../requirements/REQ-003-sql-session.md)、[决策](../decisions/ADR-001-on-demand.md)；来源：当前代码与 [功能说明](../../features.md)。

职责：SQL 草稿/修订/历史、基础与精确 Parser 检查、Application 发布、Session 配置启停、限额运行验证。SqlJobService 处理草稿关联和生成；SqlExecutionService 重新检查并提交固定 JobID，SessionClusterService 保证专用 sql-validation 唯一。

2026-10-15：parseDeploymentYaml 明确检查 spec 为非空对象且非数组，拒绝布尔/数字/文本；Connector 提示先只遮蔽注释，保留字符串和原位置，再匹配真实 Connector/已用键。BoundedResponseBody 从空缓冲按正文倍增到原容量上限，取消和 128MiB 传输边界不变；小日志不再预分配 33MiB，满/绕回/跨增长边界有回归。[编辑校验用例](../../../frontend/tests/editor-validation.spec.ts) 与原客户端/Session 测试覆盖边界；分配量是合成测量，不能等同生产吞吐收益。

2026-10-14：按用户确认的侧栏设计实现 SQL 智能体第一阶段，含生成/解释/修复、字段/Connector 草稿、需求确认、差异显式应用及历史。编辑使旧建议与检查失效，继续复用既有 Parser/Session 验证；模型不部署或执行。模型服务、作业主动诊断、接口及代码统一见 [AI 模块](ai-assistant.md)。

现已实现（2026-10-15）：SQL 助手需求向导及任务模型选择；数据定义增加自然语言建议、字段/Connector 表单编辑、继续对话补充、待补参数和显式应用。复用当前草稿/运行配置、数据源生成及独立检查流程；复用已有 AI 任务与定义接口，原保存/发布边界保留。用户新增的模型管理与主动报错分析设计边界集中记录在 REQ-003，不能再以此前阶段划分否定这项最新要求。

2026-10-14：SqlValidationLogReader 负责固定日志请求/解析，整轮五秒预算覆盖 TM/文件列表及正文，受限响应订阅器到期取消传输。每个成功文件独立交付；SqlExecutionService 在短临界区同时追加事件和推进 TM 游标，不按业务值去重。观察、取消、准备分别占用单次运行资格，外部 I/O 不持验证状态锁；状态提交核对元数据修订，迟到响应不会复活终态或追加取消后的数据。先持久化首次取消目标，独立控制请求五秒预算，确认前不释放独占。准备阶段即安排截止；未知提交保持固定 JobID，404 在请求未结束及短暂不可见窗口内不释放，旧记录重启兼容恢复。清理前重新核对 Session UID，关闭后不再提交观察或重新安排采集。TaskManager 日志路由兼容 Flink 的地址:端口型 ResourceID，仍拒绝斜线、查询参数和路径穿越。

Session 先迁移、SQL 后迁移至 useDeploymentConfigurationEditor；共享原文/模式/有效性/脏状态及转换版本，页面提供业务快照、请求和回填。单调版本处理编辑后还原，草稿上下文处理切换和组件销毁，AbortSignal 取消旧请求；响应先完整验证再修改状态。后端 RuntimeYamlMapper 和各页面保存/发布流程不变。

2026-10-13：`POST /api/sql/configuration`、`POST /api/sessions/configuration` 只做本地配置渲染/回填，不保存、不访问 Kubernetes；沿用原 SQL/Session DTO，运行配置增加可选 `deploymentYaml`/`baseYaml`，兼容旧表单记录。解析和受控字段比较由 [RuntimeYamlMapper](../../../backend/src/main/java/com/flinkweb/service/job/creation/support/RuntimeYamlMapper.java) 完成。Session 的 `deploymentMode`/`tmReplicas` 显式表达按需/固定数量，运行中仍不能保存配置。dry-run 数值按十进制等值比较，真实改变输出最多八处路径及类别，不输出值。状态数量来自显式刷新中的 Flink REST `/overview`，内部验证就绪核对不增加数量请求。

Parser 新输出 errorType=INPUT/SYNTAX/RUNTIME，旧协议缺少类型时继续按语法错误处理；仅有效位置显示行列。安装/进程故障日志脱敏分类，JAR 清理失败关联验证 ID，未改变截止清理、终态或增加自动重试。

SqlParserServiceImpl 按精确版本读取 validator.jar，独立进程限制并发/内存/时间，清除业务凭据环境；缺失时明确 BASIC，不能报告 PARSER。Parser 不连接 Catalog、不加载 Connector。SqlRunner 先执行允许的初始化语句，再把 INSERT 合并 StatementSet；环境变量在容器中转义展开。

2026-10-12：Runner 的磁盘配置去除 pipeline.jars/classpaths 覆盖项，用户代码路径沿用 Flink 已解析的提交上下文，避免 Application 中 local URI 进入 Java URL 解码器；其余状态/S3/业务配置保留。Runner/Parser 使用 major52，已实测 JDK8/17；Parser 限制输入、数组与脚本，输出固定UTF-8。镜像与本地安装提供精确1.20.3/1.20.5 Parser；Runner下载接口固定读取安装文件、仅管理员/开发者允许。SQL/Session仍显式上传选择Runner，旧文件不自动替换。

验证将原 Sink 换为 Print，保留查询变更流，跨 Sink/TM 最多 1000 条/两分钟，行只留在进程内。后端在活动验证期间每 2 秒监听固定 sql-validation.log 及轮转副本，按验证 ID/事件顺序号去重；四种 RowKind、重复行与 JSON 转义的多行数据保留。后台上限/截止取消及重启恢复是手动查询规则的明确例外；浏览器监听离页/终态停止，服务端继续处理已授权验证，取消确认前不释放单任务入口。日志配置由 SqlValidationLog 强制生成，旧运行配置须显式重启；Runner 不再添加 LIMIT，旧 Runner 须手动重新选择。

Session 行末刷新独立冷却，复用 PodStatusDrawer 与 DiagnosticService 的所有者链/日志脱敏；新增 /api/sessions/{id}/diagnostics、/logs 及 /flink-ui/sessions/{id}/，代理每次调用 observedResource 核验身份。validationStatus 内部不查询 REST 数量。SQL CodeEditor 复用 sqlEditing 的 Flink 方言/Connector 补全，sql-formatter 的通用 SQL 规则受 token 不变检查保护，格式化历史独立可撤销。

普通 Session 可多个，验证 Session 仅一个；运行中不得改配置。管理员配置、管理员/运维启停；开发者需 SQL 编辑权限。Runner 必须手动选具体 JAR，验证和 Application 必须选；运行 Session 镜像须与 SQL 相同。动态用户 bundle 合并服务发现、拒绝冲突类，不能删除看似重复的 META-INF 服务内容。

SQL 限32 KiB/100语句，Session 验证允许 SELECT/WITH/VALUES，Application 发布仍要求 INSERT；无 SQL Gateway。SQL SET 填 Flink 配置区域。删除部署草稿需清关联并迁移仍被 SQL 草稿引用的专属文件。Catalog 删除有引用保护。

2026-10-15：验证结果面板移出 workbench 的裁剪容器，单表滚动/固定表头、多 Sink 切换及工具栏/结果区停止。Runner 在展开变量前剥离显式 INSERT 目标表的连接选项，保留字段/主键作映射，再生成单 STRING 字段 Print 表；JSON 携带 columns/cells/keys/target，保留 RowKind、NULL 和大整数精度。源表加 SELECT 无需 Sink，CTAS 不执行；Application 普通 prepare 不变。后端只扩展内存事件结构，原1000条/两分钟、取消/UID/游标保护保留。前端按主键 upsert 或无主键多重集合并撤回，仅显示最终输出字段；未匹配撤回及旧 Runner 结构缺失明确提示，不猜测旧文本。新版 Runner 须下载、上传并重新选择，旧选择不自动覆盖。

已知边界：实际 Planner/Connector 语义由 Flink 执行；普通 Session 停止可能等待其任务结束；本轮 Parser1.20.3/1.20.5、Runner1.20.3 API、Flink1.20.5/JDK17 Application/Session 实测不代表其他版本、生产集群或所有连接器已验证。

## 定位代码与验证

- [SqlJobServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/sql/impl/SqlJobServiceImpl.java)
- [SqlParserServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/sql/impl/SqlParserServiceImpl.java)
- [SqlExecutionServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/sql/impl/SqlExecutionServiceImpl.java)
- [SqlValidationLogReader.java](../../../backend/src/main/java/com/flinkweb/service/sql/support/SqlValidationLogReader.java)、[SessionRestClient.java](../../../backend/src/main/java/com/flinkweb/client/flink/SessionRestClient.java)、[受限正文](../../../backend/src/main/java/com/flinkweb/client/flink/BoundedResponseBody.java)
- [SessionClusterServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/session/impl/SessionClusterServiceImpl.java)
- [ValidationJarBundle.java](../../../backend/src/main/java/com/flinkweb/service/sql/support/ValidationJarBundle.java)
- [SqlRunner.java](../../../sql-runner/src/main/java/com/flinkweb/sql/SqlRunner.java)
- [Application 回归](../../../scripts/check_sql_application.py)、[JDK/JAR 回归](../../../scripts/check_sql_jars.py)
- [ParserMain.java](../../../sql-validator/src/main/java/com/flinkweb/validation/ParserMain.java)
- [SqlStudioView.vue](../../../frontend/src/modules/sql/pages/SqlStudioView.vue)
- [最终结果面板](../../../frontend/src/modules/sql/components/SqlVerificationResults.vue)、[变更流合并](../../../frontend/src/modules/sql/utils/verificationResults.ts)、[结果回归](../../../frontend/tests/verification-results.spec.ts)
- [SessionsView.vue](../../../frontend/src/modules/session/pages/SessionsView.vue)
- [共享配置编辑器](../../../frontend/src/shared/composables/useDeploymentConfigurationEditor.ts)、[转换版本回归](../../../frontend/tests/configuration-editor.spec.ts)、[正文预算回归](../../../backend/src/test/java/com/flinkweb/client/flink/SessionRestClientTest.java)
- [SessionDevelopmentIntegrationTest.java](../../../backend/src/test/java/com/flinkweb/SessionDevelopmentIntegrationTest.java)
- [固定日志协议](../../../backend/src/main/java/com/flinkweb/config/sql/SqlValidationLog.java)、[编辑器支持](../../../frontend/src/shared/utils/sqlEditing.ts)、[真实 Session 日志回归](../../../scripts/check_sql_validation.py)
- [SqlParserServiceTest.java](../../../backend/src/test/java/com/flinkweb/service/SqlParserServiceTest.java)

2026-10-13 归档补核：SqlJobServiceImpl.deploymentLink 在读取时兼容失效关联；JobServiceImpl.delete 先迁移仍被 SQL 草稿引用的专属依赖，再清原目录并解除 jobId/publishedAt。SQL 有有效部署关联时不能直接删草稿，JAR 开发映射只在新建或首次编辑时登记。页面检查状态绑定当前 SQL/配置，后端提交再次检查；验证 JAR 分发不能热更新 Session 环境、非 JAR 文件或 Flink 插件。Session 分区表单与草稿删除入口已有实现/测试定义，本轮未运行。来源和历史验收见 [归档核对](../validation.md#sql-与-session-开发聊天归档核对2026-10-13)。

## Session UI 与 SQL 编辑提示（2026-10-15）

SessionStateDetail 将完整脱敏错误浮层挂到 body，支持悬浮/聚焦/点击、Esc 关闭，JSON 格式化并限制滚动区域；右上角统一刷新状态，保留三秒冷却及按需观测，Pod 抽屉和 Flink UI 链接保留。最新位置要求替代旧逐行刷新。

Session Flink UI 的 `/config` 改写嵌套 `features.web-submit/web-cancel/web-rescale=false`，禁止代理 JAR 路径；普通 Session 渲染强制 `web.submit.enable=false`。真实 Flink1.20.5 证明此开关也关闭 JAR REST，因此专用验证 Session 固定为 true，保留后台提交，面向用户的代理仍隐藏并拦截。不能把 UI 限制等同于直接暴露 Flink REST 的网络权限控制；按原部署边界通过平台访问。SQL Runner、日志、1000条及截止取消不变。

SQL 提示参考 [Flink2.3 SQL](https://nightlies.apache.org/flink/flink-docs-release-2.3/docs/sql/overview/)、[数据类型](https://nightlies.apache.org/flink/flink-docs-release-2.3/docs/sql/reference/data-types/) 及各官方 Connector 文档，提供 Kafka/Upsert Kafka、JDBC、文件、DataGen、Print、BlackHole、Elasticsearch7、OpenSearch、HBase2.2、Kinesis、MongoDB 选项/值和说明。仅当前 WITH 上下文匹配，排除注释及已用键。修正 StandardSQL.spec 为空及自定义词表大小写造成基础关键词失去解析的问题，关键词/类型/字符串分别高亮。编辑提示不代表 Connector 已安装或新增2.3运行认证；精确 Parser 版本仍为1.20.3/1.20.5。

## 结果原因与验证范围修复（2026-10-16）

事件增加可选 structureStatus=STRUCTURED/TOO_WIDE/LEGACY_TEXT/INVALID_STRUCTURE。后端完整核对列、单元格及主键后保留100列结构上限，超宽仍计入事件数；前端分别引导减少字段、更新旧Runner、排查异常结构，兼容未携带新状态的事件，不猜测旧文本。基础验证遮蔽引用内容后提前拒绝CTAS，SELECT/CTE/VALUES仍可验证，Application规则保留；Runner本轮未改。验证见 [本轮验收](../validation.md#ai-与-sql-五项修复2026-10-16)。

## 结果事件标识预计算（2026-10-19）

materializeVerification在候选行扫描前计算一次eventIdentity，候选行继续按原顺序/主键或整行标识匹配；未增加跨事件缓存。1000条唯一插入与1000条混合事件均与原HEAD实现结果完全一致，序列化次数和累计输出字符下降，不能外推实际生产延迟或堆占用。新增混合主键更新、无主键重复、匹配及未匹配撤回回归。测量范围见 [本次验收](../validation.md#ai-准备阶段取消与结果标识优化2026-10-19)。
