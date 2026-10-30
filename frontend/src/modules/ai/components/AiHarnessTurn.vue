<script setup lang="ts">
import { computed, ref } from 'vue'
import AiRichText from './AiRichText.vue'
import CodeDiff from '../../../shared/components/editor/CodeDiff.vue'
import { activeHarnessTurn, type HarnessTurn } from '../api/harness'
import { requestId } from '../../../shared/utils/requestId'

const props = defineProps<{
  turn: HarnessTurn
  editable: boolean
  working: boolean
  latest: boolean
}>()
const emit = defineEmits<{
  apply: [turn: HarnessTurn, input: Record<string, unknown>]
  observe: [turn: HarnessTurn]
  requirements: [turn: HarnessTurn]
}>()
const mode = ref<'save' | 'restart'>('save'),
  restoreMode = ref(''),
  savepoint = ref(''),
  impact = ref(false)
const confirmed = ref(false),
  pending = ref<Record<string, unknown>>()
const candidate = computed(() => props.turn.candidate)
const steps = computed(() =>
  Array.isArray(props.turn.plan)
    ? props.turn.plan
    : ['核对本轮上下文及执行范围。', '按需使用工具并检查结果。', '交付结论及需要审阅的候选。'],
)
const needsRecovery = computed(
  () =>
    ['SUBMISSION_UNKNOWN', 'APPLYING'].includes(props.turn.state) ||
    ['UNKNOWN', 'SAVING', 'SUBMITTING'].includes(props.turn.application?.state || ''),
)
const canApply = computed(
  () =>
    props.editable &&
    props.latest &&
    ['AWAITING_APPROVAL', 'BUDGET_EXHAUSTED'].includes(props.turn.state) &&
    !props.turn.applicationId,
)
const sqlOnly = computed(() => candidate.value?.kind === 'sql')
const stateText = computed(
  () =>
    (
      ({
        QUEUED: '已排队',
        RUNNING: '执行中',
        SUCCEEDED: '分析完成',
        AWAITING_APPROVAL: '等待审阅',
        SUPERSEDED: '旧方案已失效',
        CANCELED: '已取消',
        FAILED: '本轮未完成',
        INTERRUPTED: '已中断',
        BUDGET_EXHAUSTED: '已达到本轮预算',
        APPLYING: '正在应用',
        SUBMISSION_UNKNOWN: '原提交待核对',
        APPLIED: '修改已保存',
        WAITING_OBSERVATION: '已受理，待核验',
        RUNNING_OBSERVED: '已核验对应修订运行中',
      }) as Record<string, string>
    )[props.turn.state] || props.turn.state,
)
function apply() {
  if (!confirmed.value || props.working) return
  if (!pending.value)
    pending.value = {
      requestId: requestId(),
      confirmed: true,
      mode: sqlOnly.value ? 'save' : mode.value,
      restoreMode: restoreMode.value,
      savepointPath: savepoint.value.trim(),
      acceptImpact: impact.value,
    }
  emit('apply', props.turn, pending.value)
}
function retryOriginal() {
  const original = props.turn.application?.request || pending.value
  if (original) emit('apply', props.turn, original)
}
</script>

