# 当前状态

更新：2026-10-12。当前修复分支 `codex/fix-state-root-s3-config`；治理基线已保存在先前本地提交。

- 已修复 FLINK_WEB_STATE_ROOT 属性读取错误，完整桶/前缀用于新建状态目录和专属清理；既有手工目录不自动迁移。
- Flink S3 AK/SK 在 flinkConfiguration 原位置展开，清理主容器旧同名AWS变量，下载器鉴权保留；预览/历史仍隐藏密钥。对应 [REQ-004](requirements/REQ-004-storage-security.md)。
- 旧作业的显式部署采用最新草稿；具备平台编辑角色与作业编辑权限的启动/恢复采用已保存草稿的显式ServiceAccount，覆盖旧资源直接启动和停止后重建；仅运维权限保持实际运行账号。对应 [REQ-002](requirements/REQ-002-authoring.md)。
- 本轮后端176项、实际Web的6项账号/模板回归通过；真实Parser、隔离MinIO、构建/格式/分层及实际镜像加密登录/配置/提交闭环通过。真实Kubernetes/Operator/账号RBAC仍未验收。
- app/web 0.0.4已发布并核对指定仓库摘要，downloader未改；发布方法与摘要见 [部署指南](../deployment.md)。未更新实际工作负载，现有Pod需按授权流程重新拉取镜像。
- 安全治理结果与未知备份/数据库、固定部署凭据等待确认项继续保留，见 [清理报告](cleanup-report.md)、[待确认事项](open-questions.md)。

下一次任务从 [INDEX](INDEX.md) 按关键词定位，仅增量维护受影响内容。
