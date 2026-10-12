# 当前状态

更新：2026-10-12。当前修复分支 `codex/fix-sql-runtime-and-panel`；状态/账号修复与治理基线已保存在先前本地提交。

- 已修复 FLINK_WEB_STATE_ROOT 属性读取错误，完整桶/前缀用于新建状态目录和专属清理；既有手工目录不自动迁移。
- Flink S3 AK/SK 在 flinkConfiguration 原位置展开，清理主容器旧同名AWS变量，下载器鉴权保留；预览/历史仍隐藏密钥。对应 [REQ-004](requirements/REQ-004-storage-security.md)。
- 旧作业的显式部署采用最新草稿；具备平台编辑角色与作业编辑权限的启动/恢复采用已保存草稿的显式ServiceAccount，覆盖旧资源直接启动和停止后重建；仅运维权限保持实际运行账号。对应 [REQ-002](requirements/REQ-002-authoring.md)。
- 本轮修复SQL Runner的local URI覆盖错误，新增辅助面板拖动缩放、统一JAR/SQL名称及Runner/Parser使用说明。Runner/Parser major52，JDK8/17真实运行通过；内置Parser1.20.3/1.20.5，受权限保护的Runner下载入口可用，旧作业须显式选择新文件。
- 本轮后端178项、Runner8项、实际Web的15项页面回归通过；Flink1.20.5/JDK17 Application运行初始SQL并输出，Session动态Connector/CSV、原Sink替换与100条输出通过。构建/格式/分层、非root只读镜像加密登录、PARSER检查及下载哈希一致、Web DNS/代理恢复通过。真实Kubernetes/Operator及其他版本/连接器仍未验收。
- app/web 0.0.4已发布并核对指定仓库摘要，downloader未改；发布方法与摘要见 [部署指南](../deployment.md)。未更新实际工作负载，现有Pod需按授权流程重新拉取镜像。
- 安全治理结果与未知备份/数据库、固定部署凭据等待确认项继续保留，见 [清理报告](cleanup-report.md)、[待确认事项](open-questions.md)。

下一次任务从 [INDEX](INDEX.md) 按关键词定位，仅增量维护受影响内容。
