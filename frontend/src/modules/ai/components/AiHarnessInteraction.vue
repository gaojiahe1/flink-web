<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { HarnessInteraction } from '../api/harness'
import { requestId } from '../../../shared/utils/requestId'
const props = defineProps<{ card: HarnessInteraction; enabled: boolean; working: boolean }>()
const emit = defineEmits<{ respond: [input: Record<string, unknown>] }>()
const answers = ref<Record<string, string>>({})
const pending = ref<Record<string, unknown>>()
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
function submit(decision: 'submit' | 'cancel', grantLogs = false) {
  if (!available.value || props.working) return
  if (!pending.value)
    pending.value = {
      requestId: requestId(),
      interactionId: props.card.id,
      decision,
      answers: { ...answers.value },
      grantLogs,
      confirmedVerification: props.card.kind === 'verification',
    }
  emit('respond', pending.value)
}
</script>

<template>
  <section class="harness-interaction" aria-label="需要你的补充" :aria-busy="working">
    <strong>{{ card.title }}</strong>
    <p v-if="card.scope">
      {{ card.scope }} <span v-if="card.model">当前模型：{{ card.model }}。</span>
    </p>
    <template v-if="available">
      <form @submit.prevent="submit('submit', card.kind === 'logs')">
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
        <label class="harness-interaction-field"
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
                  ? '读取脱敏日志并继续'
                  : card.kind === 'verification'
                    ? card.action === 'cancel'
                      ? '确认取消验证'
                      : '确认运行验证'
                    : '提交并继续'
            }}
          </button>
          <button
            v-if="card.kind === 'logs' && answers.note?.trim()"
            type="button"
            :disabled="working || !!pending"
            @click="submit('submit')"
          >
            仅提交补充，不读取日志
          </button>
          <button type="button" :disabled="working || !!pending" @click="submit('cancel')">
            {{ card.kind === 'logs' ? '暂不读取' : '取消本次交互' }}
          </button>
        </div>
        <small
          >提交后会继续原任务。{{
            card.kind === 'logs' ? '授权不会保存为用户偏好。' : '也可以在对话输入框中直接补充。'
          }}</small
        >
      </form>
    </template>
    <template v-else>
      <p>
        {{
          card.state === 'ANSWERED'
            ? '已提交，已继续原任务'
            : card.state === 'CANCELED'
              ? '已取消'
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
