<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { HarnessInteraction } from '../api/harness'
import { requestId } from '../../../shared/utils/requestId'
const props = defineProps<{ card: HarnessInteraction; enabled: boolean; working: boolean }>()
const emit = defineEmits<{ respond: [input: Record<string, unknown>] }>()
const answers = ref<Record<string, string>>({})
const pending = ref<Record<string, unknown>>()
const preferenceLabels: Record<string, string> = {
  responseStyle: '回答方式',
  defaultCatalogId: '常用 Catalog 编号',
  defaultSessionId: '常用 Session 编号',
  sqlStyle: 'SQL 编写习惯',
  logLines: '常用日志行数',
}
watch(
  () => props.card.id,
  () => {
    answers.value = {}
    pending.value = undefined
  },
)
const available = computed(() => props.enabled && props.card.state === 'PENDING')
const valid = computed(() => {
  if (answers.value.note?.trim()) return true
  if (props.card.kind === 'choice') return !!answers.value.choice
  return props.card.questions.every((_, index) => !!answers.value[`q${index}`]?.trim())
})
function submit(decision: 'submit' | 'cancel') {
  if (!available.value || props.working) return
  if (!pending.value)
    pending.value = {
      requestId: requestId(),
      interactionId: props.card.id,
      decision,
      answers: { ...answers.value },
      confirmedVerification: props.card.kind === 'verification',
      confirmedApi: props.card.kind === 'api' && decision === 'submit',
      confirmedPreferences: props.card.kind === 'preferences' && decision === 'submit',
    }
  emit('respond', pending.value)
}
</script>

<template>
  <section
    class="harness-interaction"
    :aria-label="card.kind === 'preferences' ? '确认对话偏好' : '需要你的补充'"
    :aria-busy="working"
  >
    <strong>{{ card.title }}</strong>
    <pre v-if="card.kind === 'api'" class="harness-api-request">{{ card.scope }}</pre>
    <p v-else-if="card.scope">
      {{ card.scope }} <span v-if="card.model">当前模型：{{ card.model }}。</span>
    </p>
    <template v-if="card.kind === 'preferences'">
      <p v-if="card.sourceQuote">你的要求：{{ card.sourceQuote }}</p>
      <p v-if="card.action === 'clear'">将清空以下已保存的设定：</p>
      <dl class="harness-preference-summary">
        <template
          v-for="(value, key) in card.action === 'clear' ? card.previousValues : card.changes"
          :key="key"
        >
          <dt>{{ preferenceLabels[key] || key }}</dt>
          <dd>
            {{ value
            }}<small v-if="card.action !== 'clear' && card.previousValues?.[key]"
              >原设定：{{ card.previousValues[key] }}</small
            >
          </dd>
        </template>
      </dl>
    </template>
    <template v-if="available">
      <form @submit.prevent="submit('submit')">
        <label
          v-for="(question, index) in card.questions"
          :key="index"
          class="harness-interaction-field"
        >
          {{ question }}
          <input v-model="answers[`q${index}`]" maxlength="2000" :disabled="working || !!pending" />
        </label>
        <fieldset v-if="card.kind === 'choice'" :disabled="working || !!pending">
          <legend>请选择，也可在下方补充</legend>
          <label v-for="option in card.options" :key="option" class="harness-check">
            <input v-model="answers.choice" type="radio" :value="option" />{{ option }}
          </label>
        </fieldset>
        <label
          v-if="card.kind !== 'preferences' && card.kind !== 'api'"
          class="harness-interaction-field"
          >补充说明
          <textarea
            v-model="answers.note"
            rows="2"
            maxlength="2000"
            :disabled="working || !!pending"
            placeholder="可补充条件、修改范围或填写其他答案"
          />
        </label>
        <div class="harness-interaction-actions">
          <button class="primary" :disabled="working || (!valid && card.kind !== 'logs')">
            {{
              pending
                ? '重试原提交'
                : card.kind === 'logs'
                  ? '继续分析'
                  : card.kind === 'verification'
                    ? card.action === 'cancel'
                      ? '确认取消验证'
                      : '确认运行验证'
                    : card.kind === 'preferences'
                      ? card.action === 'clear'
                        ? '确认清空'
                        : '确认保存'
                      : card.kind === 'api'
                        ? '确认执行此请求'
                        : '提交并继续'
            }}
          </button>
          <button type="button" :disabled="working || !!pending" @click="submit('cancel')">
            {{
              card.kind === 'logs'
                ? '取消本次交互'
                : card.kind === 'preferences'
                  ? '暂不保存'
                  : '取消本次交互'
            }}
          </button>
        </div>
        <small v-if="card.kind === 'preferences'"
          >点击确认后才会保存；直接发送消息可调整本次设定。</small
        >
        <small v-else
          >提交后会继续原任务。{{
            card.kind === 'logs'
              ? '当前只读日志可直接按需查询，无需额外授权。'
              : '也可以在对话输入框中直接补充。'
          }}</small
        >
      </form>
    </template>
    <template v-else>
      <p>
        {{
          card.state === 'ANSWERED'
            ? card.kind === 'preferences'
              ? card.action === 'clear'
                ? '偏好已清空'
                : '偏好已保存，将用于后续对话'
              : '已提交，已继续原任务'
            : card.state === 'CANCELED'
              ? card.kind === 'preferences'
                ? '未保存本次设定'
                : '已取消'
              : '此交互已失效，请查看最新对话'
        }}
      </p>
      <p v-if="card.verificationResult">
        验证状态：{{ card.verificationResult.state }} {{ card.verificationResult.message }}
      </p>
      <ul v-if="card.answers">
        <li v-for="(value, key) in card.answers" :key="key">
          {{
            key === 'choice'
              ? '选择'
              : key === 'note'
                ? '补充'
                : card.questions[Number(String(key).slice(1))]
          }}：{{ value }}
        </li>
      </ul>
    </template>
  </section>
</template>

<style scoped>
.harness-api-request {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 360px;
  overflow: auto;
  font-size: 12px;
}
</style>
