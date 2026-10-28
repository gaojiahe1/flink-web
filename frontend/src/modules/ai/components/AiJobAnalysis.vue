<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { X, Sparkles, LoaderCircle, ArrowUp, Check, RefreshCw } from '@lucide/vue'
import AiModelSelect from './AiModelSelect.vue'
import CodeDiff from '../../../shared/components/editor/CodeDiff.vue'
import AiRichText from './AiRichText.vue'
import AiContentDialog from './AiContentDialog.vue'
import AiEvidenceView from './AiEvidenceView.vue'
import HelpTip from '../../../shared/components/form/HelpTip.vue'
import { aiApi, type AiJobConversation, type AiJobApplication, type AiModel } from '../api/ai'
import { useAiTask } from '../composables/useAiTask'
import { requestId } from '../../../shared/utils/requestId'
import { ApiError } from '../../../shared/api/http'

const props = defineProps<{
  job: { id: string; name: string }
  error?: string
  canReadLogs: boolean
}>()
const emit = defineEmits<{ close: []; changed: []; restarted: [] }>()
const dialog = ref<HTMLDialogElement>(),
  messages = ref<HTMLElement>()
const conversation = ref<AiJobConversation>(),
  loading = ref(true),
  saving = ref(false),
  applying = ref(false)
const collecting = ref(false)
const evidenceCategory = ref('auto')
const modelId = ref(''),
  model = ref<AiModel>(),
  allowExternal = ref(false),
  includeLogs = ref(true)
const instruction = ref(''),
  failure = ref(''),
  notice = ref(''),
  editingSummary = ref(false),
  summaryText = ref('')
const unknownsText = ref('')
const file = ref<'yaml' | 'sql'>('yaml'),
  mode = ref<'save' | 'restart'>('save'),
  restoreMode = ref('')
const savepointPath = ref(''),
  confirmed = ref(false),
  acceptImpact = ref(false)
const application = ref<AiJobApplication>()
const body = ref<HTMLElement>(),
  split = ref(55),
  largeView = ref<'summary' | 'evidence' | 'comparison' | 'current'>(),
  stacked = ref(false)
const drawerWidth = ref(1180),
  availableWidth = ref(window.innerWidth),
  drawerHeight = ref(document.documentElement.clientHeight)
const consentRequest = ref<{ kind: 'chat' | 'repair'; repeat: boolean }>()
let sidebarObserver: ResizeObserver | undefined
let stopDrawerResize: (() => void) | undefined
const minDrawerWidth = computed(() => Math.min(640, availableWidth.value))
function measureDrawer() {
  drawerHeight.value = document.documentElement.clientHeight
  const sidebar = document.querySelector('.sidebar')?.getBoundingClientRect()
  availableWidth.value = Math.max(240, document.documentElement.clientWidth - (sidebar?.right || 0))
  drawerWidth.value = Math.max(
    minDrawerWidth.value,
    Math.min(drawerWidth.value, availableWidth.value),
  )
}
function resizeDrawer(event: PointerEvent) {
  if (event.button !== 0) return
  event.preventDefault()
  stopDrawerResize?.()
  const move = (e: PointerEvent) => {
    drawerWidth.value = Math.max(
      minDrawerWidth.value,
      Math.min(availableWidth.value, document.documentElement.clientWidth - e.clientX),
    )
  }
  const stop = () => {
    stopDrawerResize?.()
    stopDrawerResize = undefined
  }
  stopDrawerResize = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', stop)
    window.removeEventListener('pointercancel', stop)
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', stop)
  window.addEventListener('pointercancel', stop)
}
function resizeDrawerKey(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  drawerWidth.value = Math.max(
    minDrawerWidth.value,
    Math.min(
      availableWidth.value,
      event.key === 'Home'
        ? 1180
        : event.key === 'End'
          ? availableWidth.value
          : drawerWidth.value + (event.key === 'ArrowLeft' ? 40 : -40),
    ),
  )
}
let stopResize: (() => void) | undefined
function resize(event: PointerEvent) {
  if (!body.value || drawerWidth.value <= 1000 || event.button !== 0) return
  event.preventDefault()
  stopResize?.()
  const move = (e: PointerEvent) => {
    const rect = body.value!.getBoundingClientRect()
    split.value = Math.max(28, Math.min(72, ((e.clientX - rect.left) / rect.width) * 100))
  }
  stopResize = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', stop)
    window.removeEventListener('pointercancel', stop)
  }
  const stop = () => {
    stopResize?.()
    stopResize = undefined
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', stop)
  window.addEventListener('pointercancel', stop)
}
function resizeKey(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home'].includes(event.key)) return
  event.preventDefault()
  split.value =
    event.key === 'Home'
      ? 55
      : Math.max(28, Math.min(72, split.value + (event.key === 'ArrowLeft' ? -3 : 3)))
}
const pendingApplication = ref<{ planId: string; input: Record<string, unknown> }>()
const { task, busy, error: taskError, uncertain, retry, start, cancel, resume } = useAiTask()
let disposed = false
const working = computed(
  () => busy.value || saving.value || applying.value || loading.value || collecting.value,
)
const progressText = computed(() => {
  if (uncertain.value) return '任务状态待核对，请核对原任务后继续。'
  if (applying.value) return '正在提交已确认的修复，请稍候…'
  if (collecting.value) return '正在采集本轮证据，请稍候…'
  if (busy.value) {
    if (task.value?.state === 'QUEUED') return '分析任务已排队，等待 AI 处理…'
    if (task.value?.state === 'RUNNING') return 'AI 正在取证并分析，请稍候…'
    return '正在提交本轮分析，请稍候…'
  }
  if (saving.value) return '正在保存本轮内容，请稍候…'
  return ''
})
const elapsedSeconds = ref(0)
let progressTimer: ReturnType<typeof setInterval> | undefined
watch(
  working,
  (active) => {
    if (progressTimer) clearInterval(progressTimer)
    progressTimer = undefined
    elapsedSeconds.value = 0
    if (active) {
      const startedAt = Date.now()
      progressTimer = setInterval(() => {
        elapsedSeconds.value = Math.floor((Date.now() - startedAt) / 1000)
      }, 1000)
    }
  },
  { immediate: true },
)
const plan = computed(() => conversation.value?.plan)
const displayedError = computed(() =>
  conversation.value?.evidence ? conversation.value.evidence.runtimeError || '' : props.error || '',
)
const ready = computed(() => !!modelId.value && !working.value)
const mayApply = computed(
  () =>
    !!plan.value?.canApply &&
    !!conversation.value?.canEdit &&
    confirmed.value &&
    !working.value &&
    !pendingApplication.value &&
    (mode.value === 'save' ||
      (!!conversation.value?.canOperate && !!restoreMode.value && acceptImpact.value)),
)
const currentText = computed(() =>
  file.value === 'sql' ? plan.value?.beforeSql || '' : plan.value?.beforeYaml || '',
)
const proposedText = computed(() =>
  file.value === 'sql' ? plan.value?.sql || '' : plan.value?.afterYaml || '',
)
const applicationLabel = (state: string) =>
  ({
    SAVED: '草稿已保存',
    ACCEPTED: '操作已受理，待观察',
    REJECTED: '运行操作被拒绝',
    FAILED: '应用未完成',
    UNKNOWN: '结果待核对',
    SUBMITTING: '提交中',
    SAVING: '保存中',
  })[state] || state

