<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { Sparkles, X } from '@lucide/vue'
import AiModelSelect from './AiModelSelect.vue'
import AiResultReport from './AiResultReport.vue'
import AiDataDefinitionEditor from './AiDataDefinitionEditor.vue'
import CodeEditor from '../../../shared/components/editor/CodeEditor.vue'
import { aiApi, type AiModel, type AiResult, type AiTask } from '../api/ai'
import { useAiTask } from '../composables/useAiTask'
import { mergeDefinitions, validateDefinitions } from '../utils/definitions'
import {
  initialRole,
  roleLabel,
  wizardDataIssues,
  type TableRole,
  type WizardPattern,
  type WizardTable,
} from '../utils/wizard'
const props = defineProps<{
  sql: string
  sqlId: string
  flinkVersion: string
  contextKey: number
  editable: boolean
  checkSummary?: string
  dirty?: boolean
  active?: boolean
  canVerify?: boolean
  sources: (WizardTable & { id: string; name: string })[]
}>()
const emit = defineEmits<{
  close: []
  apply: [AiResult, number, (success: boolean) => void]
  check: []
  save: []
  run: []
  navigation: [boolean[]]
}>()
const step = defineModel<number>('step', { default: 1 })
const followup = ref('')
const selectedTables = computed(() => props.sources.filter((s) => sourceIds.value.includes(s.id)))
function nextStep(value: number) {
  if (
    value === 2 &&
    mode.value !== 'explain' &&
    mode.value !== 'definition' &&
    dataIssues.value.length
  ) {
    localError.value = dataIssues.value.join('；')
    return
  }
  if (value === 3 && !result.value) return
  if (value === 4 && !applied.value) return
  step.value = value
  tab.value = 'conversation'
}
defineExpose({ nextStep })
function chooseMode(value: string) {
  mode.value = value
  nextStep(2)
}
function applySql() {
  if (result.value && canApply.value) {
    applying.value = true
    emit('apply', result.value, base.value, (success) => {
      applying.value = false
      if (success) {
        appliedTask.value = task.value?.id || ''
        appliedVersion.value = props.contextKey
        nextStep(4)
      }
    })
  }
}
async function refine() {
  if (!followup.value.trim()) return
  instruction.value += '\n补充需求：' + followup.value
  followup.value = ''
  nextStep(2)
  compiledMessage.value = '补充已加入需求，请重新确认后生成；当前 SQL 保持不变。'
}
const modelId = ref(''),
  selectedModel = ref<AiModel>(),
  allowExternal = ref(false)
const mode = ref('generate'),
  tab = ref('conversation'),
  instruction = ref('')
const sourceIds = ref<string[]>([]),
  definitionsText = ref('[]'),
  localError = ref('')
const windowMinutes = ref(5),
  eventTime = ref(''),
  dedup = ref(''),
  startPosition = ref('latest-offset')
const pattern = ref<WizardPattern>('transform'),
  timezone = ref('Asia/Shanghai')
const roles = ref<Record<string, TableRole>>({})
const applying = ref(false),
  appliedTask = ref(''),
  appliedVersion = ref(-1)
const applied = computed(() => !!appliedTask.value && appliedVersion.value === props.contextKey)
const localTables = computed(() => {
  try {
    return definitions() as unknown as WizardTable[]
  } catch {
    return []
  }
})
const allTables = computed(() => [...selectedTables.value, ...localTables.value])
const tableRole = (table: WizardTable) =>
  roles.value[table.tableName] ?? initialRole(table.connectorType)
const dataIssues = computed(() => {
  try {
    definitions()
  } catch (cause) {
    return [(cause as Error).message]
  }
  return wizardDataIssues(allTables.value, roles.value)
})
const timeChoices = computed(() =>
  allTables.value.filter((table) => tableRole(table) === 'source' && table.watermarkColumn),
)
const specIssues = computed(() => {
  const issues = [...dataIssues.value]
  if (!dedup.value) issues.push('请确认重复数据处理方式')
  if (pattern.value === 'lookup' && !allTables.value.some((table) => tableRole(table) === 'lookup'))
    issues.push('维表打宽需要选择一张 JDBC 维表')
  if (pattern.value === 'window') {
    if (
      !Number.isInteger(windowMinutes.value) ||
      windowMinutes.value < 1 ||
      windowMinutes.value > 1440
    )
      issues.push('窗口应为1–1440分钟的整数')
    if (
      !eventTime.value ||
      !timeChoices.value.some(
        (table) => table.tableName + '.' + table.watermarkColumn === eventTime.value,
      )
    )
      issues.push('请选择输入表中已定义水位线的事件时间字段')
  }
  return issues
})
const { task, busy, error, uncertain, retry, start, cancel } = useAiTask()
const history = ref<AiTask[]>([]),
  base = ref(-1),
  stale = ref(false),
  compiledMessage = ref('')
