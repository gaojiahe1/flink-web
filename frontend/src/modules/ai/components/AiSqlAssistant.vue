<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Sparkles, X } from '@lucide/vue'
import AiModelSelect from './AiModelSelect.vue'
import AiResultReport from './AiResultReport.vue'
import AiDataDefinitionEditor from './AiDataDefinitionEditor.vue'
import CodeEditor from '../../../shared/components/editor/CodeEditor.vue'
import { aiApi, type AiModel, type AiResult, type AiTask } from '../api/ai'
import { useAiTask } from '../composables/useAiTask'
import { mergeDefinitions } from '../utils/definitions'
const props = defineProps<{
  sql: string
  sqlId: string
  flinkVersion: string
  contextKey: number
  editable: boolean
  sources: { id: string; name: string; connectorType: string }[]
}>()
const emit = defineEmits<{ close: []; apply: [AiResult, number] }>()
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
const { task, busy, error, start, cancel } = useAiTask()
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
const result = computed(() => (task.value?.state === 'SUCCEEDED' ? task.value.result : undefined))
const purpose = computed(() => (mode.value === 'definition' ? 'definition' : 'sql'))
const canApply = computed(
  () =>
    !!result.value?.sql &&
    !stale.value &&
    base.value === props.contextKey &&
    props.editable &&
    !busy.value,
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
function invalidate() {
  stale.value = true
  if (busy.value) void cancel()
}
watch(() => props.contextKey, invalidate, { flush: 'sync' })
watch(instruction, invalidate, { flush: 'sync' })
watch(
  [sourceIds, definitionsText, windowMinutes, eventTime, dedup, startPosition, modelId],
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
  if (
    !Array.isArray(value) ||
    value.length > 8 ||
    value.some((v) => !v || typeof v !== 'object' || Array.isArray(v))
  )
    throw new Error('数据定义应为最多八项的 JSON 对象数组')
  return value as Record<string, unknown>[]
}
function addDefinition(sink: boolean) {
  try {
    const value = definitions()
    value.push({
      tableName: sink ? 'sink_events' : 'source_events',
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
  localError.value = ''
  compiledMessage.value = ''
  try {
    const value = await aiApi.compile(definitions())
    compiledMessage.value = value.valid
      ? '数据定义编译通过，尚未应用到 SQL 或登记为共享数据源。'
      : value.pending.map((p) => p.table + '：' + p.message).join('\n')
  } catch (cause) {
    localError.value = (cause as Error).message
  }
}
async function send() {
  localError.value = ''
  try {
    if (!modelId.value || !instruction.value.trim()) throw new Error('请选择模型并填写需求或问题')
    if (mode.value === 'generate' && !dedup.value) throw new Error('请先确认重复数据处理方式')
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
      spec: {
        eventTime: eventTime.value,
        windowMinutes: windowMinutes.value,
        dedup: dedup.value,
        startPosition: startPosition.value,
      },
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
  try {
    history.value = await aiApi.history(props.sqlId)
  } catch (cause) {
    localError.value = (cause as Error).message
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
</script>
<template>
  <aside class="sql-runtime ai-sql-panel">
    <div class="field-heading">
      <h3><Sparkles :size="17" />AI 助手</h3>
      <button class="text-button" aria-label="收起 AI 助手" @click="emit('close')">
        <X :size="16" />
      </button>
    </div>
    <p class="muted ai-intro">当前 SQL · 已选数据 · 建议经确认后应用</p>
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
    <div class="ai-modes" aria-label="助手任务">
      <button
        v-for="item in [
          { id: 'generate', label: '生成 SQL' },
          { id: 'explain', label: '解释' },
          { id: 'repair', label: '修复' },
          { id: 'definition', label: '数据定义' },
        ]"
        :key="item.id"
        class="button"
        :class="{ active: mode === item.id }"
        :disabled="busy"
        @click="mode = item.id"
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
        对话与建议</button
      ><button
        class="text-button"
        :class="{ active: tab === 'definitions' }"
        @click="tab = 'definitions'"
      >
        数据定义</button
      ><button class="text-button" @click="showHistory">生成记录</button>
    </nav>
    <div v-show="tab === 'conversation'">
      <details :open="mode === 'generate' && !result" class="ai-spec">
        <summary>需求确认单与数据选择</summary>
        <p v-if="!sources.length" class="muted">
          暂无登记数据源，可在“数据定义”中补充当前作业的表。
        </p>
        <label v-for="source in sources" :key="source.id" class="ai-check"
          ><input v-model="sourceIds" :value="source.id" type="checkbox" :disabled="busy" />{{
            source.name
          }}
          <small>{{ source.connectorType }}</small></label
        >
        <div class="ai-spec-grid">
          <label
            >事件时间字段<input
              v-model="eventTime"
              :disabled="busy"
              placeholder="例如 pay_time" /></label
          ><label
            >窗口（分钟）<input
              v-model.number="windowMinutes"
              :disabled="busy"
              type="number"
              min="1"
              max="1440"
          /></label>
        </div>
        <label
          >重复数据<select v-model="dedup" :disabled="busy" aria-label="重复数据">
            <option value="">请确认处理方式</option>
            <option value="upstream">用户确认上游已去重</option>
            <option value="keep">每条消息均计入</option>
          </select></label
        >
        <label
          >消费起始位置<select v-model="startPosition" :disabled="busy" aria-label="消费起始位置">
            <option value="latest-offset">从最新消息</option>
            <option value="earliest-offset">从最早消息</option>
            <option value="group-offsets">沿用消费组位点</option>
          </select></label
        >
        <p class="muted">
          事件时间、水位线、主键和连接器仍以所选数据定义为准；起始位置仅对新启动生效。
        </p>
      </details>
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
          rows="4"
          :placeholder="
            mode === 'definition'
              ? '例如：Kafka 支付订单包含订单号、支付金额和支付时间。未知地址可先留空，之后手动补充。'
              : '例如：按类目每 5 分钟汇总支付金额，窗口结束后写入目标表。'
          "
        />
      </label>
      <div class="toolbar-actions">
        <button
          class="button primary"
          :disabled="busy || !modelId || !editable || (selectedModel?.external && !allowExternal)"
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
          }}</button
        ><button v-if="busy" class="button" @click="cancel">取消任务</button>
      </div>
      <p v-if="task" class="muted" role="status">
        {{ statusLabels[task.state] }} · {{ task.model }}
      </p>
      <p v-if="stale && result" class="notice">上下文或需求已改变，旧建议不可应用，请重新生成。</p>
      <AiResultReport v-if="result" :result="result" />
      <template v-if="result?.sql">
        <details class="ai-suggestion" open>
          <summary>SQL 修改对比</summary>
          <div class="ai-diff" aria-label="AI SQL 修改对比">
            <template v-for="line in differences" :key="line.n">
              <pre v-if="line.changed && line.old" class="removed">− {{ line.old }}</pre>
              <pre :class="{ added: line.changed }"
                >{{ line.changed ? '+' : ' ' }} {{ line.next }}</pre>
            </template>
          </div>
        </details>
        <button class="button primary" :disabled="!canApply" @click="emit('apply', result, base)">
          应用到编辑器
        </button>
        <p class="muted">
          应用会替换当前 SQL，并合入所需密钥引用；请先检查对比，随后重新检查和验证。
        </p>
      </template>
      <template v-if="result?.definitions"
        ><details>
          <summary>查看数据定义建议</summary>
          <pre class="ai-json">{{ JSON.stringify(result.definitions, null, 2) }}</pre>
        </details>
        <button class="button" :disabled="stale || busy" @click="applyDefinitions">
          应用数据定义建议
        </button></template
      >
    </div>
    <div v-show="tab === 'definitions'">
      <p class="muted">
        这些定义仅属于当前作业。支持字段名、类型、主键、水位线和 Connector
        参数编辑；共享数据源仍由管理员维护。
      </p>
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
    <p v-if="error || localError" class="notice error" role="alert">{{ error || localError }}</p>
    <p class="ai-boundary">AI 建议不会自动保存、运行或部署。修改内容后需重新检查 SQL。</p>
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
</style>
