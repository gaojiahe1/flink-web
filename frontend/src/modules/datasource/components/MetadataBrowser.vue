<script setup lang="ts">
import HelpTip from '../../../shared/components/form/HelpTip.vue'
import { ref, onBeforeUnmount } from 'vue'
import { request, jsonBody } from '../../../shared/api/http'
import type { ColumnDefinition, MetadataResult } from '../../flink-catalog/api/catalogs'
const props = defineProps<{ endpoint: string; actionLabel?: string }>()
const emit = defineEmits<{
  select: [value: { database: string; table: string; columns: ColumnDefinition[] }]
}>()
const databases = ref<string[]>([]),
  tables = ref<{ name: string; type: string }[]>([]),
  columns = ref<ColumnDefinition[]>([])
const database = ref(''),
  table = ref(''),
  busy = ref(false),
  error = ref(''),
  truncated = ref(false),
  queried = ref(false)
let sequence = 0
onBeforeUnmount(() => sequence++)
async function browse(level: 'databases' | 'tables' | 'columns') {
  const current = ++sequence
  busy.value = true
  error.value = ''
  if (level === 'databases') {
    database.value = ''
    databases.value = []
  }
  if (level !== 'columns') {
    table.value = ''
    tables.value = []
  }
  columns.value = []
  try {
    const result = await request<MetadataResult>(props.endpoint, {
      method: 'POST',
      ...jsonBody({ database: database.value, table: table.value }),
    })
    if (current !== sequence) return
    if (result.databases) databases.value = result.databases
    if (result.tables) tables.value = result.tables
    if (result.columns) columns.value = result.columns
    truncated.value = result.truncated
    queried.value = true
  } catch (e) {
    if (current === sequence) error.value = (e as Error).message
  } finally {
    if (current === sequence) busy.value = false
  }
}
function chooseDatabase() {
  void browse('tables')
}
function chooseTable() {
  void browse('columns')
}
function select() {
  emit('select', { database: database.value, table: table.value, columns: columns.value })
}
</script>
<template>
  <section class="metadata-browser">
    <div class="field-heading">
      <strong
        >库表浏览
        <HelpTip
          text="仅点击时读取元数据，不读取业务行。最多返回 200 个库/表、100 个字段。PostgreSQL 浏览登记数据库的指定 Schema。" /></strong
      ><button type="button" class="button" :disabled="busy" @click="browse('databases')">
        {{ busy ? '读取中…' : '加载数据库' }}
      </button>
    </div>
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <label
      >数据库<select
        v-model="database"
        :disabled="busy || !databases.length"
        @change="chooseDatabase"
      >
        <option value="" disabled>选择数据库</option>
        <option v-for="db in databases" :key="db">{{ db }}</option>
      </select></label
    >
    <label
      >数据表<select v-model="table" :disabled="busy || !tables.length" @change="chooseTable">
        <option value="" disabled>选择数据表</option>
        <option v-for="item in tables" :key="item.name" :value="item.name">
          {{ item.name }} · {{ item.type }}
        </option>
      </select></label
    >
    <p v-if="truncated" class="notice">列表超过 200 项，仅显示部分结果。</p>
    <p v-if="queried && !busy && !error && !databases.length" class="muted">
      当前账号没有可见数据库。
    </p>
    <div v-if="columns.length" class="metadata-columns">
      <div v-for="column in columns" :key="column.name">
        <code>{{ column.name }}</code
        ><span
          >{{ column.type }}{{ column.primaryKey ? ' · PK' : ''
          }}{{ column.nullable === false ? ' · NOT NULL' : '' }}</span
        >
      </div>
      <button
        v-if="actionLabel"
        type="button"
        class="button full-width"
        :disabled="busy"
        @click="select"
      >
        {{ actionLabel }}
      </button>
    </div>
  </section>
</template>
