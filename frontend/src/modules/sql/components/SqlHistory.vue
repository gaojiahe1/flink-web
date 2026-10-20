<script setup lang="ts">
import { showValidationError } from '../../../shared/utils/fieldValidation'
import { confirmDialog } from '../../../shared/utils/confirmDialog'
import ManualRefreshButton from '../../../shared/components/form/ManualRefreshButton.vue'
import { ref, watch, computed } from 'vue'
import { sqlHistoryApi } from '../api/history'
import type { SqlHistoryItem } from '../../../shared/types/workbench'
const props = defineProps<{ id: string; revision: number; sql: string; editable: boolean }>()
const emit = defineEmits<{ restored: [] }>()
const versions = ref<SqlHistoryItem[]>([]),
  selected = ref(''),
  error = ref(''),
  busy = ref(false)
const chosen = computed(() => versions.value.find((v) => v.id === selected.value))
const differences = computed(() => {
  const a = (chosen.value?.sql || '').split('\n'),
    b = props.sql.split('\n')
  return Array.from({ length: Math.min(2000, Math.max(a.length, b.length)) }, (_, i) => ({
    n: i + 1,
    a: a[i] || '',
    b: b[i] || '',
    changed: a[i] !== b[i],
  }))
})
watch(
  () => props.id,
  () => void load(),
  { immediate: true },
)
async function load() {
  const id = props.id
  if (!id) {
    versions.value = []
    return
  }
  busy.value = true
  error.value = ''
  try {
    const result = await sqlHistoryApi.list(id)
    if (id !== props.id) return
    versions.value = result
    selected.value = result[0]?.id || ''
  } catch (e) {
    error.value = showValidationError((e as Error).message)
  } finally {
    busy.value = false
  }
}
async function restore() {
  if (
    !chosen.value ||
    !(await confirmDialog('恢复此 SQL 和运行配置为新草稿？当前未保存修改会被替换，不会部署。'))
  )
    return
  busy.value = true
  error.value = ''
  try {
    await sqlHistoryApi.restore(props.id, selected.value, props.revision)
    emit('restored')
    await load()
  } catch (e) {
    error.value = showValidationError((e as Error).message)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div>
    <div class="field-heading">
      <h3>SQL 历史</h3>
      <ManualRefreshButton :action="load" :disabled="busy" label="刷新记录" />
    </div>
    <p class="muted">保存产生历史版本，仅保留最近 5 次；恢复只更新草稿。</p>
    <div v-if="error" class="notice error">{{ error }}</div>
    <select data-field="selected" v-model="selected" aria-label="SQL 历史版本">
      <option v-for="version in versions" :key="version.id" :value="version.id">
        {{ new Date(version.createdAt).toLocaleString() }} · {{ version.actor }}
      </option>
    </select>
    <p v-if="!versions.length" class="empty-state">保存 SQL 后可查看历史版本。</p>
    <template v-if="chosen"
      ><button v-if="editable" class="button" :disabled="busy" @click="restore">恢复此版本</button>
      <p class="muted">历史 / 当前，按行号对照</p>
      <div class="diff-table sql-history-diff">
        <div
          v-for="line in differences"
          :key="line.n"
          class="diff-line"
          :class="{ changed: line.changed }"
        >
          <pre><span>{{ line.n }}</span>{{ line.a }}</pre>
          <pre><span>{{ line.n }}</span>{{ line.b }}</pre>
        </div>
      </div></template
    >
  </div>
</template>