<template>
  <article class="harness-turn" :data-turn-id="turn.id">
    <div class="harness-user">
      <p>{{ turn.content }}</p>
    </div>
    <div class="harness-speaker">
      Flink AI <span>{{ stateText }}</span>
    </div>
    <p class="harness-plan-intro">我会按以下计划处理：</p>
    <ol class="harness-plan">
      <li v-for="(step, index) in steps" :key="index">{{ step }}</li>
    </ol>
    <details class="harness-process">
      <summary>
        {{ activeHarnessTurn(turn) ? '执行中' : '已记录' }} ·
        {{ turn.events.length }} 项过程，点击查看
      </summary>
      <details>
        <summary>本轮上下文与执行约束</summary>
        <pre>{{ JSON.stringify(turn.contextManifest, null, 2) }}</pre>
      </details>
      <details v-for="event in turn.events" :key="event.sequence" class="harness-event">
        <summary>
          {{ event.title }} <small>{{ new Date(event.at).toLocaleTimeString() }}</small>
        </summary>
        <pre>{{ JSON.stringify(event.detail, null, 2) }}</pre>
      </details>
    </details>
    <AiRichText v-if="turn.reply" :text="turn.reply" />
    <p v-if="activeHarnessTurn(turn)" class="harness-activity" role="status">
      {{
        turn.state === 'QUEUED'
          ? '等待处理…'
          : turn.state === 'APPLYING'
            ? '正在执行已确认操作…'
            : '正在取证和分析…'
      }}
    </p>
    <p v-if="turn.message" class="harness-turn-notice">{{ turn.message }}</p>
    <section v-if="turn.requirements && !turn.approvedRequirements" class="harness-requirements">
      <p><strong>请核对本轮需求</strong></p>
      <AiRichText :text="turn.requirements.summary" />
      <ul v-if="turn.requirements.unknowns.length">
        <li v-for="unknown in turn.requirements.unknowns" :key="unknown">待核对：{{ unknown }}</li>
      </ul>
      <button
        v-if="latest && !activeHarnessTurn(turn)"
        :disabled="working"
        @click="emit('requirements', turn)"
      >
        确认已知范围并生成候选
      </button>
      <small>未知事项仍保留，不会作为已回答条件；外部模型需要勾选本轮授权。</small>
    </section>
    <section v-if="candidate" class="harness-candidate">
      <p v-if="candidate.newDraft && !turn.applicationId">
        批准后新建并绑定 SQL 草稿：<strong>{{ candidate.sqlName }}</strong
        >；当前尚未创建。
      </p>
      <p>
        <strong>{{ turn.applicationId ? '本次审阅方案' : '已生成候选，尚未应用' }}</strong>
      </p>
      <p class="muted">{{ candidate.check }}</p>
      <details>
        <summary>审阅完整修改</summary>
        <CodeDiff
          v-if="candidate.kind === 'sql'"
          :before="candidate.beforeSql || ''"
          :after="candidate.sql || ''"
          language="sql"
        />
        <CodeDiff
          v-else
          :before="candidate.beforeYaml || ''"
          :after="candidate.afterYaml || ''"
          language="yaml"
        />
      </details>
      <div v-if="canApply" class="harness-approval">
        <div class="harness-approval-fields">
          <label v-if="!sqlOnly"
            >执行方式<select v-model="mode" :disabled="working || !!pending">
              <option value="save">仅保存参数</option>
              <option value="restart">保存、发布并启动 / 重启</option>
            </select></label
          >
          <label v-if="!sqlOnly && mode === 'restart'"
            >恢复方式<select v-model="restoreMode" :disabled="working || !!pending">
              <option value="">请选择恢复方式</option>
              <option value="savepoint">保存点恢复</option>
              <option value="last-state">last-state（需要 HA）</option>
              <option value="stateless">无状态（不恢复历史状态）</option>
            </select></label
          >
          <label v-if="mode === 'restart' && restoreMode === 'savepoint'"
            >保存点路径<input
              v-model="savepoint"
              :disabled="working || !!pending"
              placeholder="留空仅适用于当前作业正常运行时创建保存点"
          /></label>
        </div>
        <label v-if="!sqlOnly && mode === 'restart'" class="harness-check"
          ><input
            v-model="impact"
            type="checkbox"
            :disabled="working || !!pending"
          />我接受重启中断、恢复方式及处理进度影响；失败时不自动降级恢复方式。</label
        >
        <label class="harness-check"
          ><input
            v-model="confirmed"
            type="checkbox"
            :disabled="working"
          />我已审阅完整差异，确认只执行本次方案。</label
        >
        <button
          class="primary"
          :disabled="
            working || !confirmed || (!sqlOnly && mode === 'restart' && (!restoreMode || !impact))
          "
          @click="apply"
        >
          {{
            pending
              ? '核对并继续原申请'
              : sqlOnly
                ? '确认保存 SQL 草稿'
                : mode === 'restart'
                  ? '确认修改并启动 / 重启'
                  : '确认保存参数'
          }}
        </button>
        <small>仅保存不会发布或运行；会重新核验角色、修订和资源身份。</small>
      </div>
      <p v-else-if="turn.state === 'SUPERSEDED'" class="muted">已有新一轮对话，请审阅最新方案。</p>
    </section>
    <section v-if="turn.applicationId" class="harness-application">
      <p>
        <strong>{{
          turn.application?.message || '原申请已登记，请先核对结果，勿另起新申请。'
        }}</strong>
      </p>
      <p v-if="turn.application?.state === 'ACCEPTED'">运行请求已受理，尚不能判定已运行成功。</p>
      <p v-if="turn.observation">
        {{ turn.observation.message }} · 当前 {{ turn.observation.jobState }}<br />{{
          turn.observation.runningRevisionConfirmed
            ? '已核验对应修订运行中；业务结果仍需单独验收。'
            : '缺少本次修订运行成功的完整证明。'
        }}
      </p>
      <div class="harness-actions">
        <button :disabled="working" @click="emit('observe', turn)">
          {{
            needsRecovery
              ? '核对原申请'
              : sqlOnly || turn.application?.mode === 'save'
                ? '核对保存结果'
                : '手动刷新运行结果'
          }}</button
        ><button
          v-if="needsRecovery && editable && (turn.application?.request || pending)"
          :disabled="working"
          @click="retryOriginal"
        >
          继续原申请
        </button>
      </div>
      <small>使用原申请及操作号，不重新保存或部署；刷新间隔至少三秒。</small>
    </section>
  </article>
</template>
