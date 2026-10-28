<script setup lang="ts">
import { computed, ref } from 'vue'
import type { AiJobConversation } from '../api/ai'
const props = defineProps<{
  evidence: NonNullable<AiJobConversation['evidence']>
  busy?: boolean
  canReadLogs?: boolean
}>()
const emit = defineEmits<{ refresh: [string] }>()
const tab = ref('events'),
  log = ref(0)
const details = computed(
  () =>
    props.evidence.diagnostics as
      { pods?: Record<string, unknown>[]; events?: Record<string, unknown>[] } | undefined,
)
const categories = computed(() => [
  ['events', 'Pod 状态与事件'],
  ['pods', 'Pod 配置'],
  ['podMetrics', 'Pod 资源用量'],
  ['configuration', '部署配置'],
  ['logs', '容器日志'],
  ['flinkExceptions', 'Flink 异常'],
  ['flinkCheckpoints', '快照 / Checkpoint'],
  ['flinkMetrics', 'JVM / GC'],
  ['flinkLogs', 'Flink 日志'],
])
const category = computed(() => (tab.value === 'events' ? 'auto' : tab.value))
const flinkLabels: Record<string, string> = {
  overview: '集群概览',
  exceptions: '当前作业异常链',
  checkpoints: 'Checkpoint 详情',
  operatorSnapshots: 'Operator 保存点与快照',
  jobManager: 'JobManager JVM 指标',
  taskManagers: 'TaskManager JVM 指标',
  jobManagerLog: 'JobManager 日志关键摘录',
  logTruncated: '日志是否截取',
}
const collected = computed(
  () =>
    ['events', 'configuration'].includes(tab.value) ||
    props.evidence.category === tab.value ||
    (tab.value === 'logs' && !!props.evidence.logs?.length),
)
</script>
<template>
  <div class="ai-evidence-view">
    <p class="muted">
      此快照采集于 {{ new Date(evidence.queriedAt).toLocaleString() }} · 草稿 r{{
        evidence.baseline.jobRevision
      }}
      · 按需采集的只读脱敏证据，不代表持续监控
    </p>
    <div class="toolbar-actions">
      <button
        v-for="[id, name] in categories"
        :key="id"
        class="button"
        :class="{ primary: tab === id }"
        @click="tab = id!"
      >
        {{ name }}
      </button>
    </div>
    <div class="ai-evidence-actions">
      <button
        class="button primary"
        :disabled="busy || (!canReadLogs && ['logs', 'flinkLogs'].includes(tab))"
        @click="emit('refresh', category)"
      >
        {{ busy ? '正在采集…' : collected ? '重新采集此类证据' : '按需采集此类证据' }}
      </button>
      <span class="muted">每次采集生成新快照；模型只在你发送分析请求时调用。</span>
    </div>
    <p v-if="!collected" class="notice">本快照未采集此类别，点击上方按钮按需读取。</p>
    <p v-for="message in evidence.unavailable" :key="message" class="notice evidence-notice">
      {{ message }}
    </p>
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
      ><p class="notice evidence-notice">
        {{
          evidence.documents?.referenceNote ||
          '合法平台 Secret 引用在提交时解析；脱敏标记不能写回配置。'
        }}
      </p>
      <h4>当前已保存草稿 YAML（完整结构，敏感值脱敏）</h4>
      <pre>{{
        evidence.documents?.draftYaml || JSON.stringify(evidence.draftConfiguration, null, 2)
      }}</pre>
      <h4>集群实际声明 YAML（本次采集）</h4>
      <pre>{{
        evidence.documents?.runtimeYaml ||
        JSON.stringify(evidence.runtimeConfiguration, null, 2) ||
        '本轮无可用集群声明'
      }}</pre>
      <template v-if="evidence.documents?.sql"
        ><h4>当前 SQL 草稿</h4>
        <pre>{{ evidence.documents.sql }}</pre>
      </template>
      <template v-if="evidence.documents?.publishedSql"
        ><h4>已发布 SQL</h4>
        <pre>{{ evidence.documents.publishedSql }}</pre>
      </template>
      <template v-if="evidence.documents?.runtimeSql"
        ><h4>集群声明 SQL</h4>
        <pre>{{ evidence.documents.runtimeSql }}</pre>
      </template>
    </template>
    <template v-else-if="tab === 'pods' || tab === 'podMetrics'">
      <section v-for="pod in collected ? evidence.podDetails || [] : []" :key="pod.name">
        <h4>{{ pod.name }}</h4>
        <pre v-if="tab === 'pods'">{{ pod.yaml }}</pre>
        <template v-else>
          <p class="muted">
            指标时间 {{ pod.timestamp || '不可用' }} · 采样窗口 {{ pod.window || '未知' }}
          </p>
          <h5>配置的 requests / limits</h5>
          <pre>{{ JSON.stringify(pod.configured, null, 2) }}</pre>
          <h5>实际 CPU / 内存用量</h5>
          <pre>{{
            pod.usage ? JSON.stringify(pod.usage, null, 2) : '本轮没有可用用量，不能视为 0'
          }}</pre>
        </template>
      </section>
    </template>
    <template v-else-if="tab.startsWith('flink')">
      <template v-if="collected && evidence.flink">
        <p class="muted">
          Flink REST 的可用性依赖 JobManager 已启动；未返回的指标不代表正常或为零。
        </p>
        <section v-for="(value, key) in evidence.flink" :key="key">
          <template v-if="key !== 'category'"
            ><h4>{{ flinkLabels[key] || key }}</h4>
            <pre>{{ typeof value === 'string' ? value : JSON.stringify(value, null, 2) }}</pre>
          </template>
        </section>
      </template>
    </template>
    <template v-else-if="tab === 'logs'"
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
      <p class="muted">有限尾部中提取 ERROR / WARN / 异常栈附近行及最后输出，非完整日志。</p>
      <p v-if="evidence.logs?.[log]" class="muted">
        来源 {{ evidence.logs[log]!.previous ? 'previous（上次实例）' : 'current（当前实例）' }} ·
        采集时间 {{ evidence.logs[log]!.queriedAt || evidence.queriedAt }}
        {{ evidence.logs[log]!.fallback ? '· 首选日志不可用，已回退到此来源' : '' }}
      </p>
      <pre v-if="evidence.logs?.[log]">{{ evidence.logs[log]!.text }}</pre>
      <p v-else class="muted">本轮没有可用日志</p></template
    >
  </div>
</template>
<style scoped>
.ai-evidence-view {
  font-size: var(--font-section);
  line-height: 1.6;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--color-background);
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 6px;
  font-size: var(--font-body);
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
.ai-evidence-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin: 16px 0;
}
.evidence-notice {
  display: block;
}
</style>
