<script setup lang="ts">
import ManualRefreshButton from '../../../shared/components/form/ManualRefreshButton.vue'
import HelpTip from '../../../shared/components/form/HelpTip.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import PageHeader from '../../../shared/components/layout/PageHeader.vue'
import CodeEditor from '../../../shared/components/editor/CodeEditor.vue'
import MetadataBrowser from '../../datasource/components/MetadataBrowser.vue'
import { currentUser } from '../../permission/stores/auth'
import { request } from '../../../shared/api/http'
import { catalogsApi, type FlinkCatalog, type CatalogSnippet } from '../api/catalogs'
const rows = ref<FlinkCatalog[]>([]),
  sources = ref<{ id: string; name: string; database: string; connectorType: string }[]>([])
const query = ref(''),
  kind = ref(''),
  busy = ref(false),
  loading = ref(true),
  error = ref(''),
  message = ref(''),
  editing = ref(false)
const selected = ref<FlinkCatalog>(),
  snippet = ref<CatalogSnippet>(),
  element = ref<HTMLFormElement>()
const admin = computed(() => currentUser.value?.roles.includes('ROLE_ADMIN'))
const developer = computed(() =>
  currentUser.value?.roles.some((r) => ['ROLE_ADMIN', 'ROLE_DEVELOPER'].includes(r)),
)
const initial = (): FlinkCatalog => ({
  id: '',
  name: '',
  type: 'memory',
  defaultDatabase: 'default_database',
  dataSourceId: '',
  hiveConfDir: '/opt/flink/conf/hive',
  description: '',
  revision: 0,
  warehouse: 's3://bucket/warehouse/',
})
const form = reactive(initial())
const filtered = computed(() =>
  rows.value.filter(
    (r) =>
      (r.name + r.id + r.description).toLowerCase().includes(query.value.toLowerCase()) &&
      (!kind.value || kind.value === r.type),
  ),
)
const typeLabel = (type: string) =>
  ({
    memory: '内存 Catalog',
    jdbc: 'JDBC Catalog',
    hive: 'Hive Catalog',
    paimon: 'Paimon',
    iceberg: 'Iceberg',
  })[type] || type
