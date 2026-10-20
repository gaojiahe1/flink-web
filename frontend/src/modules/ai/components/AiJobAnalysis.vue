<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { X, Sparkles, LoaderCircle, ArrowUp, Check, RefreshCw } from '@lucide/vue'
import AiModelSelect from './AiModelSelect.vue'
import CodeEditor from '../../../shared/components/editor/CodeEditor.vue'
import { aiApi, type AiJobConversation, type AiJobApplication, type AiModel } from '../api/ai'
import { useAiTask } from '../composables/useAiTask'
import { requestId } from '../../../shared/utils/requestId'
import { ApiError } from '../../../shared/api/http'

const props = defineProps<{
  job: { id: string; name: string }
  error?: string
  canReadLogs: boolean
}>()
const emit = defineEmits<{ close: []; changed: [] }>()
const dialog = ref<HTMLDialogElement>(),
  messages = ref<HTMLElement>()
const conversation = ref<AiJobConversation>(),
  loading = ref(true),
  saving = ref(false),
  applying = ref(false)
const modelId = ref(''),
  model = ref<AiModel>(),
  allowExternal = ref(false),
  includeLogs = ref(true)
const instruction = ref(''),
  failure = ref(''),
  notice = ref(''),
  editingSummary = ref(false),
  summaryText = ref('')
const file = ref<'yaml' | 'sql'>('yaml'),
  mode = ref<'save' | 'restart'>('save'),
  restoreMode = ref('')
const savepointPath = ref(''),
  confirmed = ref(false),
  acceptImpact = ref(false)