const statusLabels: Record<string, string> = {
  QUEUED: '等待处理',
  RUNNING: '正在调用模型',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCELED: '已取消',
}
let contextVersion = 0,
  compileRequest = 0,
  historyRequest = 0,
  disposed = false
const result = computed(() => (task.value?.state === 'SUCCEEDED' ? task.value.result : undefined))
watch(result, (value) => {
  if (value && !stale.value && base.value === props.contextKey) {
    step.value = 3
    tab.value = 'conversation'
  }
})
const purpose = computed(() => (mode.value === 'definition' ? 'definition' : 'sql'))
const canApply = computed(
  () =>
    !!result.value?.sql &&
    !stale.value &&
    base.value === props.contextKey &&
    props.editable &&
    !busy.value &&
    !applying.value,
)
watch(
  [dataIssues, result, applied, mode],
  () =>
    emit('navigation', [
      true,
      !dataIssues.value.length || ['explain', 'definition'].includes(mode.value),
      !!result.value,
      applied.value,
    ]),
  { immediate: true },
)
watch(
  () => props.active,
  (active) => {
    if (active === false && busy.value) invalidate()
  },
)
const differences = computed(() => {
  const old = props.sql.split('\n'),
    next = (result.value?.sql || '').split('\n')
  return Array.from({ length: Math.min(1000, Math.max(old.length, next.length)) }, (_, index) => ({
    n: index + 1,
    old: old[index] || '',
    next: next[index] || '',
    changed: old[index] !== next[index],
  }))
})
const omittedDifferences = computed(() => {
  const old = props.sql.split('\n'),
    next = (result.value?.sql || '').split('\n')
  let changed = 0
  for (let index = 1000; index < Math.max(old.length, next.length); index++)
    if (old[index] !== next[index]) changed++
  return { lines: Math.max(0, Math.max(old.length, next.length) - 1000), changed }
})
const fullReview = ref(false)
function invalidate() {
  contextVersion++
  appliedTask.value = ''
  appliedVersion.value = -1
  compiledMessage.value = ''
  stale.value = true
  if (busy.value) void cancel()
}
watch(() => props.contextKey, invalidate, { flush: 'sync' })
watch(instruction, invalidate, { flush: 'sync' })
watch(
  [
    sourceIds,
    definitionsText,
    windowMinutes,
    eventTime,
    dedup,
    startPosition,
    modelId,
    mode,
    roles,
    pattern,
    timezone,
  ],
  invalidate,
  { deep: true, flush: 'sync' },
)
watch(purpose, () => {
  modelId.value = ''
  selectedModel.value = undefined
  allowExternal.value = false
})
watch(modelId, () => {
  allowExternal.value = false
})
function definitions() {
  const value: unknown = JSON.parse(definitionsText.value)
  validateDefinitions(value)
  return value
}
function addDefinition(sink: boolean) {
  try {
    const value = definitions()
    let name = sink ? 'sink_events' : 'source_events'
    let suffix = 2
    while (allTables.value.some((table) => table.tableName === name))
      name = (sink ? 'sink_events_' : 'source_events_') + suffix++
    value.push({
      tableName: name,
      connectorType: sink ? 'print' : 'kafka',
      columns: [{ name: 'id', type: 'STRING', primaryKey: false, nullable: true }],
      ...(sink
        ? {}
        : { brokers: '', topic: '', format: 'json', groupId: '', startupMode: 'latest-offset' }),
    })
    definitionsText.value = JSON.stringify(value, null, 2)
    tab.value = 'definitions'
  } catch (cause) {
    localError.value = (cause as Error).message
  }
}
async function compile() {
  const version = contextVersion,
    request = ++compileRequest
  const current = () => !disposed && version === contextVersion && request === compileRequest
  localError.value = ''
  compiledMessage.value = ''
  try {
    const value = await aiApi.compile(definitions())
    if (!current()) return
    compiledMessage.value = value.valid
      ? '数据定义编译通过，尚未应用到 SQL 或登记为共享数据源。'
      : value.pending.map((p) => p.table + '：' + p.message).join('\n')
  } catch (cause) {
    if (current()) localError.value = (cause as Error).message
  }
}
async function send() {
  localError.value = ''
  try {
    if (!modelId.value || !instruction.value.trim()) throw new Error('请选择模型并填写需求或问题')
    if (mode.value !== 'explain' && mode.value !== 'definition' && specIssues.value.length)
      throw new Error(specIssues.value.join('；'))
    const value = definitions()
    base.value = props.contextKey
    stale.value = false
    tab.value = 'conversation'
    await start({
      kind: purpose.value,
      mode: mode.value,
      modelId: modelId.value,
      allowExternal: allowExternal.value,
      instruction: instruction.value,
      sql: props.sql,
      sqlId: props.sqlId,
      sourceIds: sourceIds.value,
      definitions: value,
      flinkVersion: props.flinkVersion,
      spec:
        mode.value === 'generate' || mode.value === 'repair'
          ? {
              workflowVersion: 2,
              pattern: pattern.value,
              timezone: timezone.value,
              bindings: allTables.value.map((table) => ({
                tableName: table.tableName,
                role: tableRole(table),
              })),
              ...(pattern.value === 'window'
                ? {
                    eventTable: eventTime.value.split('.')[0],
                    eventTime: eventTime.value.split('.').slice(1).join('.'),
                    windowMinutes: windowMinutes.value,
                    outputMode: 'window-close',
                  }
                : {}),
              dedup: dedup.value,
              startPosition: startPosition.value,
            }
          : {},
    })
  } catch (cause) {
    localError.value = (cause as Error).message
  }
}
function applyDefinitions() {
  try {
    const current = definitions()
    const incoming = result.value?.definitions || []
    // 已有非空连接参数优先，新增表和字段可合入；建议始终需要显式应用。
    const merged = mergeDefinitions(current, incoming)
    definitionsText.value = JSON.stringify(merged, null, 2)
    tab.value = 'definitions'
    compiledMessage.value = '已合入建议并保留原有非空参数，请检查字段并编译。'
  } catch (cause) {
    localError.value = (cause as Error).message
  }
}
async function loadHistory() {
  const version = contextVersion,
    request = ++historyRequest,
    sqlId = props.sqlId
  const current = () =>
    !disposed && version === contextVersion && request === historyRequest && sqlId === props.sqlId
  try {
    const value = await aiApi.history(sqlId)
    if (current()) history.value = value
  } catch (cause) {
    if (current()) localError.value = (cause as Error).message
  }
}
function showHistory() {
  tab.value = 'history'
  void loadHistory()
}
watch(
  () => props.sqlId,
  () => {
    history.value = []
    void loadHistory()
  },
)
onMounted(loadHistory)
onBeforeUnmount(() => {
  disposed = true
  contextVersion++
})
</script>
<template>
  <aside class="sql-runtime ai-sql-panel">
    <div class="field-heading">
      <div>
        <h3>
          <Sparkles :size="17" />{{
            ['选择数据', '需求确认', '查看 SQL 建议', '检查与保存'][step - 1]
          }}
        </h3>
        <small class="muted">已选数据 · 语义确认 · SQL 建议</small>
      </div>
      <button class="text-button" aria-label="收起 AI 助手" @click="emit('close')">
        <X :size="16" />
      </button>
    </div>
    <AiModelSelect
      :key="purpose"
      v-model="modelId"
      :purpose="purpose"
      :disabled="busy"
      @selected="selectedModel = $event"
    />
    <label v-if="selectedModel?.external" class="ai-check"
      ><input v-model="allowExternal" type="checkbox" :disabled="busy" />允许将本次脱敏
      SQL、表定义和需求发送到此外部模型</label
    >
    <div class="wizard-function-title">
      <strong>SQL 需求向导</strong><span>选择数据 → 确认语义 → 审阅建议</span>
    </div>
    <div class="ai-modes" aria-label="辅助能力">
      <button
        v-for="item in [
          { id: 'generate', label: '需求生成' },
          { id: 'explain', label: '解释' },
          { id: 'repair', label: '修复' },
          { id: 'definition', label: '数据定义' },
        ]"
        :key="item.id"
        class="button"
        :class="{ active: mode === item.id }"
        :disabled="busy"
        @click="chooseMode(item.id)"
      >
        {{ item.label }}
      </button>
    </div>
    <nav class="ai-tabs" aria-label="AI 产物">
      <button
        class="text-button"
        :class="{ active: tab === 'conversation' }"
        @click="tab = 'conversation'"
      >
        需求向导</button
      ><button
        class="text-button"
        :class="{ active: tab === 'definitions' }"
        @click="tab = 'definitions'"
      >
        数据定义</button
      ><button class="text-button" @click="showHistory">生成记录</button>
    </nav>
    <div v-show="tab === 'conversation'" class="wizard-conversation">
      <section v-show="step === 1" class="wizard-data">
        <h4>这份作业从哪里读，写到哪里？</h4>
        <p class="muted">选择数据后明确每张表的用途。角色仅用于本次需求，不修改共享数据源。</p>
        <p v-if="!sources.length" class="empty-state">
          暂无登记数据源，可先添加当前作业的数据定义。
        </p>
        <div
          v-for="source in sources"
          :key="source.id"
          class="wizard-source"
          :class="{ selected: sourceIds.includes(source.id) }"
        >
          <label
            ><input
              v-model="sourceIds"
              :value="source.id"
              type="checkbox"
              :disabled="busy || !editable"
            /><span
              ><strong>{{ source.name }}</strong
              ><small>{{ source.connectorType }} · {{ source.tableName }}</small></span
            ></label
          >
          <label v-if="sourceIds.includes(source.id)" class="wizard-role"
            >用途<select
              :value="tableRole(source)"
              :disabled="busy || !editable"
              :aria-label="source.name + '的数据角色'"
              @change="
                roles[source.tableName] = ($event.target as HTMLSelectElement).value as TableRole
              "
            >
              <option value="">请选择</option>
              <option value="source" :disabled="source.connectorType === 'print'">输入表</option>
              <option
                value="lookup"
                :disabled="!['mysql', 'postgresql'].includes(source.connectorType)"
              >
                维表
              </option>
              <option value="sink" :disabled="source.connectorType === 'datagen'">输出表</option>
            </select></label
          >
        </div>
        <div
          v-for="(table, index) in localTables"
          :key="'local-' + index"
          class="wizard-local-table"
        >
          <span
            >{{ table.tableName }}<small>当前作业定义 · {{ table.connectorType }}</small></span
          ><select
            :value="tableRole(table)"
            :disabled="busy"
            :aria-label="table.tableName + '的数据角色'"
            @change="
              roles[table.tableName] = ($event.target as HTMLSelectElement).value as TableRole
            "
          >
            <option value="">请选择用途</option>
            <option value="source">输入表</option>
            <option value="lookup">维表</option>
            <option value="sink">输出表</option>
          </select>
        </div>
        <button class="text-button" @click="tab = 'definitions'">补充当前作业的数据定义</button>
        <p v-if="dataIssues.length" class="wizard-pending">{{ dataIssues.join('；') }}</p>
        <div class="wizard-actions">
          <button
            class="button primary"
            :disabled="busy || !!dataIssues.length"
            @click="nextStep(2)"
          >
            下一步：确认需求
          </button>
        </div>
      </section>
      <section v-show="step === 2" class="wizard-confirm">
        <div v-if="selectedTables.length" class="wizard-source-tags">
          <span v-for="source in selectedTables" :key="source.id"
            >{{ roleLabel[tableRole(source) as keyof typeof roleLabel] || '待选用途' }} ·
            {{ source.name }}</span
          >
        </div>
        <label v-if="mode === 'generate' || mode === 'repair'"
          >处理场景<select v-model="pattern" :disabled="busy" aria-label="处理场景">
            <option value="transform">清洗转换：筛选、映射与明细同步</option>
            <option value="lookup">维表打宽：关联 JDBC 维度</option>
            <option value="window">窗口统计：按事件时间定期汇总</option>
          </select></label
        >
        <label class="ai-prompt"
          >{{
            mode === 'explain'
              ? '想了解什么'
              : mode === 'repair'
                ? '错误与修复目标'
                : mode === 'definition'
                  ? '描述输入、输出及字段'
                  : '描述业务需求'
          }}<textarea
            v-model="instruction"
            :disabled="busy"
            maxlength="8000"
            rows="3"
            placeholder="描述统计口径、输入输出和时间要求，未知连接参数可后续补全。"
          />
        </label>
        <p class="muted">生成器只编写转换逻辑；表结构、连接参数和密钥引用由已选定义编译。</p>
        <section v-show="mode !== 'explain' && mode !== 'definition'" class="wizard-contract">
          <header>
            <strong>需求确认单</strong
            ><span class="badge" :class="{ running: !specIssues.length }">{{
              !specIssues.length ? '可确认生成' : '待补充'
            }}</span>
          </header>
          <div class="wizard-contract-row">
            <span>输入</span
            ><span>{{
              allTables
                .filter((t) => tableRole(t) === 'source')
                .map((t) => t.name || t.tableName)
                .join('、')
            }}</span
            ><small>已选数据</small>
          </div>
          <div class="wizard-contract-row">
            <span>输出表</span
            ><span>{{
              allTables
                .filter((t) => tableRole(t) === 'sink')
                .map((t) => t.name || t.tableName)
                .join('、')
            }}</span
            ><small>已选目标</small>
          </div>
          <div class="wizard-contract-row" v-if="pattern === 'window'">
            <label for="wizard-time">事件时间</label
            ><select
              id="wizard-time"
              v-model="eventTime"
              :disabled="busy"
              aria-label="事件时间字段"
            >
              <option value="">请选择已登记水位线字段</option>
              <option
                v-for="table in timeChoices"
                :key="table.tableName"
                :value="table.tableName + '.' + table.watermarkColumn"
              >
                {{ table.tableName }}.{{ table.watermarkColumn }}
              </option></select
            ><small>输入定义</small>
          </div>
          <div class="wizard-contract-row" v-if="pattern === 'window'">
            <label for="wizard-window">窗口</label
            ><input
              id="wizard-window"
              v-model.number="windowMinutes"
              :disabled="busy"
              type="number"
              min="1"
              max="1440"
              aria-label="窗口（分钟）"
            /><small>分钟</small>
          </div>
          <div class="wizard-contract-row" v-if="pattern === 'window'">
            <span>迟到</span
            ><span
              >{{
                timeChoices.find((t) => t.tableName + '.' + t.watermarkColumn === eventTime)
                  ?.watermarkDelaySeconds ?? '待选'
              }}
              秒；超出可能不计入窗口</span
            ><small>水位线</small>
          </div>
          <div class="wizard-contract-row">
            <span>输出方式</span
            ><span>{{ pattern === 'window' ? '窗口结束后输出' : '持续输出符合需求的明细' }}</span
            ><small>场景约束</small>
          </div>
          <div class="wizard-contract-row">
            <label for="wizard-zone">时区</label
            ><select id="wizard-zone" v-model="timezone" :disabled="busy">
              <option value="Asia/Shanghai">Asia/Shanghai</option>
              <option value="UTC">UTC</option></select
            ><small>运行配置</small>
          </div>
          <div class="wizard-contract-row">
            <label for="wizard-start">起始位置</label
            ><select
              id="wizard-start"
              v-model="startPosition"
              :disabled="busy"
              aria-label="消费起始位置"
            >
              <option value="latest-offset">从最新消息</option>
              <option value="earliest-offset">从最早消息</option>
              <option value="group-offsets">沿用消费组位点</option></select
            ><small>新启动生效</small>
          </div>
          <div class="wizard-contract-row">
            <label for="wizard-dedup">重复数据</label
            ><select id="wizard-dedup" v-model="dedup" :disabled="busy" aria-label="重复数据">
              <option value="">请确认处理方式</option>
              <option value="upstream">用户确认上游已去重</option>
              <option value="keep">每条消息均计入</option></select
            ><small>需确认</small>
          </div>
          <p v-if="specIssues.length" class="wizard-pending">{{ specIssues.join('；') }}</p>
        </section>
        <p v-if="compiledMessage" class="notice" role="status">{{ compiledMessage }}</p>
        <div class="wizard-actions">
          <button class="button" :disabled="busy" @click="nextStep(1)">上一步</button
          ><button
            class="button primary"
            :disabled="
              busy ||
              !modelId ||
              !editable ||
              !instruction.trim() ||
              (['generate', 'repair'].includes(mode) && !!specIssues.length) ||
              (selectedModel?.external && !allowExternal)
            "
            @click="send"
          >
            {{
              busy
                ? '正在分析…'
                : mode === 'definition'
                  ? '起草 / 继续补充定义'
                  : mode === 'generate'
                    ? '确认并生成 SQL'
                    : '发送'
            }}
          </button>
        </div>
      </section>
      <section v-show="step === 3" class="wizard-suggestion">
        <p v-if="!result" class="empty-state">确认需求后，SQL 建议和修改对比将在这里展示。</p>
        <p v-if="stale && result" class="notice">
          上下文或需求已改变，旧建议不可应用，请重新生成。
        </p>
        <AiResultReport v-if="result" :result="result" />
        <details v-if="result?.spec" class="wizard-confirmed-spec">
          <summary>查看本次生成使用的需求确认单</summary>
          <pre class="ai-json">{{ JSON.stringify(result.spec, null, 2) }}</pre>
        </details>
        <template v-if="result?.sql"
          ><details class="ai-suggestion" open>
            <summary>SQL 修改对比</summary>
            <template v-if="omittedDifferences.lines">
              <p class="notice" role="status">
                对比仅展示前 1000 行，后续 {{ omittedDifferences.lines }} 行中有
                {{ omittedDifferences.changed }} 行修改未展示，请完整审阅后再应用。
              </p>
              <button class="button" @click="fullReview = !fullReview">
                {{ fullReview ? '收起完整 SQL' : '审阅完整 SQL' }}
              </button>
              <div v-if="fullReview" class="ai-full-review">
                <h4>当前完整 SQL</h4>
                <CodeEditor :model-value="sql" language="sql" readonly label="当前完整 SQL" />
                <h4>建议完整 SQL</h4>
                <CodeEditor
                  :model-value="result.sql"
                  language="sql"
                  readonly
                  label="建议完整 SQL"
                />
              </div>
            </template>
            <div class="ai-diff" aria-label="AI SQL 修改对比">
              <template v-for="line in differences" :key="line.n">
                <pre v-if="line.changed && line.old" class="removed">− {{ line.old }}</pre>
                <pre :class="{ added: line.changed }"
                  >{{ line.changed ? '+' : ' ' }} {{ line.next }}</pre>
              </template>
            </div>
          </details>
          <button class="button primary" :disabled="!canApply" @click="applySql">
            应用到编辑器
          </button>
          <p class="muted">先检查差异，再显式应用；应用后重新检查和验证。</p></template
        >
        <template v-if="result?.definitions"
          ><details>
            <summary>查看数据定义建议</summary>
            <pre class="ai-json">{{ JSON.stringify(result.definitions, null, 2) }}</pre>
          </details>
          <button class="button" :disabled="stale || busy" @click="applyDefinitions">
            应用数据定义建议
          </button></template
        >
        <div class="wizard-actions">
          <button class="button" :disabled="busy" @click="nextStep(2)">返回需求确认</button
          ><button class="button" :disabled="!applied" @click="nextStep(4)">
            下一步：检查与保存
          </button>
        </div>
      </section>
      <section v-show="step === 4" class="wizard-save">
        <h4>检查当前 SQL 草稿</h4>
        <p class="muted">
          {{ checkSummary || '当前 SQL 尚未检查。应用 AI 建议后需要重新检查 SQL 和运行配置。' }}
        </p>
        <div class="wizard-actions">
          <button class="button" :disabled="!editable || busy" @click="emit('check')">
            检查当前 SQL</button
          ><button class="button" :disabled="!editable || busy || !canVerify" @click="emit('run')">
            运行验证</button
          ><button class="button primary" :disabled="!editable || busy" @click="emit('save')">
            保存当前草稿
          </button>
        </div>
        <p class="muted">
          {{ dirty ? '当前有未保存的编辑。' : '已保存后可返回 SQL 开发继续编辑。' }} SQL
          检查不等于运行验证。
        </p>
        <button class="text-button" @click="nextStep(3)">返回查看 SQL 建议</button>
      </section>
      <div v-if="step === 2 || step === 3" class="wizard-followup">
        <label
          >继续补充需求<textarea
            v-model="followup"
            :disabled="busy"
            rows="3"
            placeholder="例如：只统计支付成功的订单…"
          />
        </label>
        <div>
          <small>仅使用已选表与字段</small
          ><button
            class="button primary"
            :disabled="
              busy ||
              !editable ||
              !followup.trim() ||
              !modelId ||
              (selectedModel?.external && !allowExternal)
            "
            @click="refine"
          >
            发送补充
          </button>
        </div>
      </div>
    </div>
    <div v-show="tab === 'definitions'">
      <p class="muted">这些定义仅属于当前作业，可补充字段、主键、水位线和 Connector 参数。</p>
      <div class="toolbar-actions">
        <button class="button" :disabled="busy" @click="addDefinition(false)">添加输入表</button
        ><button class="button" :disabled="busy" @click="addDefinition(true)">添加输出表</button>
      </div>
      <AiDataDefinitionEditor v-model="definitionsText" :disabled="busy" />
      <details>
        <summary>高级 JSON 编辑</summary>
        <label
          >字段与 Connector 定义（JSON）<textarea
            v-model="definitionsText"
            :disabled="busy"
            rows="18"
            class="ai-json-input"
            spellcheck="false"
          />
        </label>
      </details>
      <button class="button" :disabled="busy" @click="compile">编译定义并检查待补项</button>
      <p v-if="compiledMessage" class="notice ai-prewrap" role="status">{{ compiledMessage }}</p>
      <button class="button primary" :disabled="busy" @click="nextStep(2)">继续确认需求</button>
    </div>
    <div v-show="tab === 'history'">
      <p v-if="!history.length" class="empty-state">暂无生成记录</p>
      <details v-for="item in history" :key="item.id" class="ai-history">
        <summary>{{ new Date(item.createdAt).toLocaleString() }} · {{ item.state }}</summary>
        <p>{{ item.model }} · {{ item.message }}</p>
        <AiResultReport v-if="item.result" :result="item.result" /><CodeEditor
          v-if="item.result?.sql"
          :model-value="item.result.sql"
          language="sql"
          readonly
          label="历史 AI SQL 建议"
        />
      </details>
    </div>
    <div v-if="busy || task || uncertain" class="wizard-task-status">
      <p v-if="task" class="muted" role="status">
        {{ statusLabels[task.state] }} · {{ task.model }}
      </p>
      <button v-if="uncertain" class="button" @click="retry">核对原任务并重试</button
      ><button v-if="busy" class="button" @click="cancel">取消任务</button>
    </div>
    <p v-if="localError || error" class="notice error ai-prewrap" role="alert">
      {{ localError || error }}
    </p>
    <p class="ai-boundary">
      每一步都可返回修改 · 保存后继续在 SQL 开发编辑。建议不会自动保存、发布或执行。
    </p>
  </aside>