async function load() {
  loading.value = true
  error.value = ''
  try {
    ;[rows.value, sources.value] = await Promise.all([
      catalogsApi.list(),
      request<{ id: string; name: string; database: string; connectorType: string }[]>(
        '/api/catalog/datasources',
      ),
    ])
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}
onMounted(load)
function edit(row?: FlinkCatalog) {
  Object.assign(form, initial(), row || {})
  editing.value = true
  error.value = ''
}
function typeChanged() {
  form.defaultDatabase = ['hive', 'paimon', 'iceberg'].includes(form.type)
    ? 'default'
    : 'default_database'
}
async function testCatalog(row: FlinkCatalog) {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const result = await catalogsApi.test(row.id)
    message.value = result.message
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
function sourceChanged() {
  const source = sources.value.find((s) => s.id === form.dataSourceId)
  if (source) form.defaultDatabase = source.database
}
async function save() {
  if (!element.value?.reportValidity()) return
  busy.value = true
  error.value = ''
  try {
    const saved = await catalogsApi.save({ ...form })
    rows.value = [...rows.value.filter((r) => r.id !== saved.id), saved]
    editing.value = false
    selected.value = undefined
    snippet.value = undefined
    message.value = 'Catalog 已登记，可在 SQL 开发中选择。不会创建远端数据库。'
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function details(row: FlinkCatalog) {
  busy.value = true
  error.value = ''
  selected.value = row
  snippet.value = undefined
  try {
    snippet.value = await catalogsApi.snippet(row.id)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function remove(row: FlinkCatalog) {
  if (!window.confirm('删除 Catalog「' + row.name + '」登记？不会删除远端库表。')) return
  busy.value = true
  error.value = ''
  try {
    await catalogsApi.remove(row)
    rows.value = rows.value.filter((r) => r.id !== row.id)
    if (selected.value?.id === row.id) {
      selected.value = undefined
      snippet.value = undefined
    }
    message.value = 'Catalog 登记已删除'
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <PageHeader
    ><template #title>Flink Catalog</template
    ><template #description>集中维护 Catalog，在 SQL 开发中选择目录、浏览库表并复用。</template
    ><template #actions
      ><button v-if="admin" class="button primary" :disabled="busy" @click="edit()">
        ＋ 注册 Catalog
      </button></template
    ></PageHeader
  >
  <div v-if="error" class="notice error" role="alert">{{ error }}</div>
  <div v-if="message" class="notice" role="status">{{ message }}</div>
  <section v-if="editing" class="panel form-panel compact-form detail-panel">
    <h3>{{ form.revision ? '编辑 Catalog' : '注册 Catalog' }}</h3>
    <form ref="element" @submit.prevent="save">
      <fieldset class="plain-fieldset" :disabled="busy">
        <div class="field-grid">
          <label
            >Catalog 标识<input
              v-model="form.id"
              required
              pattern="[a-z_][a-z0-9_]{0,63}"
              :disabled="!!form.revision" /><HelpTip text="SQL 中的目录名，保存后不可修改。"
          /></label>
          <label>显示名称<input v-model="form.name" required maxlength="100" /></label>
          <label
            >Catalog 类型<select
              v-model="form.type"
              aria-label="Catalog 类型"
              @change="typeChanged"
            >
              <option value="memory">内存 Catalog</option>
              <option value="jdbc">JDBC · MySQL / PostgreSQL</option>
              <option value="hive">Hive Catalog</option>
              <option value="paimon">Paimon · Filesystem</option>
              <option value="iceberg">Iceberg · Hadoop</option>
            </select></label
          >
          <label
            >默认数据库<input
              v-model="form.defaultDatabase"
              required
              pattern="[A-Za-z_][A-Za-z0-9_-]{0,63}"
          /></label>
          <label v-if="form.type === 'jdbc'"
            >JDBC 数据源<select v-model="form.dataSourceId" required @change="sourceChanged">
              <option value="">选择 MySQL / PostgreSQL 数据源</option>
              <option
                v-for="source in sources.filter((s) =>
                  ['mysql', 'postgresql'].includes(s.connectorType),
                )"
                :key="source.id"
                :value="source.id"
              >
                {{ source.name }} · {{ source.database }}
              </option></select
            ><HelpTip text="复用连接与密钥引用；运行镜像需要 JDBC Catalog 插件及对应数据库驱动。"
          /></label>
          <label v-if="form.type === 'hive'"
            >Hive 配置目录<input
              v-model="form.hiveConfDir"
              required
              placeholder="/opt/flink/conf/hive" /><HelpTip
              text="Flink 容器内的 hive-site.xml 所在目录；请通过 PodTemplate 挂载配置并安装 Hive 依赖。"
          /></label>
          <label v-if="['paimon', 'iceberg'].includes(form.type)"
            >Warehouse<input
              v-model="form.warehouse"
              required
              placeholder="s3://bucket/warehouse/" /><HelpTip
              text="Paimon 使用 Filesystem Catalog，Iceberg 使用 Hadoop Catalog。需要匹配的运行时插件与存储权限；平台不直接访问湖存储。"
          /></label>
          <label class="wide-field">描述<input v-model="form.description" maxlength="500" /></label>
        </div>
        <HelpTip
          text="保存仅登记配置。内存、JDBC、Hive 和湖存储目录均在 Flink 作业运行时初始化，平台不会创建远端数据库。"
        />
      </fieldset>
      <div class="form-actions">
        <button type="button" class="button" :disabled="busy" @click="editing = false">取消</button
        ><button class="button primary" :disabled="busy">保存 Catalog</button>
      </div>
    </form>
  </section>
  <section class="panel detail-panel">
    <div class="filter-bar">
      <input v-model="query" class="search" placeholder="搜索 Catalog 名称 / 标识" /><select
        v-model="kind"
        aria-label="Catalog 类型筛选"
      >
        <option value="">全部类型</option>
        <option value="memory">内存</option>
        <option value="jdbc">JDBC · MySQL / PostgreSQL</option>
        <option value="hive">Hive</option>
        <option value="paimon">Paimon</option>
        <option value="iceberg">Iceberg</option></select
      ><ManualRefreshButton :action="load" :disabled="loading || busy" />
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Catalog</th>
            <th>类型</th>
            <th>默认数据库</th>
            <th>修订</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in filtered" :key="row.id">
            <td>
              <strong>{{ row.name }}</strong
              ><small class="table-description">{{ row.id }}</small>
            </td>
            <td>
              <span class="badge neutral">{{ typeLabel(row.type) }}</span>
            </td>
            <td class="mono">{{ row.defaultDatabase }}</td>
            <td>{{ row.revision }}</td>
            <td>
              <button v-if="admin" class="text-button" :disabled="busy" @click="testCatalog(row)">
                {{ row.type === 'jdbc' ? '测试连接' : '校验配置' }}</button
              ><button class="text-button" :disabled="busy" @click="details(row)">
                查看 / 库表</button
              ><button v-if="admin" class="text-button" :disabled="busy" @click="edit(row)">
                编辑</button
              ><button
                v-if="admin"
                class="text-button danger"
                :disabled="busy"
                @click="remove(row)"
              >
                删除
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="loading" class="empty-state">正在加载…</div>
    <div v-else-if="!filtered.length" class="empty-state">
      暂无匹配的 Catalog，注册后可在 SQL 开发中选择。
    </div>
    <div class="panel-footer">
      共 {{ filtered.length }} 个 Catalog · 注册和刷新列表均不探测远端服务
    </div>
  </section>
  <section v-if="selected" class="panel form-panel detail-panel catalog-detail">
    <div class="field-heading">
      <h3>{{ selected.name }} · 初始化预览</h3>
      <button class="text-button" @click="selected = undefined">收起</button>
    </div>
    <p v-if="snippet" class="muted">{{ snippet.notice }}</p>
    <CodeEditor
      v-if="snippet"
      :model-value="snippet.sql"
      language="sql"
      readonly
      label="Catalog 初始化 SQL"
    />
    <MetadataBrowser
      v-if="developer && selected.type === 'jdbc'"
      :key="selected.id"
      :endpoint="'/api/sql/catalogs/' + selected.id + '/metadata'"
    />
    <HelpTip
      v-else
      text="库表浏览支持 JDBC。Hive、Paimon、Iceberg 插件在 Flink 运行镜像中加载，当前页面不执行远端湖表浏览。"
    />
  </section>
</template>
