# 作业生命周期

更新：2026-10-20。关联 [需求](../requirements/REQ-001-lifecycle.md)、[决策](../decisions/ADR-002-safe-stop.md)；来源：当前代码与 [功能说明](../../features.md)。

职责：作业查询/纳管、身份绑定、启动停止、快照、诊断和只读 Flink UI。调用链由 controller → query/management/operation/stop 服务 → repository/collector/KubernetesClient；前端 JobsView/工作台按需发起。

Savepoint 入口 `StopServiceImpl.stop` 记录意图及加密实际配置，`advance` 在手动刷新续做，`proceed` 检查停止 generation、资源身份和协调状态，`upgradeSavepointConfirmed` 区分真实 UPGRADE Savepoint 与 Checkpoint，`restartResource` 按已部署配置恢复。操作记录 requestId 幂等；草稿 revision 与集群 UID/resourceVersion 各保护不同资源。当前状态、期望状态、草稿分别展示，ACCEPTED 不等于 RUNNING。

关键约束：保存恢复路径先于资源删除；旧 UID/外部重建不可继续旧请求；保存点未确认保留资源；立即删除是独立策略；停止保留平台草稿和目录，删除草稿需集群资源消失后才清专属 UUID 路径。作业名唯一，资源名由自增编号生成，旧纳管资源在停止后再切换编号名。低版本无快照 CRD 及快照读取失败不能破坏已取得的部署状态。

诊断入口为 PodStatusDrawer 和 DiagnosticServiceImpl；日志最多10000行、尾部4 MiB、传输64 MiB，核验归属。FlinkUiServiceImpl 代理只读受控目标。刷新规则另见 [ADR-001](../decisions/ADR-001-on-demand.md)。

2026-10-13：Session 复用相同 Pod/事件/日志投影与抽屉，先由 SessionClusterService.observedResource 校验 Session 标签/UID，再校验 Pod 所有者链；新增共享基础设施入口不需要伪造作业草稿。Session 日志只允许管理员/运维，UI 代理沿用敏感路径和配置脱敏边界；事件二次读取也重新核对 Pod 归属。

已知边界：本轮本地回归通过；现场保存点恢复历史仅验证过特定 test2/Flink 1.20.5，不能推及所有版本。修改停止流程需优先检查 WorkbenchIntegrationTest 与 KubernetesClientTest。

2026-10-20 SQL例外：启动/恢复先核对保存SQL是否已发布。SQL的已发布参数与实际资源或停止快照不同，具备平台开发角色和作业编辑权限时使用最新已发布完整草稿，避免启动旧SQL；仅运维权限拒绝替换。stateless新SQL启动不附加旧自动保存点，显式恢复的选择路径仍保留；普通JAR与未变SQL继续复用原快照。实现及验证入口见[SQL模块](sql-session.md)，此项补充下方“仅ServiceAccount例外”的历史规则。

## 定位代码与验证

- [StopServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/job/runtime/impl/StopServiceImpl.java)
- [OperationServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/job/runtime/impl/OperationServiceImpl.java)
- [JobQueryServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/job/management/impl/JobQueryServiceImpl.java)
- [JobAdoptionServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/job/management/impl/JobAdoptionServiceImpl.java)
- [FlinkStateCollector.java](../../../backend/src/main/java/com/flinkweb/collector/job/FlinkStateCollector.java)
- [KubernetesClient.java](../../../backend/src/main/java/com/flinkweb/client/kubernetes/KubernetesClient.java)
- [JobsView.vue](../../../frontend/src/modules/job-management/pages/JobsView.vue)
- [WorkbenchIntegrationTest.java](../../../backend/src/test/java/com/flinkweb/WorkbenchIntegrationTest.java)

2026-10-12 账号规则：OperationServiceImpl 对有编辑权限操作者重建停止快照时，将已保存草稿显式 serviceAccount 合入；仅该字段例外，其余快照配置保持原规则。运维不可借启动发布草稿，显式 deploy 始终取最新草稿。

补充适用范围：ServiceAccount 例外同时覆盖旧资源仍存在的直接启动/恢复和资源删除后的重建；平台编辑角色与作业编辑权限同时满足才采用草稿账号。最终176项后端回归和实际镜像闭环通过。

## 前端启动与历史故障定位（2026-10-13 补核）

- FlinkStateCollector 将 JobManager ERROR、status.error 与失败状态优先归为异常；DeploymentYamlSnapshot 对 status 保留原文，metadata/spec 单独清理。当前 YAML 使用 CodeEditor 与剪贴板工具。顶部进度只展示未完成阶段，详情操作记录共用页签。
- 10-08/10-09 两种前端启动故障不同：静态 upstream 解析失败由动态 resolver 修复；端口参数与 Service 注入的 tcp:// 变量重名由独立 FLINK_WEB_PROXY_BACKEND_HOST/PORT 修复。脚本默认 Service:8080 可用于独立部署；当前合并 StatefulSet 显式代理 127.0.0.1:8081。enableServiceLinks=false 是清单配置，修复镜像本身仍兼容注入变量。
- 10-04 Pod 内 /overview 也返回 Metaspace，证据指向 Flink JobManager，而非 Nginx。10-08 test2 调整内存后 UI 恢复的历史报告见验收记录；本次未访问现场，不将旧测试内存或旧 Deployment 名称作为当前部署配置。

定位：[YAML快照](../../../backend/src/main/java/com/flinkweb/collector/job/DeploymentYamlSnapshot.java)、[启动脚本](../../../frontend/docker-entrypoint.sh)、[Nginx](../../../frontend/nginx.conf)、[代理容器回归](../../../scripts/check_web_proxy.py)、[运行补丁](../../../deploy/kubernetes/frontend-runtime-patch.yaml)。更新工作负载使用部署指南的当前资源类型，并另有对应授权。

本 SQL/Session 聊天的生产403补核（2026-10-13）：用户明确同 namespace、复用 Operator SA，并反馈平台可读取 Operator Pod。该证据仅证明相应请求可用，不能证明所有资源获准；自动挂载不创建 RBAC。历史“快照403会导致整个列表失败”描述的是当时代码，现行 collectSnapshots 已隔离缺失CRD/403/网络失败，保留部署结果并返回 snapshotQuery 警告；[KubernetesClientTest](../../../backend/src/test/java/com/flinkweb/KubernetesClientTest.java) 已有对应定义。本聊天没有被拒绝的准确请求/资源与最终恢复反馈，不把缺少快照或 StatefulSet 读取权限断言为已确认现场根因。

2026-10-15：Session入口新增UI features改写与JAR路径限制，作业Flink UI行为不改；普通Session关闭运行时Web提交，专用验证保留内部REST。具体代码、真实Flink验证及最新统一刷新/错误浮层见 [SQL/Session模块](sql-session.md)。
