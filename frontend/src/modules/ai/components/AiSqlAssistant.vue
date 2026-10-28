<script setup lang="ts">
import CodeDiff from '../../../shared/components/editor/CodeDiff.vue'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import forge from 'node-forge'
import {
  ArrowUp,
  Check,
  ChevronDown,
  ChevronRight,
  LoaderCircle,
  MessageSquare,
  Sparkles,
  X,
} from '@lucide/vue'
import AiModelSelect from './AiModelSelect.vue'
import AiResultReport from './AiResultReport.vue'
import HelpTip from '../../../shared/components/form/HelpTip.vue'
import AiRichText from './AiRichText.vue'
import AiContentDialog from './AiContentDialog.vue'
import { aiApi, type AiConversation, type AiModel, type AiResult, type AiTask } from '../api/ai'
import { useAiTask } from '../composables/useAiTask'
import { requestId } from '../../../shared/utils/requestId'

const props = defineProps<{
  sql: string
  udfContext?: unknown[]
  catalogId?: string
  catalogIds?: string[]
  sqlId: string
  flinkVersion: string
  contextKey: number
  editable: boolean
  checkSummary?: string
  dirty?: boolean
  active?: boolean
  canVerify?: boolean
  sqlCollapsed?: boolean
}>()
const emit = defineEmits<{
  close: []
  create: []
  apply: [AiResult, number, (success: boolean) => void]
  review: [AiResult | undefined]
  check: []
  save: []
  run: []
  navigation: [boolean[]]
  'toggle-sql': []
}>()
const step = defineModel<number>('step', { default: 1 })
const summaryLarge = ref(false)
const conversation = ref<AiConversation>()
const modelId = ref(''),
  selectedModel = ref<AiModel>(),
  allowExternal = ref(false)
const input = ref(''),
  mode = ref<'requirement' | 'error'>('requirement')
const localError = ref(''),
  note = ref(''),
  loading = ref(false),
  mutation = ref(false)
const editingSummary = ref(false),
  summaryExpanded = ref(false),
  summaryText = ref(''),
  applying = ref(false)
const unknownsText = ref('')
const suggestion = ref<AiTask>(),
  baseline = ref(-1),
  appliedVersion = ref(-1)
const thread = ref<HTMLElement>(),
  reviewDialog = ref<HTMLDialogElement>(),
  showOriginal = ref(false)
const { task, busy, error, uncertain, start, retry, cancel } = useAiTask()
let epoch = 0,
  loadVersion = 0,
  disposed = false,
  handled = '',
  activeContext = -1
