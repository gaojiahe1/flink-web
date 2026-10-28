<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { catalogsApi, type FlinkCatalog, type ColumnDefinition } from '../api/catalogs'
import ManualRefreshButton from '../../../shared/components/form/ManualRefreshButton.vue'
import CodeEditor from '../../../shared/components/editor/CodeEditor.vue'
import { copyText } from '../../../shared/utils/clipboard'

const props = defineProps<{
  catalog: FlinkCatalog
  compact?: boolean
  canBrowse: boolean
  insertable?: boolean
}>()
const emit = defineEmits<{
  select: [
    value: {
      catalogId: string
      database: string
      table: string
      columns: ColumnDefinition[]
      sql: string
    },
  ]
}>()
const databases = ref<string[]>([]),
  objects = ref<{ name: string; type: string }[]>([]),
  columns = ref<ColumnDefinition[]>([])
const database = ref(''),
  object = ref(''),
  query = ref(''),
  tab = ref<'tables' | 'functions'>('tables'),
  busy = ref(false),
  queried = ref(false)
const error = ref(''),
  note = ref(''),
  checkedAt = ref(''),
  truncated = ref(false),
  functionClass = ref(''),
  copied = ref(false)
let epoch = 0,
  controller: AbortController | undefined
const quote = (s: string) => '`' + s.replaceAll('`', '``') + '`'
const reference = computed(() =>
  [props.catalog.id, database.value, object.value].map(quote).join('.'),
)
const sql = computed(() =>
  !object.value
    ? ''
    : tab.value === 'tables'
      ? `SELECT ${columns.value.length ? columns.value.map((c) => quote(c.name)).join(', ') : '*'}\nFROM ${reference.value};`
      : `SELECT ${reference.value}(/* 参数 */);`,
)
const filtered = computed(() =>
  objects.value.filter((o) => o.name.toLowerCase().includes(query.value.toLowerCase())),
)
function reset() {
  epoch++
  controller?.abort()
  busy.value = false
  query.value = ''
  tab.value = 'tables'
  databases.value = []
  objects.value = []
  columns.value = []
  database.value = ''
  object.value = ''
  error.value = ''
  note.value = ''
  checkedAt.value = ''
  queried.value = false
  functionClass.value = ''
  truncated.value = false
}
watch(() => [props.catalog.id, props.catalog.revision], reset)
onBeforeUnmount(() => {
  epoch++
  controller?.abort()
})
async function browse(level: 'databases' | 'objects' | 'details') {
  if (!props.canBrowse) return
  const version = ++epoch
  controller?.abort()
  controller = new AbortController()
  busy.value = true
  error.value = ''
  note.value = ''
  copied.value = false
  if (level === 'databases') {
    databases.value = []
    database.value = ''
    objects.value = []
  }
  if (level !== 'details') {
    object.value = ''
    objects.value = []
  }
  columns.value = []
  functionClass.value = ''
  try {
    if (level === 'databases') {
      const result = await catalogsApi.metadata(props.catalog.id, '', '', controller.signal)
      if (version !== epoch) return
      databases.value = result.databases || []
      checkedAt.value = result.checkedAt
      truncated.value = result.truncated
      queried.value = true
    } else if (tab.value === 'tables') {
      const result = await catalogsApi.metadata(
        props.catalog.id,
        database.value,
        level === 'details' ? object.value : '',
        controller.signal,
      )
      if (version !== epoch) return
      if (level === 'objects') objects.value = result.tables || []
      else columns.value = result.columns || []
      checkedAt.value = result.checkedAt
      truncated.value = result.truncated
    } else {
      const result = await catalogsApi.functions(
        props.catalog.id,
        database.value,
        level === 'details' ? object.value : '',
        controller.signal,
      )
      if (version !== epoch) return
      if (level === 'objects') objects.value = result.functions || []
      else functionClass.value = result.className || ''
      note.value = result.message || ''
      checkedAt.value = result.checkedAt
      truncated.value = result.truncated
    }
  } catch (cause) {
    if (version === epoch) error.value = (cause as Error).message
  } finally {
    if (version === epoch) busy.value = false
  }
}
function changeTab(value: 'tables' | 'functions') {
  tab.value = value
  objects.value = []
  object.value = ''
  columns.value = []
  note.value = ''
  if (database.value) void browse('objects')
}
function choose(name: string) {
  object.value = name
  void browse('details')
}
async function copy() {
  try {
    await copyText(sql.value)
    copied.value = true
  } catch {
    error.value = '复制失败，请从下方 SQL 框中复制'
  }
}
</script>
<template>
  <section class="catalog-explorer" :class="{ compact }" aria-label="Catalog 对象浏览">
    <div class="catalog-object-list">
      <div class="explorer-controls">
        <strong>数据库与对象</strong
        ><ManualRefreshButton
          :action="() => browse('databases')"
          :disabled="busy || !canBrowse || !['jdbc', 'hive'].includes(catalog.type)"
          label="读取数据库"
        />
      </div>
      <p v-if="!canBrowse" class="muted">元数据浏览需要管理员或开发者权限。</p>
      <p v-if="!['jdbc', 'hive'].includes(catalog.type)" class="muted">
        此目录元数据由 Flink 运行时管理。当前重设计面向 JDBC 与 Hive，原登记保留兼容。
      </p>
      <label
        >数据库<select
          v-model="database"
          aria-label="Catalog 数据库"
          :disabled="busy || !databases.length"
          @change="browse('objects')"
        >
          <option value="" disabled>先读取并选择数据库</option>
          <option v-for="db in databases" :key="db">{{ db }}</option>
        </select></label
      >
      <nav class="object-tabs" aria-label="Catalog 对象类型">
        <button :class="{ active: tab === 'tables' }" @click="changeTab('tables')">表 / 视图</button
        ><button :class="{ active: tab === 'functions' }" @click="changeTab('functions')">
          函数
        </button>
      </nav>
      <input v-model="query" placeholder="筛选对象名称" aria-label="筛选 Catalog 对象" />
      <div class="catalog-object-items" aria-label="Catalog 对象列表">
        <button
          v-for="item in filtered"
          :key="item.name"
          :class="{ active: object === item.name }"
          :disabled="busy"
          @click="choose(item.name)"
        >
          <span>{{ item.name }}</span
          ><small>{{ item.type }}</small>
        </button>
        <p v-if="!filtered.length" class="empty-state">
          {{
            busy
              ? '正在读取元数据…'
              : database
                ? '暂无对象，或尚未读取此类对象'
                : queried
                  ? '请选择数据库'
                  : '点击“读取数据库”开始浏览'
          }}
        </p>
      </div>
      <p v-if="checkedAt" class="catalog-time muted">
        本次查询：{{ new Date(checkedAt).toLocaleTimeString() }} · 按需读取
      </p>
      <p v-if="truncated" class="notice">
        结果已达展示上限（对象200项 / 字段100项），不能视为完整清单。
      </p>
    </div>
    <div class="catalog-object-detail">
      <p v-if="error" class="notice error" role="alert">{{ error }}</p>
      <p v-if="note" class="notice">{{ note }}</p>
      <template v-if="object"
        ><div class="field-heading">
          <h3>{{ object }}</h3>
          <span class="muted">{{ tab === 'tables' ? '表 / 视图' : '函数' }}</span>
        </div>
        <p class="muted catalog-qualified">{{ reference }}</p>
        <table v-if="tab === 'tables' && columns.length" class="catalog-schema">
          <thead>
            <tr>
              <th>字段</th>
              <th>类型</th>
              <th>约束 / 分区</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="col in columns" :key="col.name">
              <td>
                {{ col.name }}<small v-if="col.comment">{{ col.comment }}</small>
              </td>
              <td>
                <code>{{ col.type }}</code>
              </td>
              <td>
                {{ col.primaryKey ? 'PK' : '' }} {{ col.nullable === false ? 'NOT NULL' : '' }}
                {{ col.partition ? '分区' : '' }}
              </td>
            </tr>
          </tbody>
        </table>
        <p v-if="functionClass" class="notice">实现类：{{ functionClass }}</p>
        <div class="toolbar-actions">
          <button class="button" :disabled="busy || !!error" @click="copy">
            {{ copied ? '已复制' : '复制引用 SQL' }}</button
          ><button
            v-if="insertable"
            class="button primary"
            :disabled="busy || !!error"
            @click="
              emit('select', { catalogId: catalog.id, database, table: object, columns, sql })
            "
          >
            插入 SQL
          </button>
        </div>
        <CodeEditor :model-value="sql" language="sql" label="Catalog 引用 SQL" readonly />
        <p class="muted">
          仅生成引用，不查询业务数据。SELECT 可用于 SQL 验证；发布 Application 仍需明确的 INSERT
          目标。
        </p>
      </template>
      <div v-else class="empty-state">
        <h3>浏览后选择一个对象</h3>
        <p>查看字段、分区或函数，并复制到 SQL 工作台。</p>
      </div>
    </div>
  </section>
