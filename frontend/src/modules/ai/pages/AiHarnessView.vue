<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  PanelLeftClose,
  PanelLeftOpen,
  Plus,
  Trash2,
  RefreshCw,
  Send,
  Square,
  MessageSquare,
} from '@lucide/vue'
import { aiApi, type AiModel } from '../api/ai'
import { useRoute } from 'vue-router'
import {
  harnessApi,
  activeHarnessTurn,
  type HarnessSession,
  type HarnessTurn,
} from '../api/harness'
import AiApiReview from '../components/AiApiReview.vue'
import AiHarnessTurn from '../components/AiHarnessTurn.vue'
import { currentUser } from '../../permission/stores/auth'
import { request } from '../../../shared/api/http'
import { requestId } from '../../../shared/utils/requestId'
import { confirmDialog } from '../../../shared/utils/confirmDialog'
import '../styles/harness.css'

const route = useRoute()
const ready = ref(false)
const requestedJobId = computed(() => (typeof route.query.job === 'string' ? route.query.job : ''))
let jobEntryRequest: { jobId: string; requestId: string } | undefined
const sessions = ref<HarnessSession[]>([]),
  session = ref<HarnessSession>()
const models = ref<AiModel[]>([]),
  modelId = ref(''),
  content = ref('')
const error = ref(''),
  connection = ref(''),
  busy = ref(false),
  collapsed = ref(false)
let composerResponse: { turnId: string; text: string; input: Record<string, unknown> } | undefined
const templateName = ref('')
const taskTemplates = [
  ['develop_sql', 'SQL 开发'],
  ['diagnose_job', '作业诊断'],
  ['diagnose_checkpoint_failure', 'Checkpoint 排查'],
  ['diagnose_restart', '重启排查'],
  ['review_restore_source', '恢复来源核对'],
  ['verify_deployment', '部署核验'],
  ['review_resource_change', '资源调整审阅'],
  ['compare_sql_versions', 'SQL 版本比较'],
  ['diagnose_backpressure', '反压排查'],
]
const transcript = ref<HTMLElement>()
const creating = ref(false),
  kind = ref<HarnessSession['kind']>('diagnosis'),
  target = ref('')
const jobs = ref<{ id: string; name: string }[]>([]),
  drafts = ref<{ id: string; name: string }[]>([])
const editable = computed(
  () =>
    currentUser.value?.roles.some((role) => ['ROLE_ADMIN', 'ROLE_DEVELOPER'].includes(role)) ||
    false,
)
const choices = computed(() =>
  kind.value === 'diagnosis'
    ? jobs.value
    : kind.value === 'sql'
      ? drafts.value
      : sessions.value
          .filter((item) => item.kind !== 'evaluation')
          .map((item) => ({ id: item.id, name: item.title })),
)
const availableModels = computed(() =>
  models.value.filter(
    (model) =>
      model.enabled && model.purposes.includes(session.value?.kind === 'sql' ? 'sql' : 'diagnosis'),
  ),
)
const selectedModel = computed(() =>
  availableModels.value.find((model) => model.id === modelId.value),
)
const turns = computed(() => session.value?.turns || [])
const active = computed(() => turns.value.find(activeHarnessTurn))
let source: EventSource | undefined,
  selection = 0,
  disposed = false
let pending: { sessionId: string; input: Record<string, unknown> } | undefined
let createRequest: { identity: string; id: string } | undefined

