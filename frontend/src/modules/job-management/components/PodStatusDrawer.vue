<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { X, RefreshCw, Box, Server, CircleAlert, FileText } from '@lucide/vue'
import { workbenchApi } from '../api/workbench'
import { sessionsApi } from '../../session/api/sessions'
import LogViewer from '../../../shared/components/editor/LogViewer.vue'
import { useManualRefresh } from '../../../shared/composables/useManualRefresh'
import type { JobDraft } from '../../../shared/types/api'
import type { Diagnostic, DiagnosticPod } from '../../../shared/types/workbench'
const props = defineProps<{
  job: Pick<JobDraft, 'id' | 'name' | 'deploymentName'>
  canReadLogs: boolean
  kind?: 'session' | 'job'
}>()
const api = computed(() => (props.kind === 'session' ? sessionsApi : workbenchApi))
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
const drawerWidth = ref(1120),
  availableWidth = ref(window.innerWidth - 16),
  resizing = ref(false)
const minDrawerWidth = computed(() => Math.min(640, availableWidth.value))
let sidebarObserver: ResizeObserver | undefined
let resizeStart = { x: 0, width: 0 }
function measureDrawer() {
  const viewport = document.documentElement.clientWidth
  const sidebar = document.querySelector('.sidebar')?.getBoundingClientRect()
  availableWidth.value = Math.max(240, Math.min(viewport - 16, viewport - (sidebar?.right || 0)))
  drawerWidth.value = Math.max(
    minDrawerWidth.value,
    Math.min(drawerWidth.value, availableWidth.value),
  )
}
function startResize(event: PointerEvent) {
  if (event.button !== 0) return
  event.preventDefault()
  resizeStart = { x: event.clientX, width: drawerWidth.value }
  resizing.value = true
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}
function resizeDrawer(event: PointerEvent) {
  if (resizing.value)
    drawerWidth.value = Math.max(
      minDrawerWidth.value,
      Math.min(availableWidth.value, resizeStart.width + resizeStart.x - event.clientX),
    )
}
function resizeKey(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const width =
    event.key === 'Home'
      ? 1120
      : event.key === 'End'
        ? availableWidth.value
        : drawerWidth.value + (event.key === 'ArrowLeft' ? 40 : -40)
  drawerWidth.value = Math.max(minDrawerWidth.value, Math.min(availableWidth.value, width))
}
const data = ref<Diagnostic | null>(null),
  error = ref(''),
  selected = ref(''),
  tab = ref('status')
const container = ref(''),
  previous = ref(false),
  log = ref(''),
  logError = ref(''),
  logTime = ref(''),
  logLoaded = ref(false)
let logEpoch = 0,
  disposed = false