async function reload() {
  const value = await aiApi.jobConversation(props.job.id)
  if (disposed) return
  conversation.value = value
  application.value = value.application
}
async function refreshEvidence(category: string) {
  if (working.value) return
  collecting.value = true
  failure.value = ''
  try {
    const value = await aiApi.jobEvidence(props.job.id, category)
    if (!disposed && conversation.value) conversation.value.evidence = value
  } catch (e) {
    if (!disposed) failure.value = (e as Error).message
  } finally {
    collecting.value = false
  }
}
function finishRestart() {
  if (application.value?.mode === 'restart' && application.value.state === 'ACCEPTED') {
    emit('restarted')
    return true
  }
  return false
}
onMounted(async () => {
  measureDrawer()
  window.addEventListener('resize', measureDrawer)
  sidebarObserver = new ResizeObserver(measureDrawer)
  const sidebar = document.querySelector('.sidebar')
  if (sidebar) sidebarObserver.observe(sidebar)
  dialog.value?.showModal()
  try {
    await reload()
    if (
      conversation.value?.lastTask &&
      ['QUEUED', 'RUNNING'].includes(conversation.value.lastTask.state)
    )
      await resume(conversation.value.lastTask.id)
    else if (conversation.value?.lastTask?.state === 'FAILED') {
      task.value = conversation.value.lastTask
      failure.value =
        conversation.value.lastTask.message || '上次分析未完成，可核对当前对话后重试。'
    }
  } catch (e) {
    failure.value = (e as Error).message
  } finally {
    if (!disposed) loading.value = false
  }
})
onBeforeUnmount(() => {
  disposed = true
  if (progressTimer) clearInterval(progressTimer)
  stopResize?.()
  stopDrawerResize?.()
  sidebarObserver?.disconnect()
  window.removeEventListener('resize', measureDrawer)
})
watch(modelId, () => {
  allowExternal.value = false
  consentRequest.value = undefined
})
watch([mode, restoreMode, savepointPath], () => {
  confirmed.value = false
  acceptImpact.value = false
})
watch(
  () => plan.value?.id,
  () => {
    confirmed.value = false
    file.value = plan.value?.sqlChanged ? 'sql' : 'yaml'
  },
)
watch(
  () => task.value?.state,
  async (state) => {
    if (!state || ['QUEUED', 'RUNNING'].includes(state)) return
    try {
      await reload()
    } catch (e) {
      if (!disposed) failure.value = (e as Error).message
    }
    if (state === 'SUCCEEDED')
      notice.value = conversation.value?.plan
        ? 'AI 已回答；已生成修改建议，尚未保存或重启。'
        : 'AI 已回答；本轮尚未生成可应用的 SQL / 配置候选，可补充证据或生成修复方案。'
  },
)
watch(
  () => conversation.value?.messages.length,
  async () => {
    await nextTick()
    if (messages.value) messages.value.scrollTop = messages.value.scrollHeight
  },
)

