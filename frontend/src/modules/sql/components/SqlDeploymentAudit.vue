<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { LoaderCircle, X } from '@lucide/vue'
import CodeEditor from '../../../shared/components/editor/CodeEditor.vue'
import { request } from '../../../shared/api/http'
import { jobsApi } from '../../job-management/api/jobs'
import { useManualRefresh } from '../../../shared/composables/useManualRefresh'
import { decodeDeploymentSql } from '../utils/deploymentSql'
const props = defineProps<{
  sql: string
  jobId: string
  contextKey: number
  preview: (signal: AbortSignal) => Promise<{ yaml: string }>
}>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>(),
  active = ref(0),
  phase = ref('')
type Entry = { label: string; value?: ReturnType<typeof decodeDeploymentSql>; error?: string }
const entries = ref<Entry[]>([])
const selected = computed(() => entries.value[active.value])
const controller = new AbortController()
let disposed = false,
  version = 0
async function load() {
  const serial = ++version
  const labels = ['当前待发布 SQL', '已发布部署草稿 SQL', '集群声明 SQL']
  entries.value = labels.map((label) => ({ label }))
  phase.value = ''
  const results = await Promise.allSettled([
    props.preview(controller.signal),
    props.jobId
      ? request<{ yaml: string }>('/api/jobs/' + encodeURIComponent(props.jobId), {
          signal: controller.signal,
        })
      : Promise.reject(new Error('尚未生成部署草稿')),
    props.jobId
      ? jobsApi.refresh(controller.signal)
      : Promise.reject(new Error('尚未关联部署，无需查询集群')),
  ])
  if (disposed || serial !== version) return
  entries.value = results.map((result, index) => {
    try {
      if (result.status === 'rejected') throw result.reason
      let source = result.value as any
      if (index === 2) {
        const item = source.items?.find((row: any) => row.jobId === props.jobId && row.managed)
        if (!item) throw new Error('本次未找到属于当前作业的集群资源；可能尚未部署或已删除')
        phase.value = '本次观测作业状态：' + (item.jobState || '未知')
        source = item
      }
      return { label: labels[index]!, value: decodeDeploymentSql(source.yaml) }
    } catch (cause) {
      return { label: labels[index]!, error: (cause as Error).message }
    }
  })
}
const { refresh, busy, disabled, remaining } = useManualRefresh(load)
function comparison(entry: Entry, index: number) {
  if (entry.error) return entry.error
  if (busy.value || !entry.value) return '正在核对…'
  if (index === 0) return '本次编辑内容，经 Catalog 初始化和 SQL 检查生成'
  if (!entries.value[0]?.value) return '当前 SQL 编译失败，暂不能比较'
  return entry.value.fingerprint === entries.value[0].value.fingerprint
    ? '与当前待发布 SQL 一致'
    : '与当前待发布 SQL 不一致'
}
watch(
  () => props.contextKey,
  () => emit('close'),
)
onMounted(() => {
  dialog.value?.showModal()
  void refresh()
})
onBeforeUnmount(() => {
  disposed = true
  version++
  controller.abort()
  dialog.value?.close()
})
</script>
<template>
  <dialog
    ref="dialog"
    class="sql-audit-dialog"
    aria-label="核对部署 SQL"
    @cancel.prevent="emit('close')"
  >
    <div class="audit-heading">
      <h3>核对部署 SQL</h3>
      <button class="text-button" aria-label="关闭 SQL 核对" @click="emit('close')">
        <X :size="17" />
      </button>
    </div>
    <p class="audit-explanation">
      SQL 随部署写入 <code>spec.job.args</code>，每条语句以 Base64 编码，由 Runner
      解码执行。这里直接还原这些参数，核对发布和集群声明是否包含最新 SQL。
    </p>
    <div class="audit-status">
      <span>{{ phase || '仅在打开或手动刷新时查询' }}</span
      ><button class="button" :disabled="disabled" @click="refresh">
        <LoaderCircle v-if="busy" class="audit-spinner" :size="14" />{{
          busy ? '正在核对…' : remaining ? `刷新核对（${remaining}s）` : '刷新核对'
        }}
      </button>
    </div>
    <div class="audit-entries">
      <button
        v-for="(entry, index) in entries"
        :key="entry.label"
        :class="{ active: active === index }"
        @click="active = index"
      >
        <strong>{{ entry.label }}</strong
        ><span>{{ comparison(entry, index) }}</span
        ><small v-if="entry.value"
          >源修订 {{ entry.value.revision }} · SHA-256
          {{ entry.value.sourceHash.slice(0, 12) || '旧版本未记录' }}</small
        >
      </button>
    </div>
    <p class="audit-caution">
      集群声明一致代表参数已提交，不代表 Flink 已完成升级或运行成功。请结合运行状态；“SQL
      检查”验证语法，“SQL 验证”在专用 Session 中检查限量结果。
    </p>
    <CodeEditor
      :key="active"
      :model-value="selected?.value?.sql || (active === 0 ? sql : '')"
      readonly
      language="sql"
      :label="selected?.label || '部署 SQL 核对内容'"
    />
    <p v-if="selected?.error" class="field-error" role="alert">{{ selected.error }}</p>
  </dialog>
</template>
<style scoped>
.sql-audit-dialog {
  width: min(1100px, 94vw);
  height: 85dvh;
  max-height: 90dvh;
  border: 1px solid var(--line);
  border-radius: 9px;
  padding: 18px;
  color: var(--text);
  background: var(--color-surface);
}
.sql-audit-dialog[open] {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sql-audit-dialog::backdrop {
  background: var(--color-overlay);
}
.audit-heading,
.audit-status {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
  flex-shrink: 0;
}
.audit-heading h3 {
  margin: 0;
  font-size: 16px;
}
.audit-explanation,
.audit-caution {
  font-size: 12px;
  line-height: 1.7;
  margin: 0;
  flex-shrink: 0;
}
.audit-caution {
  color: var(--muted);
}
.audit-status {
  font-size: 12px;
}
.audit-entries {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  flex-shrink: 0;
}
.audit-entries button {
  text-align: left;
  border: 1px solid var(--line);
  background: var(--color-surface);
  border-radius: 6px;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.audit-entries button.active {
  border-color: var(--blue);
  background: var(--color-surface-soft);
}
.audit-entries small {
  font-size: 11px;
  color: var(--muted);
}
.sql-audit-dialog :deep(.code-editor) {
  flex: 1;
  min-height: 100px;
}
.audit-spinner {
  animation: audit-spin 1s linear infinite;
}
@keyframes audit-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 600px) {
  .sql-audit-dialog {
    padding: 12px;
    height: 92dvh;
  }
  .audit-entries {
    grid-template-columns: minmax(0, 1fr);
    gap: 5px;
  }
  .audit-entries button {
    padding: 7px;
    gap: 3px;
  }
  .audit-explanation,
  .audit-caution {
    font-size: 11px;
  }
  .audit-caution {
    max-height: 65px;
    overflow: auto;
  }
}
</style>