function stopStream() {
  source?.close()
  source = undefined
  connection.value = ''
}
function setTurn(turn: HarnessTurn) {
  if (session.value?.id !== turn.sessionId) return
  const list = (session.value.turns ||= [])
  const index = list.findIndex((item) => item.id === turn.id)
  if (index < 0) list.push(turn)
  else list[index] = turn
}
async function scrollIfNear(force = false) {
  const el = transcript.value
  const near = force || (!!el && el.scrollHeight - el.scrollTop - el.clientHeight < 140)
  if (near) {
    await nextTick()
    transcript.value?.scrollTo({ top: transcript.value.scrollHeight })
  }
}
function connect(turn: HarnessTurn) {
  stopStream()
  if (!activeHarnessTurn(turn)) return
  const id = turn.sessionId,
    token = selection
  const stream = new EventSource(harnessApi.eventsUrl(id, turn.id), { withCredentials: true })
  source = stream
  stream.addEventListener('snapshot', (event) => {
    if (disposed || source !== stream || token !== selection || session.value?.id !== id) return
    try {
      const snapshot = JSON.parse((event as MessageEvent).data) as HarnessTurn
      void scrollIfNear()
      setTurn(snapshot)
      connection.value = ''
      if (!activeHarnessTurn(snapshot)) {
        stopStream()
        void reloadCurrent(id, token)
      }
    } catch {
      stopStream()
      error.value = '对话数据未能解析，请重新加载会话。'
    }
  })
  stream.onerror = () => {
    if (source === stream)
      connection.value = '连接暂时中断，正在按原任务恢复。可手动重新加载；不会重新调用模型。'
  }
}
async function refreshList() {
  sessions.value = await harnessApi.list()
}
async function reloadCurrent(id: string, token: number) {
  try {
    const value = await harnessApi.get(id)
    if (disposed || token !== selection) return
    session.value = value
    await refreshList()
  } catch (e) {
    if (token === selection) error.value = (e as Error).message
  }
}
async function select(id: string) {
  if (busy.value) return
  const token = ++selection
  stopStream()
  error.value = ''
  pending = undefined
  busy.value = true
  try {
    const value = await harnessApi.get(id)
    if (disposed || token !== selection) return
    session.value = value
    if (!availableModels.value.some((model) => model.id === modelId.value))
      modelId.value = availableModels.value[0]?.id || ''
    const running = value.turns?.find(activeHarnessTurn)
    if (running) connect(running)
    await scrollIfNear(true)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function openCreate() {
  error.value = ''
  target.value = ''
  creating.value = true
  try {
    const [jobList, sqlList] = await Promise.all([
      request<{ id: string; name: string }[]>('/api/jobs'),
      editable.value
        ? request<{ id: string; name: string }[]>('/api/sql/jobs')
        : Promise.resolve([]),
    ])
    jobs.value = jobList
    drafts.value = sqlList
  } catch (e) {
    error.value = (e as Error).message
  }
}
async function create() {
  if (
    (!(kind.value === 'sql' && !target.value) &&
      !choices.value.some((item) => item.id === target.value)) ||
    busy.value
  )
    return
  busy.value = true
  error.value = ''
  try {
    const identity = kind.value + ':' + target.value
    if (createRequest?.identity !== identity) createRequest = { identity, id: requestId() }
    const value = await harnessApi.create({
      requestId: createRequest.id,
      kind: kind.value,
      jobId: kind.value === 'diagnosis' ? target.value : '',
      sqlId: kind.value === 'sql' ? target.value : '',
      sourceSessionId: kind.value === 'evaluation' ? target.value : '',
    })
    await refreshList()
    createRequest = undefined
    creating.value = false
    busy.value = false
    await select(value.id)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function remove(item: HarnessSession) {
  if (
    busy.value ||
    !(await confirmDialog(
      '将删除此私有会话、执行记录、候选与关联评测会话。共享作业和 SQL 草稿保留。正在分析的任务会取消；未核对的运行提交需要先核对。',
      '删除会话',
    ))
  )
    return
  busy.value = true
  error.value = ''
  try {
    const latest = await harnessApi.get(item.id)
    await harnessApi.remove(item.id, latest.revision)
    if (session.value?.id === item.id || session.value?.sourceSessionId === item.id) {
      ++selection
      stopStream()
      session.value = undefined
      pending = undefined
    }
    await refreshList()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function send(requirementTurn?: HarnessTurn) {
  if (!session.value || busy.value || active.value || !selectedModel.value) return
  const id = session.value.id,
    token = selection
  if (
    pending &&
    (pending.sessionId !== id ||
      !(await confirmDialog(
        '上次发送结果尚未确认。将使用原请求号核对原任务，不重复发起新的模型任务。',
        '核对原任务',
      )))
  )
    return
  if (!pending) {
    const text = requirementTurn
      ? '我已核对已知需求范围，请生成可审阅的 SQL 候选，保留未确认事项。'
      : content.value.trim()
    if (!text) return
    pending = {
      sessionId: id,
      input: {
        requestId: requestId(),
        revision: session.value.revision,
        content: text,
        modelId: modelId.value,
        allowExternal: Boolean(selectedModel.value.external),
        templateName: templateName.value,
        ...(requirementTurn ? { requirementsTurnId: requirementTurn.id, confirmed: true } : {}),
      },
    }
  }
  busy.value = true
  error.value = ''
  try {
    const turn = await harnessApi.start(id, pending.input)
    pending = undefined
    content.value = ''
    setTurn(turn)
    connect(turn)
    await scrollIfNear(true)
    if (!activeHarnessTurn(turn)) await reloadCurrent(id, token)
  } catch (e) {
    error.value = (e as Error).message + '；发送内容已保留，再次发送会先核对原任务。'
  } finally {
    busy.value = false
  }
}
async function cancel() {
  if (!session.value || !active.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    setTurn(await harnessApi.cancel(session.value.id, active.value.id))
    stopStream()
    await reloadCurrent(session.value.id, selection)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function respond(turn: HarnessTurn, input: Record<string, unknown>): Promise<boolean> {
  if (!session.value || busy.value || active.value) return false
  const id = session.value.id,
    token = selection
  busy.value = true
  error.value = ''
  try {
    const next = await harnessApi.respond(id, turn.id, input)
    if (session.value?.id !== id || token !== selection) return false
    await reloadCurrent(id, token)
    if (session.value?.id === id && token === selection) {
      setTurn(next)
      if (activeHarnessTurn(next)) connect(next)
    }
    return true
  } catch (e) {
    error.value = (e as Error).message
    return false
  } finally {
    busy.value = false
  }
}
async function apply(turn: HarnessTurn, input: Record<string, unknown>) {
  if (!session.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    setTurn(await harnessApi.apply(session.value.id, turn.id, input))
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    await reloadCurrent(session.value.id, selection)
    busy.value = false
  }
}
async function observe(turn: HarnessTurn) {
  if (!session.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    setTurn(await harnessApi.observe(session.value.id, turn.id))
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
function confirmRequirements(turn: HarnessTurn) {
  void send(turn)
}
function submit() {
  const waiting = turns.value.find(
    (turn) => turn.state === 'WAITING_USER' && turn.interaction?.state === 'PENDING',
  )
  if (waiting && waiting.interaction?.kind !== 'preferences' && content.value.trim()) {
    const text = content.value.trim()
    if (
      !composerResponse ||
      composerResponse.turnId !== waiting.id ||
      composerResponse.text !== text
    )
      composerResponse = {
        turnId: waiting.id,
        text,
        input: {
          requestId: requestId(),
          interactionId: waiting.interaction!.id,
          decision: 'submit',
          answers: { note: text },
          grantLogs: false,
        },
      }
    void respond(waiting, composerResponse.input).then((ok) => {
      if (ok && content.value.trim() === text) {
        content.value = ''
        composerResponse = undefined
      }
    })
    return
  }
  void send()
}
function composerKey(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    submit()
  }
}
async function openJobSession(jobId: string) {
  const token = ++selection
  stopStream()
  session.value = undefined
  pending = undefined
  error.value = ''
  busy.value = true
  try {
    const list = await harnessApi.list()
    if (disposed || token !== selection) return
    sessions.value = list
    let current = list.find((item) => item.kind === 'diagnosis' && item.jobId === jobId)
    if (!current) {
      if (jobEntryRequest?.jobId !== jobId) jobEntryRequest = { jobId, requestId: requestId() }
      current = await harnessApi.create({
        requestId: jobEntryRequest.requestId,
        kind: 'diagnosis',
        jobId,
      })
    }
    if (disposed || token !== selection) return
    if (!sessions.value.some((item) => item.id === current.id))
      sessions.value = [current, ...sessions.value]
    jobEntryRequest = undefined
    busy.value = false
    await select(current.id)
  } catch (e) {
    if (token === selection) error.value = '打开作业 AI 会话失败：' + (e as Error).message
  } finally {
    if (token === selection) busy.value = false
  }
}
watch(requestedJobId, (jobId) => {
  if (ready.value && jobId) void openJobSession(jobId)
})
onMounted(async () => {
  try {
    const [list, configured] = await Promise.all([harnessApi.list(), aiApi.models()])
    sessions.value = list
    models.value = configured
    ready.value = true
    if (requestedJobId.value) await openJobSession(requestedJobId.value)
    else if (list[0]) await select(list[0].id)
  } catch (e) {
    error.value = (e as Error).message
  }
})
onBeforeUnmount(() => {
  disposed = true
  ++selection
  stopStream()
})
</script>

<template>
  <AiApiReview :id="String(route.query.operation || '')" />
  <div class="harness-workspace" :class="{ 'history-collapsed': collapsed }">
    <aside v-if="!collapsed" class="harness-history" aria-label="私有会话">
      <div class="harness-history-heading">
        <strong>我的会话</strong
        ><button
          class="harness-icon"
          title="收起会话列表"
          aria-label="收起会话列表"
          @click="collapsed = true"
        >
          <PanelLeftClose :size="16" />
        </button>
      </div>
      <button class="harness-new" :disabled="busy" @click="openCreate">
        <Plus :size="15" />新建会话
      </button>
      <div class="harness-session-list">
        <div
          v-for="item in sessions"
          :key="item.id"
          class="harness-session"
          :class="{ selected: session?.id === item.id }"
        >
          <button class="harness-session-select" :disabled="busy" @click="select(item.id)">
            <span>{{ item.title }}</span
            ><small>{{ new Date(item.updatedAt).toLocaleDateString() }}</small>
          </button>
          <button
            class="harness-icon harness-delete"
            :disabled="busy"
            :aria-label="'删除会话 ' + item.title"
            title="删除会话"
            @click="remove(item)"
          >
            <Trash2 :size="14" />
          </button>
        </div>
      </div>
      <small class="harness-retention"
        >仅自己可见 · 最多 20 个会话<br />每个会话保留最近 10 轮</small
      >
    </aside>
    <section class="harness-chat" aria-label="Flink AI 对话">
      <header class="harness-chat-header">
        <button
          v-if="collapsed"
          class="harness-icon"
          aria-label="展开会话列表"
          title="展开会话列表"
          @click="collapsed = false"
        >
          <PanelLeftOpen :size="16" />
        </button>
        <div>
          <strong>{{ session?.title || 'Flink AI Harness' }}</strong
          ><small>{{
            session ? '计划先行 · 过程按需展开 · 修改前确认' : '诊断、SQL 开发与执行轨迹评测'
          }}</small>
        </div>
        <button
          v-if="session"
          class="harness-icon"
          :disabled="busy"
          aria-label="重新加载会话"
          title="重新加载会话"
          @click="select(session.id)"
        >
          <RefreshCw :size="15" />
        </button>
      </header>
      <p v-if="error" class="harness-error" role="alert">
        {{ error }}<button class="harness-icon" aria-label="关闭提示" @click="error = ''">×</button>
      </p>
      <button
        v-if="requestedJobId && !session"
        :disabled="busy"
        @click="openJobSession(requestedJobId)"
      >
        重试打开作业会话
      </button>
      <p v-if="connection" class="harness-connection" role="status">{{ connection }}</p>
      <div ref="transcript" class="harness-transcript">
        <div v-if="!session || !turns.length" class="harness-welcome">
          <MessageSquare :size="28" />
          <h2>{{ session ? '从一个具体问题开始' : '让 Flink 工作在对话中推进' }}</h2>
          <p>
            先展示执行计划，按需查看调用过程。结论与关键证据留在对话中，参数修改和重启在审阅后实施。
          </p>
          <button v-if="!session" class="primary" @click="openCreate">新建会话</button>
        </div>
        <div class="harness-message-column">
          <AiHarnessTurn
            v-for="(turn, index) in turns"
            :key="turn.id"
            :turn="turn"
            :editable="editable"
            :working="busy || !!active"
            :latest="index === turns.length - 1"
            @respond="respond"
            @apply="apply"
            @observe="observe"
            @requirements="confirmRequirements"
          />
        </div>
      </div>
      <form v-if="session" class="harness-composer" @submit.prevent="submit">
        <textarea
          v-model="content"
          rows="3"
          maxlength="8000"
          :readonly="!!pending"
          :disabled="busy"
          aria-label="输入需求"
          placeholder="描述问题、开发需求或希望检查的执行轨迹…"
          @keydown="composerKey"
        />
        <div class="harness-composer-tools">
          <select v-model="modelId" :disabled="busy || !!active || !!pending" aria-label="模型">
            <option value="" disabled>请选择模型</option>
            <option v-for="model in availableModels" :key="model.id" :value="model.id">
              {{ model.name }}
            </option>
          </select>
          <label class="harness-template"
            >任务模板
            <select v-model="templateName" :disabled="busy || !!active || !!pending">
              <option value="">自动选择</option>
              <option v-for="item in taskTemplates" :key="item[0]" :value="item[0]">
                {{ item[1] }}
              </option>
            </select>
          </label>
          <span class="harness-composer-hint">Enter 发送 · Shift + Enter 换行</span
          ><button
            v-if="active"
            type="button"
            :disabled="busy || active.state === 'APPLYING'"
            @click="cancel"
          >
            <Square :size="13" />停止本轮</button
          ><button v-else class="primary" :disabled="busy || !content.trim() || !selectedModel">
            <Send :size="14" />{{ pending ? '核对原发送' : '发送' }}
          </button>
        </div>
        <small v-if="selectedModel?.external" class="harness-consent"
          >当前使用外部模型；发送将使用本会话、SQL 和选取的脱敏证据，可随时停止。</small
        >
        <small v-if="!availableModels.length"
          >暂无适用模型，请由管理员在系统设置中配置支持工具调用的模型。</small
        >
      </form>
    </section>
    <div v-if="creating" class="harness-modal-backdrop" @click.self="creating = false">
      <section
        class="harness-create"
        role="dialog"
        aria-modal="true"
        aria-labelledby="harness-create-title"
        @keydown.esc="creating = false"
      >
        <h2 id="harness-create-title">新建私有会话</h2>
        <form @submit.prevent="create">
          <label
            >处理场景<select v-model="kind" @change="target = ''">
              <option value="diagnosis">作业诊断与受控修复</option>
              <option v-if="editable" value="sql">SQL 开发</option>
              <option value="evaluation">真实执行轨迹评测</option>
            </select></label
          ><label
            >{{ kind === 'diagnosis' ? '目标作业' : kind === 'sql' ? 'SQL 草稿' : '来源会话'
            }}<select v-model="target" :required="kind !== 'sql'">
              <option value="" :disabled="kind !== 'sql'">
                {{ kind === 'sql' ? '从需求开始，批准后创建草稿' : '请选择' }}
              </option>
              <option v-for="item in choices" :key="item.id" :value="item.id">
                {{ item.name }}
              </option>
            </select></label
          >
          <p>目标在会话内固定；创建不会调用模型。评测检查实际轨迹结构，结果不代表业务正确率。</p>
          <div class="harness-actions">
            <button type="button" @click="creating = false">取消</button
            ><button class="primary" :disabled="busy || (kind !== 'sql' && !target)">
              创建会话
            </button>
          </div>
        </form>
      </section>
    </div>
  </div>
</template>