async function ask(kind: 'chat' | 'repair', repeat = false) {
  if (!ready.value || !conversation.value || pendingApplication.value) return
  if (model.value?.external && !allowExternal.value) {
    consentRequest.value = { kind, repeat }
    await nextTick()
    dialog.value?.querySelector<HTMLButtonElement>('.ai-job-consent .primary')?.focus()
    return
  }
  consentRequest.value = undefined
  saving.value = true
  failure.value = ''
  notice.value = ''
  confirmed.value = false
  try {
    if (!repeat) {
      const content =
        instruction.value.trim() ||
        (kind === 'repair'
          ? '请基于当前证据和会话总结生成可审阅的修复方案，说明影响与前置检查。'
          : '请综合当前作业状态、部署配置、Pod 事件及有权读取的日志分析原因，区分事实与推测。')
      const input = {
        messageId: requestId(),
        revision: conversation.value.revision,
        content,
        mode: kind,
      }
      try {
        conversation.value = await aiApi.jobMessage(props.job.id, input)
      } catch (e) {
        await reload()
        if (!conversation.value?.messages.some((m) => m.id === input.messageId)) throw e
      }
      instruction.value = ''
    }
    await start({
      kind: 'diagnosis',
      mode: kind,
      modelId: modelId.value,
      jobId: props.job.id,
      jobConversationVersion: conversation.value!.contentVersion,
      allowExternal: allowExternal.value,
      includeLogs: includeLogs.value && props.canReadLogs,
      evidenceCategory: evidenceCategory.value,
    })
    allowExternal.value = false
  } catch (e) {
    failure.value = (e as Error).message
  } finally {
    saving.value = false
  }
}
async function allowAndAsk() {
  const request = consentRequest.value
  if (!request) return
  allowExternal.value = true
  await ask(request.kind, request.repeat)
}
function editSummary() {
  summaryText.value = conversation.value?.summary || ''
  unknownsText.value = conversation.value?.unknowns.join('\n') || ''
  editingSummary.value = true
}
async function saveSummary() {
  if (!conversation.value || working.value) return
  saving.value = true
  failure.value = ''
  try {
    conversation.value = await aiApi.jobSummary(props.job.id, {
      revision: conversation.value.revision,
      summaryRevision: conversation.value.summaryRevision,
      summary: summaryText.value,
      unknowns: unknownsText.value
        .split('\n')
        .map((text) => text.trim())
        .filter(Boolean),
    })
    editingSummary.value = false
  } catch (e) {
    failure.value = (e as Error).message
  } finally {
    saving.value = false
  }
}
async function apply() {
  if (!plan.value || applying.value || (!pendingApplication.value && !mayApply.value)) return
  const pending = pendingApplication.value || {
    planId: plan.value.id,
    input: {
      requestId: requestId(),
      mode: mode.value,
      confirmed: true,
      restoreMode: restoreMode.value,
      savepointPath: savepointPath.value,
      acceptImpact: acceptImpact.value,
    },
  }
  pendingApplication.value = pending
  applying.value = true
  failure.value = ''
  try {
    application.value = await aiApi.applyJobPlan(props.job.id, pending.planId, pending.input)
    pendingApplication.value = undefined
    if (finishRestart()) return
    const outcome = application.value
    await reload()
    if (outcome?.mode === 'save' && outcome.state === 'SAVED')
      notice.value = '草稿已保存，后续分析将读取最新 SQL / 配置。'
    emit('changed')
  } catch (e) {
    try {
      application.value = await aiApi.jobApplication(props.job.id, String(pending.input.requestId))
      pendingApplication.value = undefined
      if (finishRestart()) return
      const outcome = application.value
      await reload()
      if (outcome?.mode === 'save' && outcome.state === 'SAVED')
        notice.value = '草稿已保存，后续分析将读取最新 SQL / 配置。'
      emit('changed')
    } catch (lookup) {
      if (
        e instanceof ApiError &&
        [400, 403, 409, 422].includes(e.status) &&
        lookup instanceof ApiError &&
        lookup.status === 404
      )
        pendingApplication.value = undefined
      failure.value = pendingApplication.value
        ? '应用响应尚未确认，请核对并重试原应用；不会生成新请求号。'
        : (e as Error).message
    }
  } finally {
    applying.value = false
  }
}
async function continueApplication() {
  if (!application.value?.request || !plan.value) return
  pendingApplication.value = { planId: plan.value.id, input: application.value.request }
  await apply()
}
</script>