</template>
<style scoped>
.ai-sql-panel {
  padding: 16px;
}
.field-heading h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--color-primary);
}
.ai-intro,
.muted {
  font-size: 12px;
}
.ai-modes {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 5px;
  margin: 12px 0;
}
.ai-modes .button {
  min-width: 0;
  padding: 6px 3px;
  font-size: 12px;
}
.ai-modes .active {
  color: var(--color-primary);
  background: #edf3ff;
  border-color: #c9dcff;
}
.ai-tabs {
  display: flex;
  gap: 12px;
  border-bottom: 1px solid var(--line);
  padding: 4px 0 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.ai-tabs .active {
  color: var(--color-primary);
}
.ai-spec {
  border: 1px solid var(--line);
  border-radius: 7px;
  padding: 12px;
  margin-bottom: 14px;
}
.ai-spec summary {
  font-size: 12px;
  margin-bottom: 10px;
  cursor: pointer;
}
.ai-check {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  font-weight: 400;
}
.ai-check input {
  width: auto;
  flex: none;
}
.ai-check small {
  color: var(--color-muted);
}
.ai-spec-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.ai-prompt textarea,
.ai-json-input {
  width: 100%;
  resize: vertical;
}
.ai-json-input {
  font:
    12px/1.7 Consolas,
    monospace;
  min-height: 260px;
}
.ai-diff {
  margin: 10px 0;
  max-height: 360px;
  overflow: auto;
  background: #fbfcfe;
  border: 1px solid var(--line);
}
.ai-diff pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  margin: 0;
  padding: 3px 7px;
  font:
    12px/1.6 Consolas,
    monospace;
}
.added {
  background: #edf9f1;
  color: #246449;
}
.removed {
  background: #fff1f0;
  color: #a64242;
}
.ai-json {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 12px;
  max-height: 350px;
  overflow: auto;
}
.ai-history {
  padding: 12px 0;
  border-bottom: 1px solid var(--line);
  font-size: 12px;
}
.ai-boundary {
  border-top: 1px solid var(--line);
  padding-top: 12px;
  margin-top: 16px;
  font-size: 12px;
  color: var(--color-muted);
}
.ai-prewrap {
  white-space: pre-wrap;
}