const pod = computed(() => data.value?.pods.find((p) => p.name === selected.value))
function needsAttention(p: DiagnosticPod) {
  return (
    p.phase === 'Failed' ||
    p.states.some(
      (s) => s.state === 'waiting' || (s.exitCode ?? 0) !== 0 || (!s.init && s.ready === false),
    )
  )
}
const warnings = computed(() => data.value?.pods.filter(needsAttention).length || 0)
const time = (value?: string) => (value ? new Date(value).toLocaleString() : '—')
function clearLog() {
  logEpoch++
  log.value = ''
  logError.value = ''
  logTime.value = ''
  logLoaded.value = false
}
watch(
  selected,
  () => {
    container.value =
      pod.value?.containers.find((name) => !pod.value?.states.find((s) => s.name === name)?.init) ||
      pod.value?.containers[0] ||
      ''
    previous.value = false
    clearLog()
  },
  { flush: 'sync' },
)
watch([container, previous], clearLog, { flush: 'sync' })
const { refresh, busy, disabled, remaining } = useManualRefresh(async () => {
  error.value = ''
  clearLog()
  try {
    const result = await api.value.diagnostics(props.job.id)
    if (disposed) return
    data.value = result
    if (!result.pods.some((p) => p.name === selected.value))
      selected.value = result.pods[0]?.name || ''
    if (!pod.value?.containers.includes(container.value))
      container.value = pod.value?.containers[0] || ''
  } catch (e) {
    if (!disposed) {
      error.value = (e as Error).message
      data.value = null
    }
  }
})
const {
  refresh: readLog,
  busy: logBusy,
  disabled: logDisabled,
  remaining: logRemaining,
} = useManualRefresh(async () => {
  if (!props.canReadLogs || !selected.value || !container.value) return
  clearLog()
  const epoch = logEpoch
  try {
    const result = await api.value.logs(props.job.id, {
      pod: selected.value,
      container: container.value,
      lines: 10000,
      previous: previous.value,
    })
    if (disposed || epoch !== logEpoch) return
    log.value = result.text
    logTime.value = result.queriedAt || new Date().toISOString()
    logLoaded.value = true
  } catch (e) {
    if (!disposed && epoch === logEpoch) logError.value = (e as Error).message
  }
})
function showLog(name: string) {
  container.value = name
  previous.value = false
  tab.value = 'logs'
  void readLog()
}
onMounted(() => {
  measureDrawer()
  window.addEventListener('resize', measureDrawer)
  sidebarObserver = new ResizeObserver(measureDrawer)
  const sidebar = document.querySelector('.sidebar')
  if (sidebar) sidebarObserver.observe(sidebar)
  dialog.value?.showModal()
  void refresh()
})
onUnmounted(() => {
  window.removeEventListener('resize', measureDrawer)
  sidebarObserver?.disconnect()
  disposed = true
  logEpoch++
})
</script>
<template>
  <dialog
    ref="dialog"
    class="pod-drawer"
    :class="{ resizing }"
    :style="{ width: drawerWidth + 'px', maxWidth: availableWidth + 'px' }"
    aria-labelledby="pod-drawer-title"
    @close="emit('close')"
    @cancel.prevent="emit('close')"
  >
    <div
      class="pod-drawer-resizer"
      role="separator"
      aria-label="调整 Pod 状态弹窗宽度"
      aria-orientation="vertical"
      tabindex="0"
      :aria-valuemin="minDrawerWidth"
      :aria-valuemax="availableWidth"
      :aria-valuenow="Math.round(drawerWidth)"
      @pointerdown="startResize"
      @pointermove="resizeDrawer"
      @pointerup="resizing = false"
      @pointercancel="resizing = false"
      @lostpointercapture="resizing = false"
      @keydown="resizeKey"
    >
      <span />
    </div>
    <header class="drawer-heading">
      <div>
        <span class="muted">{{ kind === 'session' ? 'Session 集群诊断' : '作业运行诊断' }}</span>
        <h2 id="pod-drawer-title">{{ job.name }} · Pod 状态</h2>
        <small>{{ job.deploymentName }}</small>
      </div>
      <button class="icon-button" aria-label="关闭 Pod 状态" @click="emit('close')">
        <X :size="20" />
      </button>
    </header>
    <div class="drawer-summary">
      <span><Box :size="16" />{{ data?.pods.length ?? '—' }} 个 Pod</span>
      <span :class="{ danger: warnings }"><CircleAlert :size="16" />{{ warnings }} 个待检查</span>
      <button class="button" :disabled="disabled" @click="refresh">
        <RefreshCw :size="14" />{{
          busy ? '查询中…' : remaining ? remaining + ' 秒后刷新' : '刷新 Pod'
        }}
      </button>
    </div>
    <div class="drawer-scroll" :class="{ 'showing-logs': tab === 'logs' && pod }">
      <p v-if="error" class="notice error" role="alert">{{ error }}</p>
      <div v-if="data?.error" class="notice error">
        <strong>部署错误</strong>
        <p class="error-detail">{{ data.error }}</p>
      </div>
      <p v-if="busy && !data" class="empty-state">正在读取 Pod 与容器状态…</p>
      <div v-else-if="data && !data.pods.length" class="empty-state">
        <Server :size="32" />
        <h3>当前没有关联 Pod</h3>
        <p>资源可能尚未创建或已被删除，请结合部署错误和事件查看。</p>
      </div>
      <div v-if="data?.pods.length" class="pod-layout">
        <aside class="pod-list" aria-label="Pod 列表">
          <h3>关联 Pod</h3>
          <button
            v-for="p in data.pods"
            :key="p.name"
            class="pod-choice"
            :class="{ active: selected === p.name }"
            :aria-pressed="selected === p.name"
            @click="selected = p.name"
          >
            <strong>{{ p.name }}</strong
            ><span
              class="badge"
              :class="needsAttention(p) ? 'failed' : p.phase === 'Running' ? 'running' : 'neutral'"
              >{{ p.phase }}</span
            >
            <small
              >已就绪 {{ p.states.filter((s) => !s.init && s.ready).length }} 个运行容器 · 重启
              {{ p.states.reduce((n, s) => n + s.restarts, 0) }} 次</small
            >
          </button>
        </aside>
        <main v-if="pod" class="pod-detail">
          <div class="pod-detail-heading">
            <div>
              <h3 :title="pod.name">{{ pod.name }}</h3>
              <p class="muted">
                节点 {{ pod.node || '尚未调度' }} · 创建 {{ time(pod.createdAt) }}
              </p>
            </div>
            <span
              class="badge"
              :class="
                needsAttention(pod) ? 'failed' : pod.phase === 'Running' ? 'running' : 'neutral'
              "
              >{{ pod.phase }}</span
            >
          </div>
          <div class="segmented" aria-label="Pod 诊断页签">
            <button :class="{ active: tab === 'status' }" @click="tab = 'status'">状态与事件</button
            ><button v-if="canReadLogs" :class="{ active: tab === 'logs' }" @click="tab = 'logs'">
              <FileText :size="14" />容器日志
            </button>
          </div>
          <template v-if="tab === 'status'">
            <p v-if="pod.message" class="notice error">{{ pod.reason }} {{ pod.message }}</p>
            <section class="pod-card">
              <h3>容器状态</h3>
              <div class="container-list">
                <article v-for="state in pod.states" :key="state.name">
                  <div class="container-heading">
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
                  <button
                    v-if="canReadLogs"
                    class="button small"
                    :disabled="logDisabled || busy"
                    @click="showLog(state.name)"
                  >
                    <FileText :size="14" />查看最新日志
                  </button>
                </article>
                <p v-if="!pod.states.length" class="muted">
                  尚未报告容器状态，可在容器日志页签选择容器。
                </p>
              </div>
            </section>
            <section v-if="pod.conditions?.length" class="pod-card">
              <h3>Pod 条件</h3>
              <div v-for="c in pod.conditions" :key="c.type" class="condition-row">
                <strong>{{ c.type }}</strong
                ><span class="badge" :class="c.status === 'True' ? 'running' : 'neutral'">{{
                  c.status
                }}</span>
                <p v-if="c.reason || c.message" class="muted">{{ c.reason }} {{ c.message }}</p>
              </div>
            </section>
          </template>
          <section v-else-if="canReadLogs" class="pod-logs">
            <div class="pod-log-controls">
              <label
                >日志容器<select v-model="container">
                  <option v-for="name in pod.containers" :key="name" :value="name">
                    {{ name }}
                  </option>
                </select></label
              ><label class="inline-check"
                ><input v-model="previous" type="checkbox" />上一实例</label
              ><button
                class="button"
                :disabled="!container || logDisabled || busy"
                @click="readLog"
              >
                <RefreshCw :size="14" />{{
                  logBusy
                    ? '读取中…'
                    : logRemaining
                      ? logRemaining + ' 秒后刷新'
                      : '读取最新 10000 行'
                }}
              </button>
            </div>
            <p class="muted log-hint">最多最近 10000 行，内容超过 4 MiB 时保留末尾；按需读取。</p>
            <p v-if="logError" class="notice error" role="alert">{{ logError }}</p>
            <p v-if="logBusy" class="empty-state">正在读取容器日志…</p>
            <template v-else-if="logLoaded"
              ><p class="muted">日志查询：{{ time(logTime) }}</p>
              <LogViewer v-if="log" :text="log" fill />
              <p v-else class="empty-state">该容器暂无日志输出</p></template
            >
            <p v-else-if="!logError" class="empty-state">选择容器后读取最新日志</p>
          </section>
        </main>
      </div>
      <section v-if="data && tab === 'status'" class="pod-events">
        <h3>最近事件</h3>
        <p v-if="!data.events.length" class="muted">暂无事件</p>
        <article v-for="(event, index) in data.events" :key="index">
          <span class="badge" :class="event.type === 'Warning' ? 'failed' : 'neutral'">{{
            event.reason || event.type
          }}</span
          ><small>{{ time(event.time) }}</small>
          <p>{{ event.message }}</p>
        </article>
      </section>
    </div>
    <footer>最近状态查询：{{ data ? time(data.queriedAt) : '尚未查询' }} · 按需刷新</footer>
  </dialog>
