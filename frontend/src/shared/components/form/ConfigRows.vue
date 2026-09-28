<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
const props = defineProps<{ modelValue: Record<string, string> }>()
const emit = defineEmits<{ 'update:modelValue': [Record<string, string>] }>()
const root = ref<HTMLElement>()
const rows = ref<{ key: string; value: string }[]>([])
const hints: Record<string, string> = {
  'execution.checkpointing.interval': 'Checkpoint 周期，例如 60s。',
  'execution.checkpointing.timeout': 'Checkpoint 超时，例如 10min。',
  'state.checkpoints.num-retained': '保留的已完成 Checkpoint 数量，平台默认为 1。',
  'state.checkpoints.dir': 'Checkpoint 的 S3 存储目录。',
  'state.savepoints.dir': 'Savepoint 的 S3 存储目录。',
  'taskmanager.numberOfTaskSlots': '每个 TaskManager 的逻辑槽位数。',
  'parallelism.default': '默认并行度，必须为正整数。',
  'state.backend.type': '状态后端，例如 hashmap / rocksdb；以运行版本为准。',
  'restart-strategy.type': '重启策略，例如 fixed-delay。',
  'table.local-time-zone': 'SQL 本地时区，例如 Asia/Shanghai。',
}
watch(
  () => props.modelValue,
  (value) => {
    if (
      JSON.stringify(value) !==
      JSON.stringify(
        Object.fromEntries(rows.value.filter((r) => r.key).map((r) => [r.key, r.value])),
      )
    )
      rows.value = Object.entries(value).map(([key, value]) => ({ key, value }))
  },
  { immediate: true },
)
watch(
  rows,
  async () => {
    await nextTick()
    root.value
      ?.querySelectorAll<HTMLInputElement>('[data-config-key]')
      .forEach((element, index) => {
        const row = rows.value[index]!
        element.setCustomValidity(
          !row.key.trim()
            ? '请填写配置键'
            : rows.value.filter((r) => r.key === row.key).length > 1
              ? '配置键重复'
              : '',
        )
      })
  },
  { deep: true },
)
function change() {
  emit(
    'update:modelValue',
    Object.fromEntries(rows.value.filter((r) => r.key).map((r) => [r.key, r.value])),
  )
}
</script>
<template>
  <div ref="root" class="config-rows">
    <div v-for="(row, index) in rows" :key="index" class="config-row">
      <input
        data-config-key
        required
        v-model="row.key"
        :aria-label="'配置键 ' + (index + 1)"
        list="flink-config-keys"
        placeholder="配置键"
        :aria-invalid="!!row.key && rows.filter((r) => r.key === row.key).length > 1"
        @change="change"
      /><span>:</span
      ><input
        v-model="row.value"
        :aria-label="'配置值 ' + (index + 1)"
        placeholder="值"
        @input="change"
      /><button
        type="button"
        class="text-button"
        :aria-label="'移除配置 ' + (index + 1)"
        @click="
          rows.splice(index, 1)
          change()
        "
      >
        ×</button
      ><small>{{ hints[row.key] || '自定义 Flink 配置，请确认运行版本支持该参数。' }}</small>
    </div>
    <datalist id="flink-config-keys">
      <option v-for="(hint, key) in hints" :key="key" :value="key">{{ hint }}</option></datalist
    ><button type="button" class="button" @click="rows.push({ key: '', value: '' })">
      ＋ 添加配置
    </button>
  </div>
</template>