const application = ref<AiJobApplication>()
const pendingApplication = ref<{ planId: string; input: Record<string, unknown> }>()
const { task, busy, error: taskError, uncertain, retry, start, cancel, resume } = useAiTask()
let disposed = false
const working = computed(() => busy.value || saving.value || applying.value || loading.value)
const plan = computed(() => conversation.value?.plan)
const ready = computed(
  () => !!modelId.value && (!model.value?.external || allowExternal.value) && !working.value,
)
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
onMounted(async () => {
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
})
watch(modelId, () => {
  allowExternal.value = false
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
    if (state === 'SUCCEEDED') notice.value = 'AI 已回答；建议尚未应用。'
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
    })
    allowExternal.value = false
  } catch (e) {
    failure.value = (e as Error).message
  } finally {
    saving.value = false
  }
}
function editSummary() {
  summaryText.value = conversation.value?.summary || ''
  editingSummary.value = true
}
async function saveSummary() {
  if (!conversation.value || working.value) return
  saving.value = true
  failure.value = ''
  try {
    conversation.value = await aiApi.jobSummary(props.job.id, {
      revision: conversation.value.revision,
      summary: summaryText.value,
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
    await reload()
    emit('changed')
  } catch (e) {
    try {
      application.value = await aiApi.jobApplication(props.job.id, String(pending.input.requestId))
      pendingApplication.value = undefined
      await reload()
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
    :aria-label="'AI 作业助手：' + job.name"
    @cancel.prevent="emit('close')"
  >
    <header class="field-heading ai-job-heading">
      <div>
        <h3><Sparkles :size="18" /> AI 作业助手</h3>
        <p class="muted">{{ job.name }} · 分析原因、持续沟通、审阅修复</p>
      </div>
      <button class="icon-button" aria-label="关闭 AI 助手" @click="emit('close')">
        <X :size="19" />
      </button>
    </header>
    <p v-if="error" class="notice error ai-prewrap ai-job-current-error">{{ error }}</p>
    <div class="ai-job-settings">
      <AiModelSelect
        v-model="modelId"
        purpose="diagnosis"
        :disabled="working"
        @selected="model = $event"
      />
      <label v-if="canReadLogs" class="ai-check"
        ><input
          v-model="includeLogs"
          :disabled="working"
          type="checkbox"
        />本轮包含受限脱敏日志</label
      >
      <label v-if="model?.external" class="ai-check ai-external"
        ><input
          v-model="allowExternal"
          :disabled="working"
          type="checkbox"
        />允许本次将总结、近期对话及脱敏 SQL、配置、事件与所选日志发送到此外部模型</label
      >
    </div>
    <p v-if="loading" class="muted" role="status">
      <LoaderCircle :size="14" class="ai-job-spin" /> 正在恢复本作业对话…
    </p>
    <div v-else class="ai-job-body" :class="{ 'ai-job-has-plan': !!plan }">
      <section class="ai-job-conversation" aria-label="诊断对话">
        <details class="ai-job-summary" :open="editingSummary">
          <summary>
            当前总结
            <span class="muted">{{
              conversation?.compressedMessages
                ? `· 已归纳 ${conversation.compressedMessages} 条较早消息`
                : '· 每次对话自动带入'
            }}</span>
          </summary>
          <template v-if="!editingSummary"
            ><p class="ai-prewrap">
              {{
                conversation?.summary ||
                '开始分析后，AI 会总结已知事实、用户约束、尝试结果和待核对事项。'
              }}
            </p>
            <button
              v-if="conversation?.summary"
              class="text-button"
              :disabled="working"
              @click="editSummary"
            >
              修正总结
            </button></template
          >
          <template v-else
            ><label>核对后的总结<textarea v-model="summaryText" rows="5" maxlength="4000" /></label>
            <div class="toolbar-actions">
              <button
                class="button primary"
                :disabled="working || !summaryText.trim()"
                @click="saveSummary"
              >
                保存总结</button
              ><button class="button" :disabled="working" @click="editingSummary = false">
                取消
              </button>
            </div></template
          >
          <ul v-if="conversation?.unknowns.length" class="muted">
            <li v-for="item in conversation.unknowns" :key="item">待核对：{{ item }}</li>
          </ul>
        </details>
        <div ref="messages" class="ai-job-messages" aria-label="近期对话">
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
            <p class="ai-prewrap">{{ item.content }}</p>
          </article>
          <p v-if="busy" class="ai-job-progress" role="status">
            <LoaderCircle :size="15" class="ai-job-spin" /> 正在收集证据并分析，可继续查看已有对话…
          </p>
        </div>
        <details v-if="conversation?.evidence" class="ai-job-evidence">
          <summary>
            本轮证据 · {{ new Date(conversation.evidence.queriedAt).toLocaleTimeString() }}
            <span class="muted">· 展开查看来源</span>
          </summary>
          <p class="muted">
            草稿 r{{ conversation.evidence.baseline.jobRevision
            }}<template v-if="conversation.evidence.baseline.sqlRevision">
              · SQL r{{ conversation.evidence.baseline.sqlRevision }}</template
            >
            ·
            {{
              conversation.evidence.resourceExists
                ? '已核对当前资源归属'
                : '运行证据不完整或资源不存在'
            }}
          </p>
          <p v-for="item in conversation.evidence.unavailable" :key="item" class="notice">
            {{ item }}
          </p>
          <details>
            <summary>部署配置投影与 Pod / 事件</summary>
            <pre>{{
              JSON.stringify(
                {
                  draft: conversation.evidence.draftConfiguration,
                  runtime: conversation.evidence.runtimeConfiguration,
                  diagnostics: conversation.evidence.diagnostics,
                },
                null,
                2,
              )
            }}</pre>
          </details>
          <details v-for="log in conversation.evidence.logs" :key="log.pod + log.container">
            <summary>
              {{ log.pod }} / {{ log.container }} {{ log.previous ? '· 上次实例' : '' }}
            </summary>
            <pre>{{ log.text }}</pre>
          </details>
        </details>
        <div class="ai-job-input">
          <label for="ai-job-question">问题或补充说明</label
          ><textarea
            id="ai-job-question"
            v-model="instruction"
            :disabled="working || !!pendingApplication"
            maxlength="8000"
            rows="3"
            placeholder="补充现象、说明不能改变的业务逻辑，或提出修复要求…"
          />
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
              生成修复方案</button
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
      <section v-if="plan" class="ai-job-plan" aria-label="修复方案审阅">
        <h4>修复方案与完整对比</h4>
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
        </div>
        <div class="ai-job-comparison">
          <section>
            <h5>当前{{ file === 'sql' ? ' SQL' : ' YAML' }}（完整）</h5>
            <CodeEditor :model-value="currentText" :language="file" readonly label="当前完整内容" />
          </section>
          <section>
            <h5>建议{{ file === 'sql' ? ' SQL' : ' YAML' }}（完整）</h5>
            <CodeEditor
              :model-value="proposedText"
              :language="file"
              readonly
              label="建议完整内容"
            />
          </section>
        </div>
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
        按需取证，不持续监控。总结与近期对话保存在本作业下；作业删除后清理关联 AI 数据。
      </p>
    </footer>
  </dialog>
</template>

<style scoped>
.ai-analysis-drawer {
  box-sizing: border-box;
  position: fixed;
  inset: 0 0 0 auto;
  margin: 0 0 0 auto;
  width: min(1180px, 94vw);
  height: 100dvh;
  max-height: 100dvh;
  max-width: 100vw;
  border: 0;
  background: var(--color-surface);
  color: var(--color-text);
  padding: 20px 24px 12px;
  box-shadow: -5px 0 24px #1522381a;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
}
.ai-analysis-drawer::backdrop {
  background: #15223866;
}
.ai-job-heading {
  flex: none;
}
.ai-job-heading h3 {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
}
.ai-job-heading p {
  margin: 5px 0 0;
}
.ai-job-settings {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 20px;
  flex: none;
}
.ai-job-settings :deep(.ai-model-field) {
  flex: 1;
  min-width: 220px;
}
.ai-external {
  flex-basis: 100%;
}
.ai-check {
  display: flex;
  align-items: flex-start;
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
  min-height: 0;
  flex: 1;
  gap: 20px;
}
.ai-job-has-plan {
  grid-template-columns: minmax(280px, 0.9fr) minmax(0, 1.1fr);
}
.ai-job-conversation {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  gap: 12px;
}
.ai-job-summary,
.ai-job-evidence {
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 12px;
  overflow: auto;
  max-height: 30%;
  flex: none;
}
.ai-job-summary {
  background: var(--color-background);
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
  min-height: 120px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 4px 3px;
}
.ai-job-message {
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
  background: #eef4ff;
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
  font-size: 12px;
  color: var(--color-primary);
}
.ai-job-input {
  border-top: 1px solid var(--line);
  padding-top: 12px;
  flex: none;
}
.ai-job-input label {
  font-size: 12px;
}
.ai-job-input textarea {
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
  border-left: 1px solid var(--line);
  padding-left: 20px;
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
  height: 260px;
  font-size: 11px;
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
  padding-top: 8px;
}
.ai-job-footer p {
  margin: 4px 0;
}
.ai-job-current-error {
  max-height: 80px;
  overflow: auto;
  flex: none;
  margin: 0;
}
.ai-job-spin {
  animation: ai-job-spin 1s linear infinite;
  flex: none;
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
@media (max-width: 900px) {
  .ai-analysis-drawer {
    width: 100%;
    padding: 16px;
  }
  .ai-job-has-plan {
    grid-template-columns: 1fr;
    overflow: auto;
  }
  .ai-job-has-plan .ai-job-conversation {
    min-height: 520px;
  }
  .ai-job-plan {
    overflow: visible;
    border-left: 0;
    border-top: 1px solid var(--line);
    padding: 16px 0;
  }
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