.wizard-data,
.wizard-confirm,
.wizard-suggestion,
.wizard-save {
  padding-top: 14px;
}
.wizard-source {
  display: flex;
  align-items: center;
  gap: 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 14px;
  margin: 10px 0;
  flex-wrap: wrap;
}
.wizard-source.selected {
  background: #f7faff;
  border-color: #bfd3f7;
}
.wizard-source > label:first-child {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 130px;
  margin: 0;
}
.wizard-role {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 1 150px;
  margin: 0 !important;
  white-space: nowrap;
  font-size: 12px;
}
.wizard-role select {
  min-width: 0;
}
.wizard-local-table {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid var(--line);
  padding: 12px 0;
}
.wizard-local-table small {
  display: block;
  color: var(--color-muted);
  font-size: 11px;
}
.wizard-local-table select {
  width: 120px;
}
.wizard-function-title {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 6px;
  margin-top: 16px;
}
.wizard-function-title strong {
  font-size: 13px;
  font-weight: 600;
}
.wizard-function-title span {
  font-size: 11px;
  color: var(--color-muted);
}
.wizard-data h4 {
  margin: 0 0 8px;
}
.wizard-source input {
  width: auto;
}
.wizard-source strong,
.wizard-source small {
  display: block;
}
.wizard-source small {
  margin-top: 5px;
  color: var(--color-muted);
}
.wizard-actions {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
  margin: 16px 0;
}
.wizard-source-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 12px;
}
.wizard-source-tags span {
  border: 1px solid #dbe5f4;
  background: #f7faff;
  color: #56739c;
  border-radius: 4px;
  padding: 4px 7px;
  font-size: 11px;
}
.wizard-contract {
  border: 1px solid var(--line);
  border-radius: 8px;
  margin-top: 18px;
  overflow: hidden;
}
.wizard-contract header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px;
  border-bottom: 1px solid var(--line);
}
.wizard-contract-row {
  display: grid;
  grid-template-columns: 70px minmax(0, 1fr) 70px;
  gap: 10px;
  align-items: center;
  padding: 9px 14px;
  font-size: 12px;
}
.wizard-contract-row label {
  margin: 0;
  color: var(--color-muted);
}
.wizard-contract-row small {
  color: #7b92b4;
  text-align: right;
  font-size: 11px;
}
.wizard-contract-row input,
.wizard-contract-row select {
  min-width: 0;
  width: 100%;
  margin: 0;
}
.wizard-pending {
  padding: 11px 14px;
  background: #fff8e9;
  color: #9a6717;
  font-size: 12px;
  margin: 5px 0 0;
}
.wizard-followup {
  margin-top: 20px;
  border: 1px solid var(--line);
  padding: 14px;
  border-radius: 8px;
}
.wizard-followup textarea {
  width: 100%;
  resize: vertical;
}
.wizard-followup > div {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.wizard-followup small {
  color: #7790b2;
  font-size: 11px;
}
.wizard-task-status {
  border-top: 1px solid var(--line);
  margin-top: 16px;
  padding-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.wizard-task-status p {
  flex: 1;
  min-width: 140px;
}
.ai-sql-panel .ai-modes .button {
  border: 0;
  background: transparent;
  color: #7690b1;
}
.ai-sql-panel .ai-modes .button.active {
  background: #edf4ff;
  color: #2866d8;
}
.ai-sql-panel .ai-intro {
  margin: 8px 0 14px;
}
@media (max-width: 600px) {
  .wizard-contract-row {
    grid-template-columns: 60px minmax(0, 1fr);
    padding: 9px 10px;
  }
  .wizard-contract-row small {
    display: none;
  }
  .wizard-source-tags span {
    overflow-wrap: anywhere;
  }
}
</style>
