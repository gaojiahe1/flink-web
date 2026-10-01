<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { X, RefreshCw, Box, Server, CircleAlert } from '@lucide/vue'
import { workbenchApi } from '../api/workbench'
import { useManualRefresh } from '../../../shared/composables/useManualRefresh'
import type { JobDraft } from '../../../shared/types/api'
import type { Diagnostic } from '../../../shared/types/workbench'
const props = defineProps<{ job: JobDraft }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>(),
  data = ref<Diagnostic | null>(null),
  error = ref(''),
  selected = ref('')
const { refresh, busy, disabled, remaining } = useManualRefresh(async () => {
  error.value = ''
  data.value = null
  try {
    const result = await workbenchApi.diagnostics(props.job.id)
    data.value = result
    if (!result.pods.some((p) => p.name === selected.value))
      selected.value = result.pods[0]?.name || ''
  } catch (e) {
    error.value = (e as Error).message
  }
})
const pod = computed(() => data.value?.pods.find((p) => p.name === selected.value))
const warnings = computed(
  () =>
    data.value?.pods.filter(
      (p) =>
        p.phase === 'Failed' ||
        p.states.some((s) => s.state === 'waiting' || (s.exitCode ?? 0) !== 0),
    ).length || 0,
)
onMounted(() => {
  dialog.value?.showModal()
  void refresh()
})
</script>
<template>
  <dialog
    ref="dialog"
    class="pod-drawer"
    aria-labelledby="pod-drawer-title"
    @close="emit('close')"
    @cancel.prevent="emit('close')"
  >
    <header class="drawer-heading">
      <div>
        <span class="muted">作业运行诊断</span>
        <h2 id="pod-drawer-title">{{ job.name }} · Pod 状态</h2>
        <small>{{ job.deploymentName }}</small>
      </div>
      <button class="icon-button" aria-label="关闭 Pod 状态" @click="emit('close')">
        <X :size="20" />
      </button>
    </header>
    <div class="drawer-summary">
      <span><Box :size="16" /> {{ data?.pods.length ?? '—' }} 个 Pod</span
      ><span :class="{ danger: warnings }"><CircleAlert :size="16" /> {{ warnings }} 个待检查</span>
      <button class="button" :disabled="disabled" @click="refresh">
        <RefreshCw :size="14" />{{
          busy ? '查询中…' : remaining ? remaining + ' 秒后刷新' : '刷新 Pod'
        }}
      </button>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <div class="drawer-scroll">
      <p v-if="busy" class="empty-state">正在读取 Pod 与容器状态…</p>
      <template v-else-if="data">
        <div v-if="data.error" class="notice error">
          <strong>部署错误</strong>
          <p class="error-detail">{{ data.error }}</p>
        </div>
        <div v-if="!data.pods.length" class="empty-state">
          <Server :size="32" />
          <h3>当前没有关联 Pod</h3>
          <p>资源可能尚未创建或已被删除，请结合部署错误和事件查看。</p>
        </div>
        <template v-else>
          <label class="pod-selector"
            >选择 Pod<select v-model="selected">
              <option v-for="p in data.pods" :key="p.name" :value="p.name">
                {{ p.name }} · {{ p.phase }}
              </option>
            </select></label
          >
          <section v-if="pod" class="pod-card">
            <div class="pod-card-heading">
              <strong>{{ pod.name }}</strong
              ><span
                class="badge"
                :class="
                  pod.phase === 'Running'
                    ? 'running'
                    : pod.phase === 'Failed'
                      ? 'failed'
                      : 'neutral'
                "
                >{{ pod.phase }}</span
              >
            </div>
            <p class="muted">
              节点 {{ pod.node || '尚未调度' }} · 创建时间
              {{ pod.createdAt ? new Date(pod.createdAt).toLocaleString() : '—' }}
            </p>
            <p v-if="pod.message" class="notice error">{{ pod.reason }} {{ pod.message }}</p>
            <h3>容器状态</h3>
            <div class="container-list">
              <article v-for="state in pod.states" :key="state.name">
                <div>
                  <strong>{{ state.name }}</strong
                  ><span class="version-pill">{{ state.init ? '初始化容器' : '运行容器' }}</span
                  ><span
                    class="badge"
                    :class="
                      state.ready
                        ? 'running'
                        : state.reason && state.reason !== 'Completed'
                          ? 'failed'
                          : 'neutral'
                    "
                    >{{ state.reason || state.state || (state.ready ? 'Ready' : '未就绪') }}</span
                  >
                </div>
                <small
                  >重启 {{ state.restarts }} 次<span v-if="state.exitCode !== undefined">
                    · 退出码 {{ state.exitCode }}</span
                  ></small
                >
                <p v-if="state.message" class="error-detail">{{ state.message }}</p>
              </article>
              <p v-if="!pod.states.length" class="muted">尚未报告容器状态</p>
            </div>
            <details v-if="pod.conditions?.length">
              <summary>Pod 条件</summary>
              <p v-for="c in pod.conditions" :key="c.type">
                <strong>{{ c.type }}：{{ c.status }}</strong
                ><br />{{ c.reason }} {{ c.message }}
              </p>
            </details>
          </section>
        </template>
        <section class="pod-events">
          <h3>最近事件</h3>
          <p v-if="!data.events.length" class="muted">暂无事件</p>
          <article v-for="(event, index) in data.events" :key="index">
            <span class="badge" :class="event.type === 'Warning' ? 'failed' : 'neutral'">{{
              event.reason || event.type
            }}</span
            ><small>{{ event.time ? new Date(event.time).toLocaleString() : '—' }}</small>
            <p>{{ event.message }}</p>
          </article>
        </section>
      </template>
    </div>
    <footer>
      最近查询：{{ data?.queriedAt ? new Date(data.queriedAt).toLocaleString() : '尚未查询' }} ·
      按需刷新
    </footer>
  </dialog>