let pendingMessage: { messageId: string; content: string; mode: string } | undefined
const hash = (sql: string) => forge.md.sha256.create().update(sql, 'utf8').digest().toHex()
const result = computed(() =>
  suggestion.value?.state === 'SUCCEEDED' ? suggestion.value.result : undefined,
)
const blocked = computed(() => loading.value || mutation.value || busy.value || !props.editable)
const processing = computed(() => loading.value || mutation.value || busy.value)
const activity = computed(() =>
  loading.value
    ? '正在同步对话…'
    : busy.value
      ? 'AI 正在思考，请稍候…'
      : mutation.value
        ? '正在提交…'
        : '',
)
const confirmationHint = computed(() =>
  !conversation.value?.summary
    ? '先发送需求，AI 回答后会整理总结。'
    : !summaryCurrent.value
      ? 'SQL 或需求已变化，请先整理本次对话。'
      : conversation.value.unknowns.length
        ? '确认将按已知内容生成；未确定条件保持现状，不自动扩展。'
        : '核对总结后即可确认生成 SQL。',
)
const summaryCurrent = computed(
  () =>
    !!conversation.value?.summary &&
    conversation.value.summaryVersion === conversation.value.contentVersion &&
    conversation.value.summarySqlHash === hash(props.sql),
)
const applied = computed(
  () =>
    appliedVersion.value === props.contextKey &&
    !!result.value?.sql &&
    result.value.sql === props.sql,
)
const canApply = computed(
  () =>
    !!result.value?.sql &&
    baseline.value === props.contextKey &&
    result.value.baseSqlHash === hash(props.sql) &&
    result.value.conversationVersion === conversation.value?.contentVersion &&
    conversation.value?.confirmedVersion === conversation.value?.contentVersion &&
    !blocked.value &&
    !applying.value,
)
function nextStep(value: number) {
  if ((value === 2 && !result.value?.sql) || (value === 3 && !applied.value)) return
  step.value = value
  emit('review', value === 2 && !showOriginal.value ? result.value : undefined)
}
defineExpose({ nextStep })
watch([result, applied], () => emit('navigation', [true, !!result.value?.sql, applied.value]), {
  immediate: true,
})
watch(step, (value) =>
  emit('review', value === 2 && !showOriginal.value ? result.value : undefined),
)
watch(showOriginal, () =>
  emit('review', step.value === 2 && !showOriginal.value ? result.value : undefined),
)
watch(
  () => props.contextKey,
  () => {
    epoch++
    baseline.value = -1
    appliedVersion.value = -1
    emit('review', undefined)
    if (busy.value) void cancel()
  },
  { flush: 'sync' },
)
watch(modelId, () => {
  allowExternal.value = false
  epoch++
  if (busy.value) void cancel()
})
watch([busy, () => conversation.value?.messages.length], async () => {
  await nextTick()
  if (thread.value) thread.value.scrollTop = thread.value.scrollHeight
})
watch(
  () => props.active,
  (active) => {
    if (active === false && busy.value) void cancel()
  },
)
async function load(restore = false) {
  if (!props.sqlId) return
  const id = props.sqlId,
    request = ++loadVersion
  const current = () => !disposed && id === props.sqlId && request === loadVersion
  loading.value = true
  try {
    const value = await aiApi.conversation(id)
    if (!current()) return
    if (!conversation.value || value.revision >= conversation.value.revision)
      conversation.value = value
    if (restore && value.lastSqlTaskId) {
      const previous = await aiApi.task(value.lastSqlTaskId)
      if (current() && previous.result?.conversationVersion === value.contentVersion) {
        suggestion.value = previous
        if (previous.result?.baseSqlHash === hash(props.sql)) baseline.value = props.contextKey
        if (previous.result?.sql === props.sql && value.confirmedVersion === value.contentVersion)
          appliedVersion.value = props.contextKey
      }
    }
    await nextTick()
    if (current() && thread.value) thread.value.scrollTop = thread.value.scrollHeight
  } catch (cause) {
    if (current()) localError.value = (cause as Error).message
  } finally {
    if (current()) loading.value = false
  }
}
watch(
  () => props.sqlId,
  () => {
    conversation.value = undefined
    suggestion.value = undefined
    step.value = 1
    void load(true)
  },
  { immediate: true },
)
watch(task, async (value) => {
  if (!value || !['SUCCEEDED', 'FAILED', 'CANCELED'].includes(value.state) || handled === value.id)
    return
  handled = value.id
  const version = epoch,
    valid = activeContext === epoch
  await load()
  if (disposed || version !== epoch || !valid) return
  if (
    value.state === 'SUCCEEDED' &&
    value.result?.sql &&
    value.result.conversationVersion === conversation.value?.contentVersion
  ) {
    suggestion.value = value
    baseline.value = props.contextKey
    showOriginal.value = false
    nextStep(2)
  }
})
function requireModel() {
  if (!modelId.value) throw new Error('请选择可用的任务模型')
  if (selectedModel.value?.external && !allowExternal.value)
    throw new Error('请明确允许本次脱敏上下文发送到外部模型')
}
async function analyze(kind: 'chat' | 'explain' | 'generate' | 'repair') {
  requireModel()
  if (!conversation.value) throw new Error('请先创建并确认 SQL 草稿名称')
  activeContext = epoch
  await start({
    kind: 'sql',
    mode: kind,
    modelId: modelId.value,
    allowExternal: allowExternal.value,
    sqlId: props.sqlId,
    sql: props.sql,
    udfContext: props.udfContext || [],
    catalogId: props.catalogId || '',
    catalogIds: props.catalogIds || [],
    flinkVersion: props.flinkVersion,
    conversationVersion: conversation.value.contentVersion,
  })
}
async function act(action: () => Promise<void>) {
  if (mutation.value || loading.value) return
  localError.value = ''
  note.value = ''
  mutation.value = true
  try {
    await action()
  } catch (cause) {
    localError.value = (cause as Error).message
  } finally {
    mutation.value = false
  }
}
async function send(explain = false) {
  if (blocked.value) return
  await act(async () => {
    requireModel()
    if (!conversation.value) throw new Error('请先新建 SQL 草稿')
    const content = explain
      ? '请解释并整理当前 SQL，将逻辑和已有需求保存到本次会话总结。'
      : input.value.trim()
    if (!content) throw new Error('请填写需求或粘贴报错信息')
    const messageMode = explain ? 'explain' : mode.value
    if (
      !pendingMessage ||
      pendingMessage.content !== content ||
      pendingMessage.mode !== messageMode
    )
      pendingMessage = { messageId: requestId(), content, mode: messageMode }
    const version = epoch
    const value = await aiApi.message(props.sqlId, {
      ...pendingMessage,
      revision: conversation.value.revision,
    })
    pendingMessage = undefined
    if (disposed || version !== epoch) return
    conversation.value = value
    input.value = ''
    suggestion.value = undefined
    appliedVersion.value = -1
    step.value = 1
    await analyze(explain ? 'explain' : 'chat')
  })
}
async function summarize() {
  if (!blocked.value) await act(() => analyze('chat'))
}
async function confirmAndGenerate() {
  if (blocked.value || !summaryCurrent.value || editingSummary.value) return
  await act(async () => {
    requireModel()
    const version = epoch
    const value = await aiApi.confirm(props.sqlId, {
      revision: conversation.value!.revision,
      summaryRevision: conversation.value!.summaryRevision,
      sql: props.sql,
      acceptCurrentScope: true,
    })
    if (disposed || version !== epoch) return
    conversation.value = value
    await analyze(
      conversation.value.messages.filter((m) => m.role === 'user').at(-1)?.mode === 'error'
        ? 'repair'
        : 'generate',
    )
  })
}
async function saveSummary() {
  await act(async () => {
    const version = epoch
    const value = await aiApi.summary(props.sqlId, {
      revision: conversation.value!.revision,
      summaryRevision: conversation.value!.summaryRevision,
      summary: summaryText.value,
      unknowns: unknownsText.value
        .split('\n')
        .map((text) => text.trim())
        .filter(Boolean),
      sql: props.sql,
    })
    if (disposed || version !== epoch) return
    conversation.value = value
    editingSummary.value = false
    suggestion.value = undefined
    appliedVersion.value = -1
    note.value = '总结已保存，请核对并重新确认。'
  })
}
function editSummary() {
  summaryText.value = conversation.value?.summary || ''
  unknownsText.value = conversation.value?.unknowns.join('\n') || ''
  editingSummary.value = true
  summaryExpanded.value = true
}
function applySql() {
  if (!result.value || !canApply.value) return
  applying.value = true
  emit('apply', result.value, baseline.value, (success) => {
    applying.value = false
    if (success) {
      appliedVersion.value = props.contextKey
      nextStep(3)
    }
  })
}
onBeforeUnmount(() => {
  disposed = true
  epoch++
  loadVersion++
  emit('review', undefined)
})
</script>
<template>
  <aside class="sql-runtime ai-sql-panel">
    <div class="ai-panel-heading">
      <h3>
        <Sparkles :size="17" />{{ ['需求对话', '审阅 SQL 建议', '检查和保存'][step - 1]
        }}<HelpTip
          text="对话和需求总结按当前用户及 SQL 草稿保存，重新进入可以继续。模型建议不会自动保存、部署或执行。"
        />
      </h3>
      <div>
        <button v-if="step === 1" class="text-button" @click="emit('toggle-sql')">
          {{ sqlCollapsed ? '展开 SQL' : '收起 SQL' }}</button
        ><button class="text-button" aria-label="收起 AI 助手" @click="emit('close')">
          <X :size="16" />
        </button>
      </div>
    </div>
    <p v-if="localError || error" class="notice error ai-notice" role="alert">
      {{ localError || error }}
    </p>
    <p v-if="note" class="notice ai-notice" role="status">{{ note }}</p>
    <div v-if="!sqlId" class="ai-empty">
      <MessageSquare :size="26" />
      <h4>先创建一份 SQL 草稿</h4>
      <p>确认唯一名称后，即可开始并保存本次对话。</p>
      <button class="button primary" @click="emit('create')">新建 SQL 草稿</button>
    </div>
    <template v-else>
      <div v-if="processing || uncertain" class="ai-task-status" role="status" aria-live="polite">
        <LoaderCircle v-if="!uncertain" class="ai-spinner" :size="16" aria-hidden="true" />
        <span>{{ uncertain ? '任务状态待核对' : activity }}</span
        ><button v-if="uncertain" class="text-button" @click="retry">核对原任务并重试</button
        ><button v-if="busy || uncertain" class="text-button" @click="cancel">
          {{ uncertain ? '重试取消' : '取消' }}
        </button>
      </div>
      <div v-show="step === 1" class="ai-chat-body">
        <div class="ai-chat-settings">
          <AiModelSelect
            v-model="modelId"
            purpose="sql"
            :disabled="blocked"
            @selected="selectedModel = $event"
          />
          <div v-if="selectedModel?.external" class="ai-external">
            <label
              ><input
                v-model="allowExternal"
                type="checkbox"
                :disabled="busy"
              />允许发送本次脱敏上下文</label
            ><HelpTip text="外部模型将读取当前会话和必要的 SQL。不要粘贴明文密码或令牌。" />
          </div>
        </div>
        <div class="ai-conversation-state">
          <span>{{
            loading
              ? '正在恢复会话…'
              : processing
                ? activity
                : task?.state === 'SUCCEEDED'
                  ? 'AI 已回答 · 当前对话已保存'
                  : conversation?.messages.length
                    ? '当前作业对话已保存'
                    : '尚无对话记录'
          }}</span
          ><button class="text-button" :disabled="loading || busy || mutation" @click="load(true)">
            重新加载
          </button>
        </div>
        <div
          ref="thread"
          class="ai-thread"
          aria-label="当前作业 AI 对话"
          aria-live="polite"
          :aria-busy="busy"
        >
          <div v-if="!conversation?.messages.length && !loading" class="ai-empty">
            <MessageSquare :size="25" />
            <h4>{{ sql.trim() ? '从现有 SQL 开始聊' : '从一句需求开始' }}</h4>
            <p>
              {{
                sql.trim()
                  ? '可以先解释和整理现有逻辑，或直接提出修改目标。'
                  : '说明数据来源、输出和业务目标，AI 会继续与你确认细节。'
              }}
            </p>
            <button v-if="sql.trim()" class="button" :disabled="blocked" @click="send(true)">
              解释并整理当前 SQL
            </button>
          </div>
          <article
            v-for="message in conversation?.messages"
            :key="message.id"
            class="ai-chat-message"
            :class="message.role"
          >
            <span class="ai-avatar">{{ message.role === 'user' ? '我' : 'AI' }}</span>
            <div>
              <div class="ai-message-meta">
                {{ message.role === 'user' ? '你' : 'AI 助手'
                }}<span v-if="message.mode === 'error'">报错分析</span>
              </div>
              <AiRichText :text="message.content" />
            </div>
          </article>
          <div v-if="busy" class="ai-waiting" role="status">
            <LoaderCircle class="ai-spinner" :size="16" aria-hidden="true" />AI 正在思考，请稍候…
          </div>
        </div>
        <section class="ai-inline-summary" aria-label="本次会话总结">
          <div class="ai-summary-heading">
            <strong>本次会话总结 · {{ sqlId }}</strong
            ><span class="badge">{{
              !summaryCurrent
                ? '待整理'
                : conversation?.confirmedVersion === conversation?.contentVersion
                  ? '已确认'
                  : '待确认'
            }}</span
            ><HelpTip
              text="总结综合本次会话的全部记录。请核对后确认，再生成或修改 SQL；继续补充或修改 SQL 会使旧确认与建议失效。"
            /><button
              v-if="conversation?.summary"
              class="text-button ai-summary-toggle"
              :aria-expanded="summaryExpanded"
              @click="summaryExpanded = !summaryExpanded"
            >
              <ChevronDown :size="14" :class="{ rotated: summaryExpanded }" />{{
                summaryExpanded ? '收起总结' : '展开总结'
              }}{{
                conversation?.unknowns.length
                  ? ` · 本作业 ${conversation.unknowns.length} 项待核对`
                  : ''
              }}</button
            ><button
              v-if="conversation?.summary && !editingSummary"
              class="text-button"
              @click="summaryLarge = true"
            >
              大视图</button
            ><button
              v-if="conversation?.summary && !editingSummary"
              class="text-button"
              :disabled="blocked"
              @click="editSummary"
            >
              修改总结
            </button>
          </div>
          <div class="ai-summary-content" :class="{ expanded: summaryExpanded || editingSummary }">
            <textarea
              v-if="editingSummary"
              v-model="summaryText"
              aria-label="编辑需求总结"
              rows="4"
              maxlength="8000"
              :disabled="blocked"
            />
            <AiRichText
              v-else
              class="ai-summary-text"
              :text="conversation?.summary || '开始对话后，AI 会将本次会话整理为可核对的需求总结。'"
            />
            <label v-if="editingSummary"
              >本作业待核对事项（每行一项，已解决或不相关项可删除）<textarea
                v-model="unknownsText"
                aria-label="编辑本作业待核对事项"
                rows="4"
                :disabled="blocked"
              />
            </label>
            <ul v-else-if="conversation?.unknowns.length" class="ai-unknowns">
              <li v-for="item in conversation.unknowns" :key="item">待核对：{{ item }}</li>
            </ul>
          </div>
          <div class="ai-summary-footer">
            <span
              ><Check v-if="conversation?.summary" :size="13" />{{
                summaryCurrent ? confirmationHint : '补充或 SQL 变化后需重新整理'
              }}</span
            ><template v-if="editingSummary"
              ><button class="button" :disabled="mutation" @click="editingSummary = false">
                取消修改</button
              ><button
                class="button primary"
                :disabled="blocked || !summaryText.trim()"
                @click="saveSummary"
              >
                保存总结
              </button></template
            ><template v-else
              ><button
                v-if="!summaryCurrent && conversation?.messages.length"
                class="button"
                :disabled="blocked"
                @click="summarize"
              >
                整理本次对话</button
              ><button
                class="button primary"
                :disabled="blocked || !summaryCurrent"
                @click="confirmAndGenerate"
              >
                确认总结并{{ mode === 'error' ? '修改' : '生成' }} SQL<ChevronRight
                  :size="14"
                /></button
            ></template>
          </div>
        </section>
        <div class="ai-composer">
          <div class="ai-compose-modes">
            <button
              class="text-button"
              :class="{ active: mode === 'requirement' }"
              @click="mode = 'requirement'"
            >
              补充需求</button
            ><button
              class="text-button"
              :class="{ active: mode === 'error' }"
              @click="mode = 'error'"
            >
              粘贴报错</button
            ><button class="text-button" :disabled="blocked || !sql.trim()" @click="send(true)">
              解释当前 SQL
            </button>
          </div>
          <textarea
            v-model="input"
            :placeholder="
              mode === 'error'
                ? '粘贴 SQL 报错，并说明期望结果…'
                : '继续补充需求，例如：只统计已支付订单，金额保留两位小数…'
            "
            aria-label="与 AI 对话"
            rows="2"
            maxlength="8000"
            :disabled="!editable || mutation"
            @keydown.ctrl.enter.prevent="send()"
            @keydown.meta.enter.prevent="send()"
          />
          <div class="ai-compose-footer">
            <span>可随时补充 · Ctrl / ⌘ + Enter 发送</span
            ><button class="button primary" :disabled="blocked || !input.trim()" @click="send()">
              发送<ArrowUp :size="14" />
            </button>
          </div>
        </div>
      </div>
      <section v-show="step === 2" class="ai-review-body">
        <p class="muted">先审阅完整 SQL，再显式应用。生成不会修改当前编辑器。</p>
        <h4>已确认的需求</h4>
        <p class="ai-summary-text">{{ conversation?.summary }}</p>
        <AiResultReport v-if="result" :result="result" />
        <p v-if="!canApply && !applied" class="notice">
          SQL、配置或需求已变化，旧建议只能审阅，请返回对话重新确认。
        </p>
        <div class="ai-review-actions">
          <button class="button" @click="nextStep(1)">继续补充需求</button
          ><button class="button" @click="showOriginal = !showOriginal">
            {{ showOriginal ? '查看建议 SQL' : '查看当前 SQL' }}</button
          ><button class="button" @click="reviewDialog?.showModal()">完整对比</button
          ><button class="button primary" :disabled="!canApply" @click="applySql">
            应用建议 SQL<ChevronRight :size="14" />
          </button>
        </div>
      </section>
      <section v-show="step === 3" class="ai-check-body">
        <p class="notice">建议已应用到当前编辑器，尚未部署。继续补充或编辑后需重新检查。</p>
        <div class="ai-check-row">
          <div>
            <strong>SQL 检查</strong>
            <p>{{ checkSummary || '按所选 Flink 版本检查语法' }}</p>
          </div>
          <button class="button" :disabled="!editable || busy" @click="emit('check')">
            检查 SQL
          </button>
        </div>
        <div class="ai-check-row">
          <div>
            <strong>SQL 验证</strong>
            <p>可选；使用已有验证专用 Session</p>
          </div>
          <button class="button" :disabled="!canVerify || !editable || busy" @click="emit('run')">
            验证结果
          </button>
        </div>
        <div class="ai-check-row">
          <div>
            <strong>保存草稿</strong>
            <p>{{ dirty ? '当前 SQL 或配置有未保存改动' : '草稿已保存' }}</p>
          </div>
          <button class="button primary" :disabled="!editable || busy" @click="emit('save')">
            保存 SQL 草稿
          </button>
        </div>
        <p class="muted">保存不自动发布或启动作业。</p>
        <button class="text-button" @click="nextStep(1)">返回对话继续补充</button>
      </section>
    </template>
    <dialog ref="reviewDialog" class="ai-full-review">
      <div class="field-heading">
        <h3>完整 SQL 对比</h3>
        <button class="text-button" aria-label="关闭完整对比" @click="reviewDialog?.close()">
          <X :size="16" />
        </button>
      </div>
      <CodeDiff
        class="ai-full-diff"
        :before="sql"
        :after="result?.sql || ''"
        before-label="当前 SQL 完整审阅"
        after-label="建议 SQL 完整审阅"
      />
    </dialog>
    <AiContentDialog v-if="summaryLarge" title="作业总结" @close="summaryLarge = false"
      ><AiRichText :text="conversation?.summary || ''"
    /></AiContentDialog>
  </aside>
