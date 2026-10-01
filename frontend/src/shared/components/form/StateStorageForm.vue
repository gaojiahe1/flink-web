<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { request } from '../../api/http'
import DurationInput from './DurationInput.vue'
const props = defineProps<{
  modelValue: Record<string, string>
  version: string
  scopeId: string
  disabled?: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [Record<string, string>]
  busy: [boolean]
}>()
type StorageDefaults = { endpoint: string; stateRoot: string }
const defaults = ref<StorageDefaults>()
const configuring = ref(false),
  error = ref('')
let defaultsRequest: Promise<StorageDefaults> | undefined
let disposed = false
onBeforeUnmount(() => {
  disposed = true
  emit('busy', false)
})
async function loadDefaults() {
  if (defaults.value) return defaults.value
  if (!defaultsRequest) defaultsRequest = request<StorageDefaults>('/api/artifacts/defaults')
  try {
    defaults.value = await defaultsRequest
    return defaults.value
  } finally {
    defaultsRequest = undefined
  }
}
onMounted(() => {
  void loadDefaults().catch(() => {
    /* 启用时重试并明确提示。 */
  })
})
const v2 = computed(() => props.version.startsWith('2'))
function configKey(legacy: string, modern: string) {
  const preferred = v2.value ? modern : legacy,
    alternative = v2.value ? legacy : modern
  return props.modelValue[preferred] !== undefined
    ? preferred
    : props.modelValue[alternative] !== undefined
      ? alternative
      : preferred
}
const keys = computed(() => ({
  cp: configKey('state.checkpoints.dir', 'execution.checkpointing.dir'),
  sp: configKey('state.savepoints.dir', 'execution.checkpointing.savepoint-dir'),
  count: configKey('state.checkpoints.num-retained', 'execution.checkpointing.num-retained'),
  storage: configKey('state.checkpoint-storage', 'execution.checkpointing.storage'),
}))

function set(key: string, value: string) {
  const next = { ...props.modelValue }
  if (value === '') delete next[key]
  else next[key] = value
  emit('update:modelValue', next)
}
function input(key: string, event: Event) {
  set(key, (event.target as HTMLInputElement).value)
}
const enabled = computed(() =>
  Object.keys(props.modelValue).some((k) =>
    ['execution.checkpointing.interval', keys.value.cp, keys.value.sp, keys.value.storage].includes(
      k,
    ),
  ),
)
async function toggle(event: Event) {
  const checkbox = event.target as HTMLInputElement
  error.value = ''
  const scopeId = props.scopeId
  if (!checkbox.checked) {
    const next = { ...props.modelValue }
    for (const key of Object.keys(next))
      if (
        key.startsWith('execution.checkpointing.') ||
        key.startsWith('state.checkpoints.') ||
        key === 'state.checkpoint-storage' ||
        key === 'state.savepoints.dir' ||
        key.startsWith('s3.') ||
        key === 'high-availability' ||
        key.startsWith('high-availability.')
      )
        delete next[key]
    emit('update:modelValue', next)
    return
  }
  configuring.value = true
  emit('busy', true)
  try {
    const connection = await loadDefaults()
    if (disposed || scopeId !== props.scopeId) return
    const root = connection.stateRoot.replace(/\/+$/, '')
    if (!/^s3:\/\/[^/]+/.test(root) || !props.scopeId)
      throw new Error('平台 S3 状态目录配置无效，请由管理员检查状态存储配置')
    const next = { ...props.modelValue }
    next['execution.checkpointing.interval'] = '60 s'
    next[keys.value.count] = '1'
    next[keys.value.storage] = 'filesystem'
    next[keys.value.cp] = root + '/' + props.scopeId + '/checkpoints'
    next[keys.value.sp] = root + '/' + props.scopeId + '/savepoints'
    next['execution.checkpointing.externalized-checkpoint-retention'] = 'RETAIN_ON_CANCELLATION'
    if (connection.endpoint) next['s3.endpoint'] = connection.endpoint
    else delete next['s3.endpoint']
    delete next['s3.region']
    next['s3.access-key'] = '{{secret:s3-access-key}}'
    next['s3.secret-key'] = '{{secret:s3-secret-key}}'
    next['s3.path.style.access'] = 'true'
    next['high-availability.type'] = 'kubernetes'
    next['high-availability.storageDir'] = root + '/' + props.scopeId + '/ha'
    emit('update:modelValue', next)
  } catch (e) {
    checkbox.checked = false
    error.value = '未启用状态存储：' + (e as Error).message
  } finally {
    configuring.value = false
    emit('busy', false)
  }
}
const retentionDescription = computed(() => {
  const policy = props.modelValue['execution.checkpointing.externalized-checkpoint-retention']
  if (policy === 'RETAIN_ON_CANCELLATION')
    return '取消作业后保留外部 Checkpoint，需手动清理；Savepoint 独立保留。'
  if (policy === 'DELETE_ON_CANCELLATION')
    return '正常取消作业时由 Flink 清理外部 Checkpoint；立即删除集群可能来不及清理，仍需检查 S3。Savepoint 不受影响。'
  if (policy === 'NO_EXTERNALIZED_CHECKPOINTS')
    return '不保留用于作业结束后恢复的外部 Checkpoint；运行期间仍按间隔做检查点。Savepoint 不受影响。'
  return '使用当前 Flink 版本的默认外部 Checkpoint 保留行为。'
})
const strategyDescriptions: Record<string, { title: string; text: string }> = {
  '': {
    title: 'Flink 默认',
    text: '采用当前 Flink 版本和集群的默认恢复策略，适合已统一配置恢复行为的环境。',
  },
  none: {
    title: '不重试',
    text: '作业失败后直接结束，不自动恢复，适合排错或需要人工确认后再启动的任务。',
  },
  'fixed-delay': {
    title: '固定间隔重试',
    text: '失败后等待固定时间再尝试恢复，达到最大重试次数后结束。适合短暂网络抖动或依赖服务偶发不可用。',
  },
  'failure-rate': {
    title: '失败率限制',
    text: '在指定统计周期内限制失败次数，每次恢复前等待固定间隔；超过阈值后结束。适合长期运行、允许少量偶发失败但需要限制频繁故障的任务。',
  },
  'exponential-delay': {
    title: '指数退避',
    text: '连续失败时按倍数延长等待时间，最长不超过上限；随机抖动可分散重试压力，稳定运行达到重置间隔后恢复初始等待。适合依赖服务需要较长时间恢复的场景。',
  },
}
const restartKey = computed(() =>
  /^1\.(16|17|18)(\.|$)/.test(props.version) ? 'restart-strategy' : 'restart-strategy.type',
)
const strategy = computed(
  () =>
    props.modelValue[restartKey.value] ||
    props.modelValue['restart-strategy.type'] ||
    props.modelValue['restart-strategy'] ||
    '',
)
const strategyFields: Record<
  string,
  { key: string; label: string; initial: string; numeric?: boolean; min?: number; step?: string }[]
