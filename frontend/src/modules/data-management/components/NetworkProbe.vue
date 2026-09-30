<script setup lang="ts">
import { ref, computed } from 'vue'
import { request, jsonBody } from '../../../shared/api/http'
import { currentUser } from '../../permission/stores/auth'
import HelpTip from '../../../shared/components/form/HelpTip.vue'
const host = ref(''),
  port = ref<number>(3306),
  busy = ref(false),
  message = ref(''),
  failed = ref(false)
const admin = computed(() => currentUser.value?.roles.includes('ROLE_ADMIN'))
async function test() {
  if (busy.value) return
  busy.value = true
  message.value = ''
  try {
    const r = await request<{ connected: boolean; message: string; elapsedMs: number }>(
      '/api/platform/network/test',
      { method: 'POST', ...jsonBody({ host: host.value.trim(), port: port.value }) },
    )
    failed.value = !r.connected
    message.value = r.message + '（' + r.elapsedMs + ' ms）'
  } catch (e) {
    failed.value = true
    message.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <details v-if="admin" class="network-diagnostic">
    <summary>
      网络端口验证
      <HelpTip
        text="从平台后端发起单个 TCP 连接，有超时和并发限制。仅在点击时执行，不代表协议认证或 Flink Pod 网络可用。"
      />
    </summary>
    <form class="network-form" @submit.prevent="test">
      <label
        >诊断主机<input
          data-field="host"
          v-model="host"
          placeholder="db.default.svc"
          required
          maxlength="253"
      /></label>
      <label
        >诊断端口<input
          data-field="port"
          v-model.number="port"
          type="number"
          min="1"
          max="65535"
          step="1"
          required
      /></label>
      <button class="button" :disabled="busy">{{ busy ? '验证中…' : '验证端口' }}</button>
    </form>
    <p v-if="message" :class="['notice', { error: failed }]" role="status">{{ message }}</p>
  </details>
</template>
<style scoped>
.network-diagnostic {
  margin-bottom: 12px;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 4px;
}
summary {
  cursor: pointer;
  color: var(--muted);
}
.network-form {
  display: flex;
  align-items: end;
  gap: 12px;
  margin: 10px 0;
}
.network-form label:first-child {
  width: 280px;
}
.network-form label:nth-child(2) {
  width: 110px;
}
</style>
