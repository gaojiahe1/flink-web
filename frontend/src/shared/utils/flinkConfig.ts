import { parseDocument } from 'yaml'

// 常用配置来自 Flink 2.3 deployment/config；旧版名称单独标记，不自动迁移用户配置。
export const flinkConfigHints = [
  ['execution.checkpointing.interval', '检查点周期', '60 s'],
  ['execution.checkpointing.timeout', '检查点超时', '10 min'],
  ['execution.checkpointing.min-pause', '两次检查点最小间隔', '10 s'],
  ['execution.checkpointing.max-concurrent-checkpoints', '并发检查点数', '1'],
  ['execution.checkpointing.mode', '一致性模式', 'EXACTLY_ONCE'],
  ['execution.checkpointing.tolerable-failed-checkpoints', '可容忍连续失败次数', '0'],
  ['execution.checkpointing.unaligned.enabled', '非对齐检查点', 'false'],
  ['execution.checkpointing.dir', '检查点目录（新版）', 's3://bucket/checkpoints'],
  ['execution.checkpointing.savepoint-dir', '保存点目录（新版）', 's3://bucket/savepoints'],
  ['execution.checkpointing.num-retained', '已完成检查点保留数（新版）', '1'],
  ['execution.checkpointing.storage', '检查点存储实现（新版）', 'filesystem'],
  ['state.checkpoints.dir', '检查点目录（旧版）', 's3://bucket/checkpoints'],
  ['state.savepoints.dir', '保存点目录（旧版）', 's3://bucket/savepoints'],
  ['state.checkpoints.num-retained', '检查点保留数（旧版）', '1'],
  ['state.backend.type', '状态后端', 'rocksdb'],
  ['state.backend.incremental', '增量快照', 'true'],
  ['taskmanager.numberOfTaskSlots', '每个 TM 的槽数', '1'],
  ['taskmanager.memory.process.size', 'TM 进程内存', '2048m'],
  ['jobmanager.memory.process.size', 'JM 进程内存', '1024m'],
  ['parallelism.default', '默认并行度', '1'],
  ['restart-strategy.type', '重启策略', 'fixed-delay'],
  ['restart-strategy.fixed-delay.attempts', '固定间隔重试次数', '3'],
  ['restart-strategy.fixed-delay.delay', '重试间隔', '10 s'],
  ['restart-strategy.failure-rate.max-failures-per-interval', '窗口内失败次数', '3'],
  ['restart-strategy.failure-rate.failure-rate-interval', '失败统计窗口', '5 min'],
  ['restart-strategy.failure-rate.delay', '失败后等待', '10 s'],
  ['pipeline.name', '管道名称', 'orders'],
] as const

export type ConfigIssue = { line: number; message: string }
export function parseFlinkConfig(text: string): {
  values: Record<string, string>
  issues: ConfigIssue[]
} {
  const values: Record<string, string> = Object.create(null),
    issues: ConfigIssue[] = []
  text.split(/\r?\n/).forEach((line, index) => {
    const raw = line.trim()
    if (!raw || raw.startsWith('#')) return
    const colon = raw.indexOf(':')
    const key = raw.slice(0, colon).trim(),
      content = raw.slice(colon + 1).trim()
    if (colon < 1 || !/^[A-Za-z0-9_.-]+$/.test(key)) {
      issues.push({ line: index + 1, message: '使用 key:value，每行一项' })
      return
    }
    if (Object.hasOwn(values, key)) {
      issues.push({ line: index + 1, message: '配置键重复：' + key })
      return
    }
    if (!content) {
      issues.push({ line: index + 1, message: '配置值不能为空；空字符串请填写 ""' })
      return
    }
    let value = content
    if (content.startsWith('"') || content.startsWith("'")) {
      const document = parseDocument(content)
      if (document.errors.length || typeof document.toJS() !== 'string') {
        issues.push({ line: index + 1, message: '带引号的值必须是完整字符串' })
        return
      }
      value = document.toJS()
    }
    values[key] = value
  })
  return { values, issues }
}
export function formatFlinkConfig(values: Record<string, string>): string {
  return Object.entries(values)
    .map(
      ([key, value]) =>
        key +
        ': ' +
        (value.trim() !== value || value === '' || /[\r\n]/.test(value) || /^['"]/.test(value)
          ? JSON.stringify(value)
          : value),
    )
    .join('\n')
}
