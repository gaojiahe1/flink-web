// 保留审计原始动作码，只在展示和搜索时使用中文，兼容历史记录。
const actions: Record<string, string> = {
  SESSION_SAVE: '保存 Session 集群',
  SESSION_START: '启动 Session 集群',
  SESSION_STOP: '停止 Session 集群',
  SESSION_DELETE: '删除 Session 集群',
  USER_SAVE: '保存用户',
  USER_DELETE: '删除用户',
  ACCESS_UPDATE: '更新作业权限',
  GROUP_SAVE: '保存用户组',
  DOWNLOADER_IMAGE_UPDATE: '更新下载器镜像',
  CATALOG_SAVE: '保存资源配置',
  CATALOG_DELETE: '删除资源配置',
  DATASOURCE_PROBE: '验证 Catalog 连接',
  FLINK_CATALOG_SAVE: '保存 Catalog',
  FLINK_CATALOG_DELETE: '删除 Catalog',
  DRAFT_CREATE: '创建作业草稿',
  DRAFT_UPDATE: '更新作业草稿',
  DRAFT_DELETE: '删除作业草稿',
  DRAFT_FORM_CONVERT: '转换作业表单',
  DRAFT_COPY: '复制作业草稿',
  JOB_ADOPT: '纳管作业',
  JOB_START: '启动作业',
  JOB_STOP: '停止作业',
  JOB_RESTART: '重启作业',
  JOB_RESTORE: '从快照恢复作业',
  JOB_SAVEPOINT: '创建保存点',
  JOB_CHECKPOINT: '触发检查点',
  JOB_DEPLOY: '部署作业',
  JOB_LOG_READ: '查看作业容器日志',
  SESSION_LOG_READ: '查看 Session 容器日志',
  AI_TASK_CREATE: '创建 AI 分析任务',
  AI_TASK_CANCEL: '取消 AI 分析任务',
  AI_MODEL_SAVE: '保存 AI 模型',
  AI_MODEL_DELETE: '删除 AI 模型',
  AI_MODEL_TEST: '测试 AI 模型',
  SQL_VERIFY: '验证 SQL',
  SQL_SAVE: '保存 SQL 草稿',
  SQL_PUBLISH: '发布 SQL 作业',
  SQL_DELETE: '删除 SQL 草稿',
  SQL_COPY: '复制 SQL 草稿',
}
const outcomes: Record<string, string> = {
  SUCCESS: '成功',
  ACCEPTED: '已受理',
  FAILED: '失败',
  UNKNOWN: '结果待确认',
  RUNNING: '执行中',
  SUCCEEDED: '成功',
  CANCELED: '已取消',
  REJECTED: '已拒绝',
}
export const auditActionLabel = (value: unknown) => actions[String(value)] || String(value || '—')
export const auditOutcomeLabel = (value: unknown) => outcomes[String(value)] || String(value || '—')

export const auditTime = (row: Record<string, any>) => {
  for (const value of [row.time, row.createdAt]) {
    const date = new Date(value || '')
    if (Number.isFinite(date.getTime())) return date
  }
  return undefined
}
export const auditTimeLabel = (row: Record<string, any>) => {
  const date = auditTime(row)
  return date
    ? new Intl.DateTimeFormat('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false,
      }).format(date)
    : '时间未记录'
}