</template>
<style scoped>
.ai-full-diff {
  height: 70dvh;
}
.ai-sql-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  padding: 12px !important;
}
.ai-panel-heading,
.ai-panel-heading h3,
.ai-panel-heading > div {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
}
.ai-panel-heading {
  justify-content: space-between;
  flex-shrink: 0;
}
.ai-panel-heading h3 {
  font-size: var(--font-section);
}
.ai-panel-heading > div {
  gap: 12px;
  font-size: var(--font-small);
}
.ai-notice {
  margin: 0;
  padding: 8px 10px;
  font-size: var(--font-small);
  white-space: pre-wrap;
}
.ai-chat-body {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto auto;
  gap: 8px;
  flex: 1;
  min-height: 0;
}
.ai-chat-settings {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 18px;
  border-bottom: 1px solid var(--line);
  padding-bottom: 9px;
  flex-shrink: 0;
}
.ai-chat-settings :deep(.ai-model-field) {
  flex: 0 1 auto;
  align-items: center;
  min-width: 180px;
  margin: 0;
}
.ai-chat-settings :deep(.ai-model-field label) {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: none;
  white-space: nowrap;
}
.ai-chat-settings :deep(select) {
  width: 210px;
  max-width: 100%;
}
.ai-external {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: var(--font-caption) !important;
  margin: 0 !important;
  flex-wrap: wrap;
  min-height: 34px;
}
.ai-external > label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  font-weight: 400;
  font-size: var(--font-caption);
}
.ai-external :deep(.help-tip) {
  display: inline-flex;
  align-items: center;
  flex: none;
}
.ai-external input {
  width: auto;
}
.ai-conversation-state {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
  font-size: var(--font-caption);
  color: var(--muted);
  flex-shrink: 0;
}
.ai-thread {
  flex: 1;
  min-height: 160px;
  overflow: auto;
  overscroll-behavior: contain;
  display: flex;
  flex-direction: column;
  gap: 15px;
  padding: 2px 5px 6px 0;
  scrollbar-width: thin;
}
.ai-chat-message {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr);
  gap: 8px;
  flex-shrink: 0;
}
.ai-avatar {
  width: 26px;
  height: 26px;
  border-radius: 6px;
  background: var(--surface-soft, var(--color-surface-soft));
  display: grid;
  place-items: center;
  font-size: var(--font-caption);
  color: var(--muted);
}
.assistant .ai-avatar {
  color: var(--blue);
  background: var(--color-primary-light, var(--color-primary-soft));
}
.ai-message-meta {
  display: flex;
  gap: 8px;
  font-size: var(--font-caption);
  color: var(--muted);
  margin: 0 0 5px;
}
.ai-chat-message p {
  margin: 0;
  font-size: var(--font-small);
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.user p {
  background: var(--surface-soft, var(--color-surface-soft));
  padding: 9px 11px;
  border-radius: 0 7px 7px 7px;
}
.ai-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 10px;
  text-align: center;
  padding: 24px 12px;
  flex: 1;
  font-size: var(--font-small);
  color: var(--muted);
}
.ai-empty h4,
.ai-empty p {
  margin: 0;
}
.ai-empty h4 {
  color: var(--text);
}
.ai-inline-summary {
  flex-shrink: 0;
  background: var(--surface-soft, var(--color-surface-soft));
  border: 1px solid var(--line);
  border-radius: 7px;
  padding: 6px 9px;
  min-width: 0;
}
.ai-summary-heading {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
  font-size: var(--font-small);
  margin-bottom: 3px;
}
.ai-summary-heading > .ai-summary-toggle {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.ai-summary-toggle .rotated {
  transform: rotate(180deg);
}
.ai-summary-content {
  max-height: 22px;
  overflow: hidden;
}
.ai-summary-content.expanded {
  max-height: clamp(100px, 22vh, 200px);
  overflow: auto;
  scrollbar-width: thin;
}
.ai-summary-content:not(.expanded) .ai-summary-text {
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.ai-summary-content textarea {
  min-height: 82px;
  resize: vertical;
}
.ai-summary-heading .badge {
  font-size: 10px;
  padding: 1px 5px;
}
.ai-summary-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: var(--font-small);
  line-height: 1.65;
  max-height: none;
  overflow: visible;
  margin: 0;
  scrollbar-width: thin;
}
.ai-summary-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 4px;
}
.ai-summary-footer > span {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--muted);
  font-size: var(--font-caption);
  margin-right: auto;
  flex: 1 1 200px;
}
.ai-summary-footer .button {
  min-height: 30px;
  font-size: var(--font-small);
  padding: 6px 10px;
}
.ai-unknowns {
  font-size: var(--font-small);
  color: var(--color-warning);
  margin: 7px 0 0;
  padding-left: 18px;
}
.ai-composer {
  flex-shrink: 0;
  border: 1px solid var(--line);
  border-radius: 7px;
  padding: 8px 10px;
  background: var(--surface, var(--color-surface));
}
.ai-compose-modes {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 5px;
  font-size: var(--font-small);
}
.ai-compose-modes .active {
  color: var(--blue);
  font-weight: 600;
}
.ai-composer textarea {
  border: 0;
  padding: 3px 0;
  height: 58px;
  min-height: 58px;
  max-height: 58px;
  resize: none;
  font-size: var(--font-small);
  line-height: 1.6;
}
.ai-compose-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.ai-compose-footer > span {
  font-size: var(--font-caption);
  color: var(--muted);
}
.ai-compose-footer .button {
  padding: 6px 10px;
  min-height: 30px;
  font-size: var(--font-small);
}
.ai-task-status {
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--surface-soft, var(--color-surface-soft));
  padding: 8px 10px;
  font-size: var(--font-small);
  flex-shrink: 0;
}
.ai-spinner {
  animation: ai-spin 1s linear infinite;
  flex-shrink: 0;
}
@keyframes ai-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (min-width: 1001px) and (max-height: 800px) {
  .ai-inline-summary {
    padding: 7px 9px;
  }
  .ai-summary-content:not(.expanded) {
    max-height: 20px;
  }
  .ai-summary-content:not(.expanded) .ai-summary-text {
    -webkit-line-clamp: 1;
  }
  .ai-summary-heading {
    margin-bottom: 4px;
  }
  .ai-composer textarea {
    height: 38px;
    min-height: 38px;
    max-height: 38px;
  }
}
.ai-waiting {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 0;
  color: var(--blue);
  font-size: var(--font-small);
  flex-shrink: 0;
}
.ai-task-status > span {
  margin-right: auto;
}
.ai-review-body,
.ai-check-body {
  min-height: 0;
  overflow: auto;
  font-size: var(--font-small);
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.ai-review-body h4 {
  margin: 0;
}
.ai-review-actions {
  display: flex;
  flex-direction: column;
  gap: 9px;
  margin-top: 4px;
}
.ai-check-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 0;
  border-bottom: 1px solid var(--line);
}
.ai-check-row p {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: var(--font-small);
}
.ai-full-review {
  width: min(1250px, 94vw);
  max-width: 94vw;
  height: 85vh;
  max-height: 85vh;
  border: 1px solid var(--line);
  border-radius: 9px;
  padding: 18px;
  color: var(--text);
  background: var(--surface, var(--color-surface));
}
.ai-full-review::backdrop {
  background: var(--color-overlay);
}
.ai-comparison {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 14px;
  height: calc(100% - 46px);
}
.ai-comparison > section {
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.ai-comparison h4 {
  margin: 0 0 8px;
  font-size: var(--font-small);
}
.ai-comparison :deep(.code-editor) {
  flex: 1;
  min-height: 0;
}
@media (max-width: 1000px) {
  .ai-sql-panel {
    height: 720px;
    min-height: 620px;
  }
  .ai-thread {
    min-height: 0;
    max-height: none;
  }
  .ai-full-review {
    height: 88vh;
  }
}
@media (max-width: 600px) {
  .ai-sql-panel {
    padding: 12px !important;
    height: 760px;
  }
  .ai-panel-heading {
    flex-wrap: wrap;
  }
  .ai-summary-footer .primary {
    width: 100%;
  }
  .ai-comparison {
    grid-template-columns: minmax(0, 1fr);
    height: auto;
  }
  .ai-comparison :deep(.code-editor) {
    height: 330px;
    flex: none;
  }
  .ai-compose-footer > span {
    font-size: var(--font-caption);
  }
  .ai-chat-settings {
    align-items: flex-start;
  }
  .ai-chat-settings :deep(.ai-model-field) {
    width: 100%;
  }
  .ai-chat-settings :deep(.ai-model-field label) {
    width: 100%;
  }
  .ai-chat-settings :deep(select) {
    min-width: 0;
    flex: 1;
    width: 0;
  }
  .ai-summary-toggle {
    font-size: var(--font-caption);
  }
}
</style>
