# 作业生命周期

更新：2026-10-12。关联 [需求](../requirements/REQ-001-lifecycle.md)、[决策](../decisions/ADR-002-safe-stop.md)；来源：当前代码与 [功能说明](../../features.md)。

职责：作业查询/纳管、身份绑定、启动停止、快照、诊断和只读 Flink UI。调用链由 controller → query/management/operation/stop 服务 → repository/collector/KubernetesClient；前端 JobsView/工作台按需发起。

Savepoint 入口 `StopServiceImpl.stop` 记录意图及加密实际配置，`advance` 在手动刷新续做，`proceed` 检查停止 generation、资源身份和协调状态，`upgradeSavepointConfirmed` 区分真实 UPGRADE Savepoint 与 Checkpoint，`restartResource` 按已部署配置恢复。操作记录 requestId 幂等；草稿 revision 与集群 UID/resourceVersion 各保护不同资源。当前状态、期望状态、草稿分别展示，ACCEPTED 不等于 RUNNING。

关键约束：保存恢复路径先于资源删除；旧 UID/外部重建不可继续旧请求；保存点未确认保留资源；立即删除是独立策略；停止保留平台草稿和目录，删除草稿需集群资源消失后才清专属 UUID 路径。作业名唯一，资源名由自增编号生成，旧纳管资源在停止后再切换编号名。低版本无快照 CRD 及快照读取失败不能破坏已取得的部署状态。

诊断入口为 PodStatusDrawer 和 DiagnosticServiceImpl；日志最多10000行、尾部4 MiB、传输64 MiB，核验归属。FlinkUiServiceImpl 代理只读受控目标。刷新规则另见 [ADR-001](../decisions/ADR-001-on-demand.md)。

已知边界：本轮本地回归通过；现场保存点恢复历史仅验证过特定 test2/Flink 1.20.5，不能推及所有版本。修改停止流程需优先检查 WorkbenchIntegrationTest 与 KubernetesClientTest。

## 定位代码与验证

- [StopServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/job/runtime/impl/StopServiceImpl.java)
- [OperationServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/job/runtime/impl/OperationServiceImpl.java)
- [JobQueryServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/job/management/impl/JobQueryServiceImpl.java)
- [JobAdoptionServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/job/management/impl/JobAdoptionServiceImpl.java)
- [FlinkStateCollector.java](../../../backend/src/main/java/com/flinkweb/collector/job/FlinkStateCollector.java)
- [KubernetesClient.java](../../../backend/src/main/java/com/flinkweb/client/kubernetes/KubernetesClient.java)
- [JobsView.vue](../../../frontend/src/modules/job-management/pages/JobsView.vue)
- [WorkbenchIntegrationTest.java](../../../backend/src/test/java/com/flinkweb/WorkbenchIntegrationTest.java)
