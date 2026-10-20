<script setup lang="ts">
import { computed, ref } from 'vue'
import type { AiJobConversation } from '../api/ai'
const props = defineProps<{ evidence: NonNullable<AiJobConversation['evidence']> }>()
const tab = ref('events'),
  log = ref(0)
const details = computed(
  () =>
    props.evidence.diagnostics as
      { pods?: Record<string, unknown>[]; events?: Record<string, unknown>[] } | undefined,
)
</script>
<template>
  <div class="ai-evidence-view">
    <p class="muted">
      采集于 {{ new Date(evidence.queriedAt).toLocaleString() }} · 草稿 r{{
        evidence.baseline.jobRevision
      }}
      · 仅展示本次有权读取的脱敏证据
    </p>
    <div class="toolbar-actions">
      <button
        v-for="[id, name] in [
          ['events', 'Pod 与事件'],
          ['configuration', '部署配置'],
          ['logs', '容器日志'],
        ]"
        :key="id"
        class="button"
        :class="{ primary: tab === id }"
        @click="tab = id!"
      >
        {{ name }}
      </button>
    </div>
    <p v-for="message in evidence.unavailable" :key="message" class="notice">{{ message }}</p>
    <template v-if="tab === 'events'">
      <h4>Pod 状态</h4>
      <pre>{{ JSON.stringify(details?.pods || [], null, 2) }}</pre>
      <h4>事件</h4>
      <div v-for="(event, i) in details?.events || []" :key="i" class="notice">
        <strong>{{ event.reason }}</strong>
        <p>{{ event.message }}</p>
        <small>{{ event.lastTimestamp || event.eventTime || event.time }}</small>
      </div>
      <p v-if="!details?.events?.length" class="muted">本轮没有可用事件</p>
    </template>
    <template v-else-if="tab === 'configuration'"
      ><h4>当前草稿配置</h4>
      <pre>{{ JSON.stringify(evidence.draftConfiguration, null, 2) }}</pre>
      <h4>集群声明配置</h4>
      <pre>{{ JSON.stringify(evidence.runtimeConfiguration, null, 2) }}</pre>
    </template>
    <template v-else
      ><label
        >容器<select v-model.number="log">
          <option
            v-for="(item, i) in evidence.logs || []"
            :key="item.pod + item.container"
            :value="i"
          >
            {{ item.pod }} / {{ item.container }} {{ item.previous ? '（上次实例）' : '' }}
          </option>
        </select></label
      >
      <pre v-if="evidence.logs?.[log]">{{ evidence.logs[log]!.text }}</pre>
      <p v-else class="muted">本轮没有可用日志</p></template
    >
  </div>
</template>
<style scoped>
.ai-evidence-view {
  font-size: 14px;
  line-height: 1.6;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--color-background);
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.7;
}
h4 {
  margin: 20px 0 8px;
}
label {
  margin-top: 16px;
}
select {
  width: 100%;
}
.notice p {
  white-space: pre-wrap;
}
.toolbar-actions {
  flex-wrap: wrap;
}
</style>
