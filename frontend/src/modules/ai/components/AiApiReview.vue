<script setup lang="ts">
import { ref, watch } from 'vue'
import { jsonBody, request } from '../../../shared/api/http'
const props = defineProps<{ id: string }>()
const proposal = ref<Record<string, unknown>>()
const error = ref(''),
  busy = ref(false)
const states: Record<string, string> = {
  PENDING: '等待确认',
  COMPLETED: '接口返回成功',
  REJECTED: '接口拒绝请求',
  UNKNOWN: '结果未知，请核对原请求',
}
watch(
  () => props.id,
  async (id) => {
    proposal.value = undefined
    error.value = ''
    if (!id) return
    try {
      const row = await request<Record<string, unknown>>(
        '/api/ai/api-proposals/' + encodeURIComponent(id),
      )
      if (props.id === id) proposal.value = row
    } catch (e) {
      if (props.id === id) error.value = (e as Error).message
    }
  },
  { immediate: true },
)
async function execute() {
  if (busy.value || !proposal.value) return
  busy.value = true
  error.value = ''
  const id = props.id
  try {
    const row = await request<Record<string, unknown>>(
      '/api/ai/api-proposals/' + encodeURIComponent(id) + '/execute',
      { method: 'POST', ...jsonBody({ confirmed: true }) },
    )
    if (props.id === id) proposal.value = row
  } catch (e) {
    if (props.id === id) error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <section v-if="id" class="panel" aria-label="业务接口请求审阅">
    <h2>业务接口请求审阅</h2>
    <p>确认前请核对目标、参数和影响。结果未知时请到业务页面核对，不会自动重复执行。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="proposal">
      <p><strong>请求：</strong>{{ proposal.method }} {{ proposal.uri }}</p>
      <p><strong>状态：</strong>{{ states[String(proposal.state)] || proposal.state }}</p>
      <details open>
        <summary>具体参数</summary>
        <pre>{{ JSON.stringify(proposal.arguments, null, 2) }}</pre>
      </details>
      <details v-if="proposal.result">
        <summary>接口返回</summary>
        <pre>{{ JSON.stringify(proposal.result, null, 2) }}</pre>
      </details>
    </template>
    <button v-if="proposal?.state === 'PENDING'" class="primary" :disabled="busy" @click="execute">
      {{ busy ? '正在执行…' : '确认执行此请求' }}
    </button>
  </section>
</template>
<style scoped>
pre {
  max-height: 50vh;
  overflow: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