<template>
  <dialog
    ref="dialog"
    class="ai-analysis-drawer"
    :class="{ 'ai-drawer-narrow': drawerWidth <= 1000 }"
    :style="{
      width: drawerWidth + 'px',
      maxWidth: availableWidth + 'px',
      '--ai-drawer-height': drawerHeight + 'px',
    }"
    :aria-label="'AI 作业助手：' + job.name"
    @cancel.prevent="emit('close')"
  >
    <div
      class="ai-drawer-resizer"
      role="separator"
      aria-label="调整 AI 助手宽度"
      aria-orientation="vertical"
      tabindex="0"
      :aria-valuenow="Math.round(drawerWidth)"
      :aria-valuemin="minDrawerWidth"
      :aria-valuemax="Math.round(availableWidth)"
      @pointerdown="resizeDrawer"
      @keydown="resizeDrawerKey"
    >
      <span />
    </div>
    <header class="field-heading ai-job-heading">
      <div>
        <h3><Sparkles :size="18" /> AI 作业助手</h3>
        <p class="muted">{{ job.name }} · 分析原因、持续沟通、审阅修复</p>
      </div>
      <button class="icon-button" aria-label="关闭 AI 助手" @click="emit('close')">
        <X :size="19" />
      </button>
    </header>
    <details v-if="displayedError" class="notice error ai-job-current-error">
      <summary :title="displayedError">
        {{
          conversation?.evidence
            ? '本轮重新采集的运行异常'
            : '列表上次查询的运行异常；发送后重新取证'
        }}
        · {{ displayedError }}
      </summary>
      <pre>{{ displayedError }}</pre>
    </details>
    <p v-if="loading" class="muted" role="status">
      <LoaderCircle :size="14" class="ai-job-spin" /> 正在恢复本作业对话…
    </p>
    <div
      v-else
      ref="body"
      class="ai-job-body"
      :class="{ 'ai-job-has-plan': !!plan }"
      :style="{ '--chat-width': split + '%' }"
    >
      <section class="ai-job-conversation" aria-label="诊断对话">
        <div class="ai-job-context">
          <div class="ai-job-settings">
            <AiModelSelect
              v-model="modelId"
              purpose="diagnosis"
              :disabled="working"
              @selected="model = $event"
            />
            <label class="ai-job-baseline ai-job-direction"
              >本轮取证方向
              <select v-model="evidenceCategory" :disabled="working" aria-label="本轮取证方向">
                <option value="auto">按当前报错自动判断</option>
                <option value="configuration">SQL / 部署配置</option>
                <option value="pods">Pod 配置与事件</option>
                <option value="podMetrics">Pod 资源用量</option>
                <option value="flinkExceptions">Flink 异常</option>
                <option value="flinkCheckpoints">快照 / Checkpoint</option>
                <option value="flinkMetrics">JVM / GC 指标</option>
                <option v-if="canReadLogs" value="flinkLogs">Flink 日志</option>
              </select>
            </label>
            <label v-if="canReadLogs" class="ai-check"
              ><input
                v-model="includeLogs"
                :disabled="working"
                type="checkbox" />包含容器日志<HelpTip
                text="按本轮需要读取有权访问的JM/TM及故障容器日志，最多四份，各取末尾600行，再提取异常附近和最后输出；并非完整日志。"
            /></label>
            <label v-if="model?.external" class="ai-check ai-external"
              ><input
                v-model="allowExternal"
                :disabled="working"
                type="checkbox" />允许本次发送脱敏上下文<HelpTip
                text="本轮向所选外部模型发送本作业总结、近期对话、当前SQL、脱敏配置、事件和所选取证方向的数据；勾选后附带受限容器日志，每轮单独确认。"
            /></label>
          </div>
          <p v-if="conversation?.current" class="muted ai-job-baseline">
            当前草稿 r{{ conversation.current.baseline.jobRevision
            }}<template v-if="conversation.current.baseline.sqlRevision !== undefined">
              · SQL r{{ conversation.current.baseline.sqlRevision }}</template
            >
            <button class="text-button" :disabled="working" @click="reload">
              重新核对最新配置
            </button>
            <button class="text-button" :disabled="working" @click="largeView = 'current'">
              查看当前 SQL / YAML
            </button>
          </p>
          <p
            v-if="conversation?.round?.reason"
            class="muted ai-job-baseline ai-job-round"
            :title="conversation.round.reason"
          >
            {{ conversation.round.reason }}
          </p>
          <p v-if="conversation?.proposalWarning" class="notice">
            {{ conversation.proposalWarning }}
          </p>
        </div>

        <button class="ai-job-summary" @click="largeView = 'summary'">
          当前总结
          <span class="muted">{{
            conversation?.compressedMessages
              ? `· 已归纳 ${conversation.compressedMessages} 条较早消息`
              : '· 每次对话自动带入'
          }}</span>
          <span class="muted">打开大视图 ↗</span>
        </button>
        <div ref="messages" class="ai-job-messages" aria-label="近期对话" :aria-busy="busy">
          <div v-if="!conversation?.messages.length" class="ai-job-empty">
            <Sparkles :size="25" />
            <h4>结合当前作业一起排查</h4>
            <p>主动读取有权访问的运行证据，先分析原因，再讨论修复。</p>
            <button class="button primary" :disabled="!ready" @click="ask('chat')">
              开始 AI 分析
            </button>
          </div>
          <article
            v-for="item in conversation?.messages"
            :key="item.id"
            class="ai-job-message"
            :class="{ 'ai-job-user': item.role === 'user' }"
          >
            <small>{{ item.role === 'user' ? '你' : 'AI 助手' }}</small>
            <AiRichText :text="item.content" />
          </article>
        </div>
        <div class="ai-job-evidence">
          <p v-if="busy || collecting" class="muted" role="status">
            正在采集当前轮次证据，完成前请勿将已有快照当作最新状态。
          </p>
          <template v-if="conversation?.evidence">
            <button class="text-button" @click="largeView = 'evidence'">
              本轮证据 · {{ new Date(conversation.evidence.queriedAt).toLocaleTimeString() }}
              <span>· 打开大视图查看 Pod、事件、配置和日志 ↗</span>
            </button>
          </template>
          <button
            v-else
            class="text-button"
            :disabled="working"
            @click="
              refreshEvidence('auto').then(() => {
                if (conversation?.evidence) largeView = 'evidence'
              })
            "
          >
            按需采集证据 · 查看 Pod、事件、配置和日志 ↗
          </button>
        </div>
        <div class="ai-job-input">
          <div v-if="progressText" class="ai-job-progress" role="status" aria-live="polite">
            <RefreshCw v-if="uncertain" :size="16" aria-hidden="true" />
            <LoaderCircle v-else :size="16" class="ai-job-spin" aria-hidden="true" />
            <span>{{ progressText }}</span>
            <small v-if="!uncertain" class="ai-job-elapsed" aria-hidden="true"
              >已等待 {{ elapsedSeconds }} 秒</small
            >
          </div>
          <label for="ai-job-question">问题或补充说明</label
          ><textarea
            id="ai-job-question"
            v-model="instruction"
            :disabled="working || !!pendingApplication"
            maxlength="8000"
            rows="3"
            placeholder="补充现象、说明不能改变的业务逻辑，或提出修复要求…"
          />
          <div
            v-if="consentRequest"
            class="notice ai-job-consent"
            role="group"
            aria-label="确认本轮外部发送"
          >
            <p>向“{{ model?.name }}”发送本轮脱敏上下文后继续分析？</p>
            <p class="muted">
              包含作业总结、近期对话、当前 SQL、受限配置、事件{{
                includeLogs && canReadLogs ? '及所选容器日志' : ''
              }}。每轮单独授权，确认前不会发送或追加消息。
            </p>
            <div class="toolbar-actions">
              <button class="button primary" :disabled="!ready" @click="allowAndAsk">
                允许并发送本轮
              </button>
              <button class="button" @click="consentRequest = undefined">取消发送</button>
            </div>
          </div>
          <p v-else-if="model?.external && !allowExternal" class="muted ai-job-consent-hint">
            外部模型每轮需授权；点击发送后可确认继续。
          </p>
          <div class="ai-job-actions">
            <button class="button" :disabled="!ready || !!pendingApplication" @click="ask('chat')">
              <ArrowUp :size="14" />{{
                conversation?.messages.length ? '发送并更新证据' : '开始 AI 分析'
              }}</button
            ><button
              class="button primary"
              :disabled="!ready || !conversation?.messages.length || !!pendingApplication"
              @click="ask('repair')"
            >
              {{ plan?.canApply ? '重新生成修复方案' : '生成修复方案' }}</button
            ><button v-if="busy" class="text-button" @click="cancel">取消分析</button
            ><button v-if="uncertain" class="text-button" @click="retry">核对原任务并重试</button
            ><button
              v-else-if="task?.state === 'FAILED' && conversation?.messages.length"
              class="text-button"
              :disabled="!ready"
              @click="
                ask(
                  conversation.messages.filter((m) => m.role === 'user').at(-1)?.mode === 'repair'
                    ? 'repair'
                    : 'chat',
                  true,
                )
              "
            >
              重试本轮分析
            </button>
          </div>
        </div>
      </section>
      <div
        v-if="plan"
        class="ai-job-resizer"
        role="separator"
        aria-label="调整对话与修复区域宽度"
        aria-orientation="vertical"
        :aria-valuenow="Math.round(split)"
        aria-valuemin="28"
        aria-valuemax="72"
        tabindex="0"
        @pointerdown="resize"
        @keydown="resizeKey"
      >
        <span />
      </div>
      <section v-if="plan" class="ai-job-plan" aria-label="修复方案审阅">
        <h4>修复方案与完整对比</h4>
        <p class="notice ai-job-proposal-state">
          {{ plan.sqlChanged ? '已生成 SQL 修改建议' : ''
          }}{{ plan.sqlChanged && plan.yamlChanged ? '及' : ''
          }}{{ plan.yamlChanged ? '配置修改建议' : '' }} ·
          {{
            plan.applicationId
              ? '本方案已提交应用'
              : plan.canApply
                ? '待你审阅确认，尚未保存或重启'
                : '基准已变化，本方案已失效'
          }}
        </p>
        <p class="muted">
          基于已保存草稿 r{{ plan.baseline.jobRevision
          }}<template v-if="plan.baseline.sqlRevision">
            · SQL r{{ plan.baseline.sqlRevision }}</template
          >。集群声明与当前草稿可能不同，请结合证据核对。
        </p>
        <div class="toolbar-actions">
          <button class="button" :class="{ primary: file === 'yaml' }" @click="file = 'yaml'">
            YAML 对比</button
          ><button
            v-if="plan.sqlChanged"
            class="button"
            :class="{ primary: file === 'sql' }"
            @click="file = 'sql'"
          >
            SQL 对比
          </button>
          <button class="text-button" @click="largeView = 'comparison'">大视图对比 ↗</button>
        </div>
        <CodeDiff
          class="ai-job-diff"
          :before="currentText"
          :after="proposedText"
          :language="file"
        />
        <ul v-if="plan.changes.length" class="ai-job-change-list">
          <li v-for="change in plan.changes" :key="change.path">
            <code>{{ change.path }}</code> → {{ change.value }}
          </li>
        </ul>
        <p class="notice">{{ plan.check }}。应用前会重新核对版本和资源身份；不代表实际运行成功。</p>
        <p v-if="!plan.canApply && !application" class="notice">
          对话、SQL 或配置已变化，旧方案仅供查看，请重新生成。
        </p>
        <template v-if="conversation?.canEdit && !plan.applicationId">
          <fieldset :disabled="working || !!pendingApplication" class="ai-job-apply-mode">
            <legend>应用到 {{ job.name }}</legend>
            <label class="ai-check"
              ><input v-model="mode" type="radio" value="save" />仅保存 SQL /
              配置草稿，当前运行实例不变</label
            ><label class="ai-check"
              ><input
                v-model="mode"
                type="radio"
                value="restart"
                :disabled="!conversation.canOperate"
              />保存、发布并启动 / 重启作业</label
            ><template v-if="mode === 'restart'"
              ><label
                >恢复方式<select v-model="restoreMode" aria-label="恢复方式">
                  <option value="">请选择恢复方式</option>
                  <option value="savepoint">Savepoint 升级 / 指定保存点恢复</option>
                  <option value="last-state">last-state（需要已有 HA 状态）</option>
                  <option value="stateless">stateless（无状态启动）</option>
                </select></label
              ><label v-if="restoreMode === 'savepoint'"
                >保存点路径（正常运行可留空，由 Operator 生成）<input
                  v-model="savepointPath"
                  placeholder="s3://…/savepoint-…" /></label
              ><label class="ai-check"
                ><input
                  v-model="acceptImpact"
                  type="checkbox"
                />已核对恢复方式，接受作业中断及可能的处理进度变化</label
              ></template
            ><label class="ai-check"
              ><input v-model="confirmed" type="checkbox" />我已审阅完整修改、影响和前置检查</label
            >
          </fieldset>
          <button class="button primary ai-job-apply" :disabled="!mayApply" @click="apply">
            <LoaderCircle v-if="applying" :size="14" class="ai-job-spin" />{{
              mode === 'save' ? '确认仅保存草稿' : '确认保存并启动 / 重启'
            }}
          </button>
        </template>
        <p v-else-if="!conversation?.canEdit" class="muted">
          当前权限可分析和审阅；保存与发布需要开发角色及作业编辑权限。
        </p>
        <button v-if="pendingApplication" class="button" :disabled="applying" @click="apply">
          核对并重试原应用
        </button>
        <button
          v-else-if="
            application &&
            (application.state === 'SAVING' ||
              (application.mode === 'restart' &&
                ['SAVED', 'SUBMITTING', 'UNKNOWN'].includes(application.state))) &&
            application.request
          "
          class="button"
          :disabled="working"
          @click="continueApplication"
        >
          核对并继续原应用
        </button>
        <div v-if="application" class="notice ai-job-application" role="status">
          <strong
            ><Check v-if="application.state === 'SAVED'" :size="14" />{{
              applicationLabel(application.state)
            }}</strong
          >
          <p>{{ application.message }}</p>
          <small>应用号 {{ application.id }}</small>
          <p v-if="application.mode === 'restart'">
            受理后请在作业列表手动刷新，核对实际运行状态。
          </p>
          <button class="text-button" :disabled="working" @click="reload">
            <RefreshCw :size="13" />核对原应用记录
          </button>
        </div>
      </section>
    </div>
    <footer class="ai-job-footer">
      <p v-if="failure || taskError" class="notice error" role="alert">
        {{ failure || taskError }}
      </p>
      <p v-else-if="notice" class="muted" role="status">{{ notice }}</p>
      <p class="muted">
        总结与 SQL 开发共用；重启受理后结束本轮并返回作业列表，保留总结。证据按需重新采集。
      </p>
    </footer>
    <AiContentDialog
      v-if="largeView"
      :title="
        largeView === 'current'
          ? '当前已保存 SQL / YAML'
          : largeView === 'summary'
            ? '作业总结'
            : largeView === 'evidence'
              ? '本轮证据详情'
              : '完整修改对比'
      "
      @close="largeView = undefined"
    >
      <template v-if="largeView === 'current' && conversation?.current">
        <p class="muted">
          当前草稿 r{{ conversation.current.baseline.jobRevision }} · SQL r{{
            conversation.current.baseline.sqlRevision ?? '无'
          }}；仅从平台读取最新保存内容，集群声明请在本轮证据中另行采集。
        </p>
        <p class="notice ai-job-proposal-state">{{ conversation.current.referenceNote }}</p>
        <h4 v-if="conversation.current.sql !== undefined">当前 SQL 草稿</h4>
        <pre v-if="conversation.current.sql !== undefined" class="ai-job-current-doc">{{
          conversation.current.sql
        }}</pre>
        <h4 v-if="conversation.current.publishedSql">已发布 SQL</h4>
        <pre v-if="conversation.current.publishedSql" class="ai-job-current-doc">{{
          conversation.current.publishedSql
        }}</pre>
        <h4>当前部署草稿 YAML（完整结构，敏感值脱敏）</h4>
        <pre class="ai-job-current-doc">{{ conversation.current.draftYaml }}</pre>
      </template>
      <template v-else-if="largeView === 'summary'">
        <template v-if="!editingSummary">
          <AiRichText
            :text="
              conversation?.summary ||
              '开始分析后，AI 会总结已知事实、用户约束、尝试结果和待核对事项。'
            "
          />
          <ul v-if="conversation?.unknowns.length" class="muted">
            <li v-for="item in conversation.unknowns" :key="item">待核对：{{ item }}</li>
          </ul>
          <button
            v-if="conversation?.summary"
            class="button"
            :disabled="working"
            @click="editSummary"
          >
            修正总结
          </button>
        </template>
        <template v-else>
          <label>核对后的总结<textarea v-model="summaryText" rows="12" maxlength="8000" /></label>
          <label
            >本作业待核对事项（每行一项，已解决或不相关项可删除）<textarea
              v-model="unknownsText"
              aria-label="编辑本作业待核对事项"
              rows="4"
            />
          </label>
          <div class="toolbar-actions">
            <button
              class="button primary"
              :disabled="working || !summaryText.trim()"
              @click="saveSummary"
            >
              保存总结
            </button>
            <button class="button" :disabled="working" @click="editingSummary = false">取消</button>
          </div>
        </template>
        <p v-if="failure" class="notice error" role="alert">{{ failure }}</p>
      </template>
      <AiEvidenceView
        v-else-if="largeView === 'evidence' && conversation?.evidence"
        :evidence="conversation.evidence"
        :busy="working"
        :can-read-logs="canReadLogs"
        @refresh="refreshEvidence"
      />
      <template v-else-if="plan"
        ><div class="toolbar-actions">
          <button class="button" @click="file = 'yaml'">YAML 对比</button
          ><button v-if="plan.sqlChanged" class="button" @click="file = 'sql'">SQL 对比</button
          ><button class="button" @click="stacked = !stacked">
            {{ stacked ? '切换并排' : '切换上下' }}
          </button>
        </div>
        <CodeDiff
          class="ai-job-large-comparison"
          :before="currentText"
          :after="proposedText"
          :language="file"
          :stacked="stacked"
      /></template>
    </AiContentDialog>
  </dialog>