> = {
  'fixed-delay': [
    { key: 'attempts', label: '最大重试次数', initial: '3', numeric: true, min: 0 },
    { key: 'delay', label: '重试间隔', initial: '10 s' },
  ],
  'failure-rate': [
    {
      key: 'max-failures-per-interval',
      label: '周期内最大失败次数',
      initial: '3',
      numeric: true,
      min: 1,
    },
    { key: 'failure-rate-interval', label: '失败统计周期', initial: '5 min' },
    { key: 'delay', label: '重试间隔', initial: '10 s' },
  ],
  'exponential-delay': [
    { key: 'initial-backoff', label: '初始等待', initial: '1 s' },
    { key: 'max-backoff', label: '最大等待', initial: '1 min' },
    {
      key: 'backoff-multiplier',
      label: '退避倍数',
      initial: '2',
      numeric: true,
      min: 1,
      step: 'any',
    },
    { key: 'reset-backoff-threshold', label: '稳定运行后重置间隔', initial: '1 h' },
    {
      key: 'jitter-factor',
      label: '随机抖动比例（0—1）',
      initial: '0.1',
      numeric: true,
      min: 0,
      step: 'any',
    },
  ],
}
function chooseRestart(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  const next = { ...props.modelValue }
  for (const key of Object.keys(next))
    if (key === 'restart-strategy' || key.startsWith('restart-strategy.')) delete next[key]
  if (value) next[restartKey.value] = value
  for (const field of strategyFields[value] || [])
    next['restart-strategy.' + value + '.' + field.key] = field.initial
  emit('update:modelValue', next)
}
watch(
  () => props.version,
  (version, old) => {
    if (!old || old === version) return
    const next = { ...props.modelValue }
    for (const [legacy, modern] of [
      ['state.checkpoints.dir', 'execution.checkpointing.dir'],
      ['state.savepoints.dir', 'execution.checkpointing.savepoint-dir'],
      ['state.checkpoints.num-retained', 'execution.checkpointing.num-retained'],
      ['state.checkpoint-storage', 'execution.checkpointing.storage'],
    ]) {
      const from = v2.value ? legacy! : modern!,
        to = v2.value ? modern! : legacy!
      if (next[from] !== undefined) {
        next[to] = next[from]!
        delete next[from]
      }
    }
    const opposite =
      restartKey.value === 'restart-strategy' ? 'restart-strategy.type' : 'restart-strategy'
    if (next[opposite]) {
      next[restartKey.value] = next[opposite]!
      delete next[opposite]
    }
    emit('update:modelValue', next)
  },
)
</script>
<template>
  <section class="panel form-panel compact-form state-storage-form">
    <h3>状态存储与故障恢复</h3>
    <fieldset :disabled="disabled || configuring">
      <label class="toggle">
        <input
          type="checkbox"
          :checked="enabled"
          :aria-invalid="error ? 'true' : undefined"
          @change="toggle"
        />
        启用状态存储与 Checkpoint
      </label>
      <p class="muted">
        启用后默认使用 S3，存储路径和连接参数自动写入下方 Flink 配置；已有作业保留原配置。
      </p>
      <p v-if="configuring" class="muted" role="status">正在生成 S3 状态配置…</p>
      <p v-if="error" class="field-error" role="alert">{{ error }}</p>
      <div v-if="enabled" class="field-grid">
        <DurationInput
          label="Checkpoint 间隔"
          :min="0.000001"
          :model-value="modelValue['execution.checkpointing.interval']"
          @update:model-value="set('execution.checkpointing.interval', $event)"
        />
        <label
          >保留 Checkpoint 数量<input
            data-field="flinkConfiguration"
            type="number"
            min="1"
            step="1"
            required
            :value="modelValue[keys.count]"
            @input="input(keys.count, $event)"
        /></label>
        <label
          >Checkpoint 存储策略
          <select
            aria-label="Checkpoint 存储策略"
            :value="modelValue['execution.checkpointing.externalized-checkpoint-retention'] || ''"
            @change="input('execution.checkpointing.externalized-checkpoint-retention', $event)"
          >
            <option value="">Flink 默认</option>
            <option value="RETAIN_ON_CANCELLATION">取消后保留</option>
            <option value="DELETE_ON_CANCELLATION">取消后删除</option>
            <option value="NO_EXTERNALIZED_CHECKPOINTS">不保留外部 Checkpoint</option>
          </select>
        </label>
        <p class="muted retention-description">{{ retentionDescription }}</p>
      </div>
      <div class="field-grid">
        <label
          >故障恢复策略<select aria-label="故障恢复策略" :value="strategy" @change="chooseRestart">
            <option value="">Flink 默认</option>
            <option value="none">不重试</option>
            <option value="fixed-delay">固定间隔重试</option>
            <option value="failure-rate">失败率限制</option>
            <option value="exponential-delay">指数退避</option>
          </select></label
        >
        <template v-for="field in strategyFields[strategy] || []" :key="field.key">
          <label v-if="field.numeric"
            >{{ field.label }}
            <input
              data-field="flinkConfiguration"
              type="number"
              :min="field.min"
              :max="field.key === 'jitter-factor' ? 1 : undefined"
              :step="field.step || '1'"
              required
              :value="modelValue['restart-strategy.' + strategy + '.' + field.key]"
              @input="input('restart-strategy.' + strategy + '.' + field.key, $event)"
            />
          </label>
          <DurationInput
            v-else
            :label="field.label"
            :model-value="modelValue['restart-strategy.' + strategy + '.' + field.key]"
            @update:model-value="set('restart-strategy.' + strategy + '.' + field.key, $event)"
          />
        </template>
      </div>
      <p class="strategy-description" aria-live="polite">
        {{
          strategyDescriptions[strategy]?.text ||
          '当前使用自定义策略，可在下方 Flink 配置中查看和修改。'
        }}
      </p>
      <details class="strategy-help">
        <summary>查看全部恢复策略说明</summary>
        <dl>
          <template v-for="(description, key) in strategyDescriptions" :key="key"
            ><dt>{{ description.title }}</dt>
            <dd>{{ description.text }}</dd></template
          >
        </dl>
      </details>
    </fieldset>
  </section>
</template>
<style scoped>
fieldset {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}
.toggle {
  display: flex;
  align-items: center;
  gap: 8px;
}
.toggle input {
  width: auto;
}
.field-grid {
  margin-top: 14px;
}
.strategy-description {
  padding: 10px 12px;
  background: var(--surface-soft, #f5f8fc);
  border-radius: 6px;
  line-height: 1.7;
}
.strategy-help {
  margin-top: 12px;
  color: var(--muted, #62758b);
  line-height: 1.7;
}
.strategy-help dt {
  font-weight: 600;
  margin-top: 10px;
}
.strategy-help dd {
  margin: 2px 0 0;
}
</style>