</template>
<style scoped>
.pod-drawer {
  overflow: hidden;
  container: pod-drawer / inline-size;
  position: fixed;
  inset: 0 0 0 auto;
  margin: 0;
  width: min(1120px, 96vw);
  height: 100dvh;
  max-height: 100dvh;
  max-width: 96vw;
  padding: 0;
  border: 0;
  border-radius: var(--radius-md) 0 0 var(--radius-md);
  color: var(--text);
  background: var(--color-background);
}
.pod-drawer[open] {
  display: flex;
  flex-direction: column;
}
.pod-drawer::backdrop {
  background: var(--color-overlay);
}
.pod-drawer-resizer {
  position: absolute;
  inset: 0 auto 0 0;
  z-index: 3;
  width: 10px;
  cursor: col-resize;
  touch-action: none;
  display: grid;
  place-items: center;
  outline-offset: -2px;
}
.pod-drawer-resizer span {
  width: 3px;
  height: 64px;
  border-radius: 4px;
  background: var(--line);
}
.pod-drawer-resizer:hover,
.pod-drawer-resizer:focus-visible {
  background: var(--color-primary-soft);
}
.pod-drawer-resizer:hover span {
  background: var(--blue);
}
.resizing {
  user-select: none;
}
.drawer-heading,
.drawer-summary,
footer {
  flex-shrink: 0;
}
.drawer-scroll.showing-logs {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.showing-logs > .notice {
  flex: none;
  max-height: 120px;
  overflow: auto;
}
.showing-logs .pod-layout {
  flex: 1;
  min-height: 0;
  grid-template-rows: minmax(0, 1fr);
  align-items: stretch;
}
.showing-logs .pod-list {
  min-height: 0;
  overflow: auto;
}
.showing-logs .pod-detail {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: auto;
}
.showing-logs .pod-detail-heading,
.showing-logs .segmented {
  flex: none;
}
.showing-logs .segmented {
  margin: 8px 0;
}
.pod-logs {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
  min-height: 0;
}
.pod-logs > :not(.log-viewer) {
  flex-shrink: 0;
}
.pod-logs > .log-viewer {
  flex: 1;
  min-height: 96px;
}
.drawer-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--line);
  background: var(--color-surface);
}
h2 {
  margin: 6px 0;
  font-size: var(--font-subtitle);
  overflow-wrap: anywhere;
}
h3 {
  font-size: var(--font-section);
  margin: 0 0 12px;
}
.drawer-summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  padding: 12px 24px;
  background: var(--color-surface);
  border-bottom: 1px solid var(--line);
  font-size: var(--font-body);
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
  min-height: 0;
}
.pod-layout {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
.pod-list,
.pod-detail,
.pod-events {
  min-width: 0;
  background: var(--color-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  padding: 16px;
}
.pod-choice {
  width: 100%;
  text-align: left;
  background: transparent;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  padding: 12px;
  margin-bottom: 8px;
  color: var(--text);
  cursor: pointer;
}
.pod-choice.active {
  border-color: var(--blue);
  background: var(--color-surface-soft);
}
.pod-choice strong,
.pod-choice small {
  display: block;
  overflow-wrap: anywhere;
}
.pod-choice .badge {
  margin: 8px 0;
}
.pod-choice small,
.container-list small {
  color: var(--muted);
  font-size: var(--font-small);
}
.pod-detail-heading {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
}
.pod-detail-heading h3 {
  overflow-wrap: anywhere;
  margin-bottom: 6px;
}
.pod-detail-heading p {
  font-size: var(--font-small);
  margin-top: 0;
  overflow-wrap: anywhere;
}
.segmented {
  margin: 12px 0 20px;
}
.segmented button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.pod-card + .pod-card {
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid var(--line);
}
.container-list article {
  border-top: 1px solid var(--line);
  padding: 12px 0;
}
.container-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  overflow-wrap: anywhere;
}
.container-list small {
  display: block;
  margin: 8px 0;
}
.error-detail {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 180px;
  overflow: auto;
  font-size: var(--font-small);
}
.condition-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-top: 1px solid var(--line);
  font-size: var(--font-small);
}
.condition-row p {
  width: 100%;
  margin: 0;
  overflow-wrap: anywhere;
}
.pod-events {
  margin-top: 16px;
}
.pod-events article {
  border-left: 2px solid var(--line);
  padding: 0 0 8px 12px;
  margin: 12px 0;
}
.pod-events small {
  margin-left: 10px;
  color: var(--muted);
  font-size: var(--font-small);
}
.pod-events p {
  overflow-wrap: anywhere;
  font-size: var(--font-body);
}
.pod-log-controls {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}
.pod-log-controls > label:first-child {
  flex: 1 1 180px;
  min-width: 0;
}
.pod-log-controls select {
  width: 100%;
}
.pod-log-controls .inline-check {
  padding-bottom: 7px;
}
.log-hint {
  font-size: var(--font-small);
}
footer {
  padding: 12px 24px;
  border-top: 1px solid var(--line);
  color: var(--muted);
  font-size: var(--font-small);
  background: var(--color-surface);
}
@container pod-drawer (max-width: 760px) {
  .pod-layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .pod-list {
    max-height: 240px;
    overflow: auto;
  }
  .showing-logs .pod-layout {
    grid-template-rows: 90px minmax(0, 1fr);
  }
  .drawer-heading,
  .drawer-summary,
  .drawer-scroll {
    padding: 16px;
  }
  .drawer-summary {
    gap: 10px;
  }
  .drawer-summary button {
    margin-left: 0;
  }
  .pod-detail {
    padding: 12px;
  }
  .pod-log-controls {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 8px;
  }
  .pod-log-controls > label:first-child {
    grid-column: 1 / -1;
  }
  .pod-log-controls .inline-check {
    padding: 0;
  }
  .showing-logs .pod-detail-heading > div {
    min-width: 0;
  }
  .showing-logs .pod-detail-heading h3 {
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }
  .showing-logs .pod-detail-heading .muted,
  .showing-logs .pod-detail-heading > .badge {
    display: none;
  }
}
</style>