</template>

<style scoped>
.ai-analysis-drawer {
  box-sizing: border-box;
  position: fixed;
  inset: 0 0 0 auto;
  margin: 0 0 0 auto;
  width: 1180px;
  height: var(--ai-drawer-height, 100vh);
  min-height: var(--ai-drawer-height, 100vh);
  max-height: var(--ai-drawer-height, 100vh);
  max-width: 100vw;
  border: 0;
  background: var(--color-surface);
  color: var(--color-text);
  padding: 16px 20px 12px;
  box-shadow: -5px 0 24px var(--color-shadow-soft);
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow: hidden;
}
.ai-analysis-drawer::backdrop {
  background: var(--color-overlay);
}
.ai-drawer-resizer {
  position: absolute;
  inset: 0 auto 0 0;
  width: 10px;
  cursor: col-resize;
  touch-action: none;
  display: grid;
  place-items: center;
  outline-offset: -2px;
}
.ai-drawer-resizer span {
  height: 64px;
  width: 3px;
  background: var(--line);
  border-radius: 4px;
}
.ai-drawer-resizer:hover,
.ai-drawer-resizer:focus-visible {
  background: var(--color-primary-soft);
}
.ai-drawer-resizer:hover span {
  background: var(--blue);
}
.ai-job-heading {
  flex: none;
}
.ai-job-baseline {
  margin: 0;
  font-size: 12px;
  flex: none;
}
.ai-job-proposal-state {
  display: block;
}
.ai-job-current-doc {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--color-background);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 16px;
  font-size: 13px;
  line-height: 1.7;
}
.ai-job-heading h3 {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
}
.ai-job-heading p {
  margin: 2px 0 0;
  font-size: 12px;
}
.ai-job-diff {
  height: clamp(280px, 50dvh, 650px);
  margin: 8px 0;
}
.ai-job-large-comparison {
  height: 65dvh;
}
.ai-job-current-error {
  max-height: 120px;
}
.ai-job-settings > .ai-check {
  order: 3;
}
.ai-job-context {
  flex: none;
  max-height: min(220px, 32dvh);
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ai-job-settings {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 14px;
  flex: none;
}
.ai-job-settings :deep(.ai-model-field) {
  flex: 1 1 260px;
  min-width: min(210px, 100%);
  margin: 0;
  align-items: center;
}
.ai-job-settings :deep(.ai-model-field label) {
  font-size: 12px;
}
.ai-job-settings > .ai-job-baseline {
  flex: 1 1 200px;
  font-weight: 400;
}
.ai-job-round {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ai-job-context > .notice {
  margin: 0;
  padding: 6px 10px;
  font-size: 12px;
}
.ai-external {
  flex: none;
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
  margin-top: 3px;
}
.ai-job-body {
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  min-height: 0;
  flex: 1;
  gap: 0;
}
.ai-job-has-plan {
  grid-template-columns: minmax(0, var(--chat-width, 55%)) 14px minmax(0, 1fr);
}
.ai-job-resizer {
  cursor: col-resize;
  touch-action: none;
  display: grid;
  place-items: center;
  border-radius: 5px;
  outline-offset: -2px;
}
.ai-job-resizer span {
  width: 3px;
  height: 52px;
  background: var(--line);
  border-radius: 4px;
}
.ai-job-resizer:hover,
.ai-job-resizer:focus-visible {
  background: var(--color-primary-soft);
}
.ai-job-resizer:hover span {
  background: var(--blue);
}
.ai-job-large-comparison-legacy {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 20px;
}
.ai-job-large-comparison.stacked {
  grid-template-columns: minmax(0, 1fr);
}
.ai-job-large-comparison section {
  min-width: 0;
}
.ai-job-large-comparison pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--color-background);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 16px;
  font-size: 13px;
  line-height: 1.7;
}
.ai-job-conversation {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  gap: 8px;
}
.ai-job-summary,
.ai-job-evidence {
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 7px 10px;
  font-size: 12px;
  overflow: auto;
  max-height: 120px;
  flex: none;
}
.ai-job-summary {
  background: var(--color-background);
  color: var(--color-text);
  text-align: left;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
}
.ai-job-summary > span:last-child {
  margin-left: auto;
}
.ai-job-consent-hint {
  margin: 6px 0 0;
  font-size: 12px;
}
.ai-job-consent {
  display: block;
  margin: 8px 0 0;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.5;
}
.ai-job-consent p {
  margin: 0 0 8px;
}
.ai-job-summary summary,
.ai-job-evidence summary {
  cursor: pointer;
}
.ai-job-summary p {
  margin: 8px 0;
}
.ai-job-messages {
  overflow: auto;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 4px 3px;
}
.ai-job-message {
  flex: none;
  background: var(--color-background);
  border-radius: 8px;
  padding: 10px 13px;
  margin-right: 24px;
}
.ai-job-message small {
  color: var(--color-muted);
  font-size: 11px;
}
.ai-job-message p {
  margin: 6px 0 0;
  font-size: 13px;
}
.ai-job-user {
  background: var(--color-primary-soft);
  margin-left: 24px;
  margin-right: 0;
}
.ai-prewrap {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.ai-job-empty {
  display: grid;
  justify-items: center;
  align-content: center;
  gap: 8px;
  flex: 1;
  color: var(--color-muted);
  text-align: center;
  padding: 16px;
}
.ai-job-empty h4,
.ai-job-empty p {
  margin: 0;
}
.ai-job-progress {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 8px;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--color-primary-soft);
  font-size: 12px;
  color: var(--color-primary);
}
.ai-job-elapsed {
  margin-left: auto;
  white-space: nowrap;
  font-size: 11px;
  color: var(--color-muted);
}
.ai-job-input {
  border-top: 1px solid var(--line);
  padding-top: 8px;
  flex: 0 1 auto;
  min-height: 0;
  max-height: 58%;
  overflow: auto;
  scrollbar-width: thin;
}
.ai-job-input label {
  font-size: 12px;
  margin: 0 0 4px;
  line-height: 1.5;
}
.ai-job-input textarea {
  display: block;
  width: 100%;
  min-height: 68px;
  max-height: 150px;
  resize: vertical;
}
.ai-job-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 8px;
}
.ai-job-plan {
  min-width: 0;
  overflow: auto;
  min-height: 0;
  padding-left: 14px;
}
.ai-job-plan h4 {
  margin: 0 0 8px;
}
.ai-job-plan .muted,
.ai-job-footer .muted {
  font-size: 12px;
}
.ai-job-comparison {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 10px;
  margin: 12px 0;
}
.ai-job-comparison h5 {
  margin: 3px 0 8px;
  font-size: 12px;
  font-weight: 500;
}
.ai-job-comparison section {
  min-width: 0;
}
.ai-job-comparison :deep(.cm-editor) {
  height: clamp(300px, 45vh, 620px);
  font-size: 13px;
}
.ai-job-comparison :deep(.editor-shell) {
  min-width: 0;
}
.ai-job-change-list {
  padding-left: 18px;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.ai-job-evidence pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 11px;
  max-height: 250px;
  overflow: auto;
}
.ai-job-evidence details {
  margin-top: 8px;
}
.ai-job-apply-mode {
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 12px;
  display: grid;
  gap: 10px;
  min-width: 0;
}
.ai-job-apply-mode legend {
  font-size: 12px;
  padding: 0 4px;
}
.ai-job-apply-mode input:not([type='radio']):not([type='checkbox']),
.ai-job-apply-mode select {
  width: 100%;
}
.ai-job-apply {
  margin: 12px 0;
}
.ai-job-application strong {
  display: flex;
  align-items: center;
  gap: 5px;
}
.ai-job-application small {
  overflow-wrap: anywhere;
}
.ai-job-footer {
  display: block;
  flex: none;
  border-top: 1px solid var(--line);
  padding: 6px 0 0;
  max-height: 90px;
  overflow: auto;
  font-size: 11px;
}
.ai-job-footer .notice {
  margin: 0;
  padding: 6px 10px;
  font-size: 12px;
}
.ai-job-footer p {
  margin: 4px 0;
}
.ai-job-current-error {
  display: block;
  flex: none;
  margin: 0;
}
.ai-job-current-error summary {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: pointer;
}
.ai-job-current-error pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 90px;
  overflow: auto;
  margin: 6px 0 0;
  font: inherit;
}
.ai-job-spin {
  animation: ai-job-spin 1s linear infinite;
  flex: none;
  transform-origin: center;
  transform-box: fill-box;
}
@keyframes ai-job-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (prefers-reduced-motion: reduce) {
  .ai-job-spin {
    animation: none;
  }
}
.ai-drawer-narrow {
  padding: 16px;
}
.ai-drawer-narrow .ai-job-has-plan {
  grid-template-columns: 1fr;
  grid-template-rows: auto auto;
  overflow: auto;
}
.ai-drawer-narrow .ai-job-has-plan .ai-job-conversation {
  height: max(420px, 65dvh);
  flex: none;
}
.ai-drawer-narrow .ai-job-plan {
  overflow: visible;
  border-left: 0;
  border-top: 1px solid var(--line);
  padding: 16px 0;
}
.ai-drawer-narrow .ai-job-resizer {
  display: none;
}
@media (max-width: 540px) {
  .ai-job-comparison {
    grid-template-columns: 1fr;
  }
  .ai-job-comparison :deep(.cm-editor) {
    height: 200px;
  }
  .ai-job-settings {
    gap: 8px;
  }
}
@media (max-height: 700px) {
  .ai-analysis-drawer {
    gap: 8px;
    padding-top: 12px;
  }
  .ai-job-input textarea {
    min-height: 50px;
    max-height: 90px;
  }
  .ai-job-summary {
    max-height: 22%;
  }
}
</style>