</template>
<style scoped>
.pod-drawer {
  position: fixed;
  inset: 0 0 0 auto;
  margin: 0;
  width: min(780px, 95vw);
  height: 100dvh;
  max-height: 100dvh;
  max-width: 95vw;
  padding: 0;
  border: 0;
  border-radius: 12px 0 0 12px;
  color: var(--text);
}
.pod-drawer[open] {
  display: flex;
  flex-direction: column;
}
.pod-drawer::backdrop {
  background: #15263d66;
}
.drawer-heading {
  display: flex;
  justify-content: space-between;
  padding: 24px;
  border-bottom: 1px solid var(--line);
}
h2 {
  margin: 6px 0;
  font-size: 19px;
}
.drawer-summary {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 16px 24px;
  background: var(--surface-soft, #f7f9fc);
}
.drawer-summary span,
.drawer-summary button {
  display: inline-flex;
  gap: 6px;
  align-items: center;
}
.drawer-summary button {
  margin-left: auto;
}
.drawer-scroll {
  padding: 20px 24px;
  overflow: auto;
  flex: 1;
}
.pod-selector {
  display: block;
  margin-bottom: 16px;
}
.pod-card {
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 8px;
}
.pod-card-heading,
.container-list article > div {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  overflow-wrap: anywhere;
}
.container-list article {
  border-top: 1px solid var(--line);
  padding: 12px 0;
}
.container-list small {
  display: block;
  margin-top: 6px;
  color: var(--muted);
}
.error-detail {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 180px;
  overflow: auto;
  font-size: 12px;
}
.pod-events {
  margin-top: 24px;
}
.pod-events article {
  border-left: 2px solid var(--line);
  padding: 0 0 8px 12px;
  margin: 12px 0;
}
.pod-events small {
  margin-left: 10px;
  color: var(--muted);
}
.pod-events p {
  overflow-wrap: anywhere;
}
footer {
  padding: 14px 24px;
  border-top: 1px solid var(--line);
  color: var(--muted);
  font-size: 12px;
}
</style>