</template>
<style scoped>
.catalog-explorer {
  display: grid;
  grid-template-columns: minmax(220px, 30%) minmax(0, 1fr);
  min-height: 480px;
  min-width: 0;
  height: 100%;
}
.catalog-object-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  min-height: 0;
  padding: 16px;
  border-right: 1px solid var(--line);
}
.explorer-controls {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.object-tabs {
  display: flex;
  border-bottom: 1px solid var(--line);
}
.object-tabs button {
  flex: 1;
  border: 0;
  background: none;
  padding: 8px;
  color: var(--color-muted);
}
.object-tabs button.active {
  color: var(--blue);
  border-bottom: 2px solid var(--blue);
}
.catalog-object-items {
  overflow: auto;
  flex: 1;
  min-height: 160px;
}
.catalog-object-items button {
  min-height: var(--table-row-height);
  width: 100%;
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 10px;
  border: 0;
  border-radius: 5px;
  background: none;
  text-align: left;
  font-size: var(--font-small);
}
.catalog-object-items button.active {
  background: var(--color-primary-soft);
  color: var(--blue);
}
.catalog-object-items span {
  overflow-wrap: anywhere;
}
small,
.catalog-time {
  font-size: var(--font-caption);
  color: var(--color-muted);
}
.catalog-object-detail {
  padding: 20px;
  min-width: 0;
  overflow: auto;
}
.catalog-qualified {
  margin: 6px 0 16px;
  overflow-wrap: anywhere;
  font-size: var(--font-small);
}
.catalog-schema {
  margin-bottom: 16px;
  font-size: var(--font-small);
}
.catalog-schema th,
.catalog-schema td {
  padding: 8px;
}
.catalog-schema small {
  display: block;
}
.code-editor {
  height: 180px;
  margin: 12px 0;
}
.compact {
  grid-template-columns: 1fr;
  height: auto;
  min-height: 0;
}
.compact .catalog-object-list {
  padding: 0 0 12px;
  border-right: 0;
}
.compact .catalog-object-items {
  max-height: 220px;
  flex: none;
}
.compact .catalog-object-detail {
  padding: 12px 0;
  border-top: 1px solid var(--line);
}
@media (max-width: 1100px) {
  .catalog-explorer {
    grid-template-columns: 1fr;
    height: auto;
  }
  .catalog-object-list {
    border-right: 0;
    border-bottom: 1px solid var(--line);
  }
  .catalog-object-items {
    max-height: 240px;
  }
}
</style>
