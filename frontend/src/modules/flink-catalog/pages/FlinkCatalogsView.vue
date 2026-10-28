<script setup lang="ts">
import { showValidationError } from '../../../shared/utils/fieldValidation'
import { confirmDialog } from '../../../shared/utils/confirmDialog'
import ManualRefreshButton from '../../../shared/components/form/ManualRefreshButton.vue'
import HelpTip from '../../../shared/components/form/HelpTip.vue'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import PageHeader from '../../../shared/components/layout/PageHeader.vue'
import CodeEditor from '../../../shared/components/editor/CodeEditor.vue'
import CatalogExplorer from '../components/CatalogExplorer.vue'
import CatalogConnections from '../components/CatalogConnections.vue'
import ArtifactPicker, { type Artifact } from '../../artifact/components/ArtifactPicker.vue'
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
const connectionsOpen = ref(false)
async function refreshConnections() {
  try {
    sources.value = await request('/api/catalog/datasources')
  } catch (e) {
    error.value = (e as Error).message
  }
}
const admin = computed(() => currentUser.value?.roles.includes('ROLE_ADMIN'))
const developer = computed(() =>
  currentUser.value?.roles.some((r) => ['ROLE_ADMIN', 'ROLE_DEVELOPER'].includes(r)),
)
const initial = (): FlinkCatalog => ({
  id: '',
  name: '',
  type: 'jdbc',
  defaultDatabase: 'default_database',
  dataSourceId: '',
  hiveConfDir: '/opt/flink/usrlib',
  hiveSiteArtifactId: '',
  hiveVersion: '3.1.3',
  metastoreUris: '',
  dependencyArtifactIds: [],
  description: '',
  revision: 0,
  warehouse: 's3://bucket/warehouse/',
})
const form = reactive(initial())
const editor = ref<HTMLDialogElement>(),
  uploading = ref(false),
  detailTab = ref('objects'),
  usage = ref<{ id: string; name: string }[]>([])
let disposed = false
onBeforeUnmount(() => {
  disposed = true
  editor.value?.close()
})
watch(editing, async (value) => {
  await nextTick()
  if (disposed) return
  if (value) editor.value?.showModal()
  else editor.value?.close()
})
function choose(row: FlinkCatalog) {
  selected.value = row
  detailTab.value = 'objects'
  snippet.value = undefined
  error.value = ''
  message.value = ''
}
async function tab(value: string) {
  detailTab.value = value
  if (!selected.value) return
  if (value === 'configuration') await details(selected.value)
  if (value === 'usage') {
    const id = selected.value.id
    error.value = ''
    busy.value = true
    try {
      const result = await catalogsApi.usage(id)
      if (!disposed && selected.value?.id === id) usage.value = result.items
    } catch (cause) {
      if (!disposed) error.value = (cause as Error).message
    } finally {
      busy.value = false
    }
  }
}
function uploaded(file: Artifact, hive = false) {
  if (hive) form.hiveSiteArtifactId = file.id
  else form.dependencyArtifactIds = [...new Set([...(form.dependencyArtifactIds || []), file.id])]
}
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
    selected.value = rows.value.find((row) => row.id === selected.value?.id) || rows.value[0]
    detailTab.value = 'objects'
    snippet.value = undefined
  } catch (e) {
    error.value = showValidationError((e as Error).message)
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
    error.value = showValidationError((e as Error).message)
  } finally {
    busy.value = false
  }
}
function sourceChanged() {
  const source = sources.value.find((s) => s.id === form.dataSourceId)
  if (source) form.defaultDatabase = source.database
}
async function save() {
  if (busy.value || uploading.value) return
  if (!element.value?.reportValidity()) return
  busy.value = true
  error.value = ''
  try {
    const saved = await catalogsApi.save({ ...form })
    rows.value = [...rows.value.filter((r) => r.id !== saved.id), saved]
    editing.value = false
    selected.value = saved
    detailTab.value = 'objects'
    snippet.value = undefined
    message.value = 'Catalog 已登记，可在 SQL 开发中选择。不会创建远端数据库。'
  } catch (e) {
    error.value = showValidationError((e as Error).message)
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
    error.value = showValidationError((e as Error).message)
  } finally {
    busy.value = false
  }
}
async function remove(row: FlinkCatalog) {
  if (!(await confirmDialog('删除 Catalog「' + row.name + '」登记？不会删除远端库表。'))) return
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
    error.value = showValidationError((e as Error).message)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <PageHeader
    title="Catalog 工作台"
    description="管理 JDBC / Hive 目录，浏览库表与函数，在 SQL 中直接引用。"
    ><template #actions
      ><button v-if="admin" class="button" @click="connectionsOpen = true">管理 JDBC 连接</button
      ><ManualRefreshButton :action="load" :disabled="loading || busy" label="刷新目录" /><button
        v-if="admin"
        class="button primary"
        :disabled="busy"
        @click="edit()"
      >
        ＋ 注册 Catalog
      </button></template
    ></PageHeader
  >
  <p v-if="error && !editing" class="notice error" role="alert">{{ error }}</p>
  <p v-if="message" class="notice" role="status">{{ message }}</p>
  <section class="catalog-workspace">
    <aside class="catalog-profiles" aria-label="Catalog 目录列表">
      <div class="field-heading">
        <strong>已登记目录</strong><span class="count">{{ rows.length }}</span>
      </div>
      <input v-model="query" placeholder="搜索 Catalog 名称 / 标识" />
      <select v-model="kind" aria-label="Catalog 类型筛选">
        <option value="">全部类型</option>
        <option value="jdbc">JDBC</option>
        <option value="hive">Hive</option>
        <option value="memory">内存</option>
        <option value="paimon">Paimon</option>
        <option value="iceberg">Iceberg</option>
      </select>
      <div class="catalog-profile-items">
        <button
          v-for="row in filtered"
          :key="row.id"
          :disabled="busy"
          :class="{ active: selected?.id === row.id }"
          @click="choose(row)"
        >
          <span class="badge neutral">{{ typeLabel(row.type) }}</span
          ><strong>{{ row.name }}</strong
          ><small>{{ row.id }} · {{ row.defaultDatabase }}</small>
        </button>
        <p v-if="!filtered.length" class="empty-state">
          {{ loading ? '读取中…' : '暂无目录，注册 JDBC 或 Hive 开始使用' }}
        </p>
      </div>
      <p class="muted">目录保存连接与依赖定义。元数据按需读取，不查询业务行。</p>
    </aside>
    <main v-if="selected" class="catalog-main">
      <header class="catalog-heading">
        <div>
          <h3>{{ selected.name }}</h3>
          <p class="muted">
            {{ selected.id }} · 默认数据库 {{ selected.defaultDatabase }} · 修订
            {{ selected.revision }}
          </p>
        </div>
        <div class="toolbar-actions">
          <button v-if="admin" class="button" :disabled="busy" @click="testCatalog(selected)">
            验证连接</button
          ><RouterLink class="button" :to="{ path: '/sql', query: { catalog: selected.id } }"
            >在 SQL 开发中使用 ↗</RouterLink
          ><button v-if="admin" class="button" :disabled="busy" @click="edit(selected)">
            编辑配置</button
          ><button
            v-if="admin"
            class="text-button danger"
            :disabled="busy"
            @click="remove(selected)"
          >
            删除登记
          </button>
        </div>
      </header>
      <p class="catalog-level muted">
        配置保存 ≠ 协议连接 ≠ Flink 运行验证。请核对匹配的插件、驱动和存储权限。
      </p>
      <nav class="catalog-detail-tabs" aria-label="Catalog 详情">
        <button
          v-for="item in [
            { id: 'objects', name: '库表与函数' },
            { id: 'configuration', name: '初始化 SQL' },
            { id: 'dependencies', name: '运行依赖' },
            { id: 'usage', name: '引用作业' },
          ]"
          :key="item.id"
          :disabled="busy"
          :class="{ active: detailTab === item.id }"
          @click="tab(item.id)"
        >
          {{ item.name }}
        </button>
      </nav>
      <CatalogExplorer
        v-if="detailTab === 'objects'"
        :key="selected.id"
        :catalog="selected"
        :can-browse="!!developer"
      />
      <section v-else-if="detailTab === 'configuration'" class="catalog-info">
        <h3>发布 / 验证时初始化</h3>
        <p class="muted">{{ snippet?.notice }}</p>
        <CodeEditor
          :model-value="snippet?.sql || ''"
          language="sql"
          label="Catalog 初始化 SQL"
          readonly
        />
      </section>
      <section v-else-if="detailTab === 'dependencies'" class="catalog-info">
        <h3>运行依赖</h3>
        <p>
          {{
            selected.type === 'hive'
              ? '需要匹配的 Hive SQL Connector、Hadoop 依赖和 hive-site.xml。'
              : '需要 Flink JDBC Catalog / Connector 及数据库驱动。'
          }}
        </p>
        <ul>
          <li v-for="file in selected.dependencyArtifactIds || []" :key="file">
            <code>{{ file }}</code>
          </li>
        </ul>
        <p v-if="!selected.dependencyArtifactIds?.length" class="notice">
          未选择附加 JAR，请确认镜像内已有所需插件或编辑配置添加。
        </p>
        <p v-if="selected.type === 'hive'">
          Hive {{ selected.hiveVersion || '未指定' }} · {{ selected.hiveConfDir }}<br />{{
            selected.hiveSiteArtifactId || '使用已有挂载目录，平台未核验目录文件'
          }}<br />{{ selected.metastoreUris }}
        </p>
        <p class="muted">下次发布或验证时由统一下载器装入，不改变运行中的作业。</p>
      </section>
      <section v-else class="catalog-info">
        <h3>可读引用作业 · {{ usage.length }}</h3>
        <div v-for="item in usage" :key="item.id" class="source-item">
          <strong>{{ item.name }}</strong
          ><RouterLink class="text-button" :to="{ path: '/sql', query: { draft: item.id } }"
            >打开 SQL ↗</RouterLink
          >
        </div>
        <p v-if="!usage.length" class="empty-state">
          暂无当前账号可读的引用，删除保护仍核对全部草稿。
        </p>
      </section>
    </main>
    <div v-else class="empty-state catalog-main">选择或注册一个目录</div>
  </section>
  <dialog
    ref="editor"
    class="catalog-editor"
    aria-label="Catalog 配置"
    @cancel.prevent="!busy && !uploading && (editing = false)"
  >
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <section v-if="editing" class="panel form-panel compact-form detail-panel">
      <h3>{{ form.revision ? '编辑 Catalog' : '注册 Catalog' }}</h3>
      <form ref="element" @submit.prevent="save">
        <fieldset class="plain-fieldset" :disabled="busy || uploading">
          <div class="field-grid">
            <label
              >Catalog 标识<input
                data-field="id"
                v-model="form.id"
                required
                pattern="[a-z_][a-z0-9_]{0,63}"
                :disabled="!!form.revision" /><HelpTip text="SQL 中的目录名，保存后不可修改。"
            /></label>
            <label
              >显示名称<input data-field="name" v-model="form.name" required maxlength="100"
            /></label>
            <label
              >Catalog 类型<select
                data-field="type"
                v-model="form.type"
                aria-label="Catalog 类型"
                @change="typeChanged"
              >
                <option v-if="form.type === 'memory'" value="memory">
                  内存 Catalog（已有配置）
                </option>
                <option value="jdbc">JDBC · MySQL / PostgreSQL</option>
                <option value="hive">Hive Catalog</option>
                <option v-if="form.type === 'paimon'" value="paimon">Paimon · Filesystem</option>
                <option v-if="form.type === 'iceberg'" value="iceberg">Iceberg · Hadoop</option>
              </select></label
            >
            <label
              >默认数据库<input
                data-field="defaultDatabase"
                v-model="form.defaultDatabase"
                required
                pattern="[A-Za-z_][A-Za-z0-9_\-]{0,63}"
            /></label>
            <label v-if="form.type === 'jdbc'"
              >JDBC 连接<select
                data-field="dataSourceId"
                v-model="form.dataSourceId"
                required
                @change="sourceChanged"
              >
                <option value="">选择 MySQL / PostgreSQL 连接</option>
                <option
                  v-for="source in sources.filter((s) =>
                    ['mysql', 'postgresql'].includes(s.connectorType),
                  )"
                  :key="source.id"
                  :value="source.id"
                >
                  {{ source.name }} · {{ source.database }}
                </option></select
              ><HelpTip
                text="复用连接与密钥引用；运行镜像需要 JDBC Catalog 插件及对应数据库驱动。"
              /><button
                v-if="admin"
                class="text-button"
                type="button"
                @click="connectionsOpen = true"
              >
                管理 JDBC 连接
              </button></label
            >
            <label v-if="form.type === 'hive'"
              >Hive 配置目录<input
                data-field="hiveConfDir"
                v-model="form.hiveConfDir"
                required
                placeholder="/opt/flink/conf/hive" /><HelpTip
                text="Flink 容器内的 hive-site.xml 所在目录；请通过 PodTemplate 挂载配置并安装 Hive 依赖。"
            /></label>
            <label v-if="['paimon', 'iceberg'].includes(form.type)"
              >Warehouse<input
                data-field="warehouse"
                v-model="form.warehouse"
                required
                placeholder="s3://bucket/warehouse/" /><HelpTip
                text="Paimon 使用 Filesystem Catalog，Iceberg 使用 Hadoop Catalog。需要匹配的运行时插件与存储权限；平台不直接访问湖存储。"
            /></label>
            <label class="wide-field"
              >描述<input data-field="description" v-model="form.description" maxlength="500"
            /></label>
          </div>
          <HelpTip
            text="保存仅登记配置。内存、JDBC、Hive 和湖存储目录均在 Flink 作业运行时初始化，平台不会创建远端数据库。"
          />

          <section v-if="form.type === 'hive'" class="hive-configuration">
            <h4>Hive 客户端配置</h4>
            <label
              >Hive 版本<select v-model="form.hiveVersion">
                <option>3.1.3</option>
                <option>2.3.9</option>
              </select></label
            >
            <ArtifactPicker
              v-model="form.hiveSiteArtifactId!"
              single
              category="dependency"
              :disabled="busy || uploading"
              @uploading="uploading = $event"
            />
            <p class="muted">
              请选择 hive-site.xml（包含 hive.metastore.uris）。发布时统一下载至
              /opt/flink/usrlib；配置文件选择后目录由平台确定。平台只读浏览支持非 SASL/TLS 的 Binary
              Thrift，不降级安全连接。
            </p>
            <details>
              <summary>已有容器挂载配置的兼容选项</summary>
              <label
                >Metastore URI<input
                  v-model="form.metastoreUris"
                  :readonly="!!form.hiveSiteArtifactId"
                  placeholder="thrift://host:9083"
              /></label>
            </details>
          </section>
          <h4>Catalog / Connector 与驱动依赖</h4>
          <ArtifactPicker
            v-model="form.dependencyArtifactIds!"
            category="dependency"
            :disabled="busy"
            @uploaded="uploaded($event)"
            @uploading="uploading = $event"
          />
          <p class="muted">
            依赖须与作业 Flink 版本兼容；镜像已包含时无需重复添加。登记与连接测试不会启动 Flink
            作业。
          </p>
        </fieldset>
        <div class="form-actions">
          <button
            type="button"
            class="button"
            :disabled="busy || uploading"
            @click="editing = false"
          >
            取消</button
          ><button class="button primary" :disabled="busy || uploading">保存 Catalog</button>
        </div>
      </form>
    </section>
  </dialog>
  <CatalogConnections
    v-if="connectionsOpen"
    @close="connectionsOpen = false"
    @changed="refreshConnections"
  />
</template>
<style scoped>
.catalog-workspace {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  min-height: 420px;
  height: var(--workbench-height);
  background: var(--color-surface);
  border: 1px solid var(--line);
  border-radius: 8px;
  overflow: hidden;
}
.catalog-profiles {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  border-right: 1px solid var(--line);
  min-width: 0;
  min-height: 0;
  background: var(--color-surface-soft);
}
.catalog-profile-items {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
.catalog-profile-items button {
  height: var(--table-row-height);
  padding: 8px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 2px 6px;
  margin-bottom: 6px;
  width: 100%;
  background: none;
  border: 0;
  text-align: left;
  border-radius: 6px;
}
.catalog-profile-items .badge {
  grid-column: 2;
  grid-row: 1;
  padding: 1px 4px;
  font-size: 10px;
}
.catalog-profile-items strong {
  grid-column: 1;
  grid-row: 1;
}
.catalog-profile-items small {
  grid-column: 1 / -1;
  grid-row: 2;
}
.catalog-profile-items strong,
.catalog-profile-items small {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.catalog-profile-items button.active {
  background: var(--color-primary-soft);
  color: var(--blue);
}
.catalog-profile-items strong,
.catalog-profile-items small {
  display: block;
  overflow-wrap: anywhere;
  margin-top: 4px;
}
.catalog-main {
  min-width: 0;
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
}
.catalog-heading {
  padding: 12px 16px;
  display: flex;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.catalog-heading p,
.catalog-level,
.catalog-profiles p {
  font-size: var(--font-small);
}
.catalog-level {
  margin: 0 18px 10px;
}
.catalog-detail-tabs {
  display: flex;
  gap: 18px;
  padding: 0 18px;
  border-bottom: 1px solid var(--line);
  flex: none;
  overflow-x: auto;
}
.catalog-detail-tabs button {
  white-space: nowrap;
  flex: none;
  background: none;
  border: 0;
  border-bottom: 2px solid transparent;
  padding: 7px 0;
  color: var(--color-muted);
  font-size: var(--font-small);
}
.catalog-detail-tabs button.active {
  border-color: var(--blue);
  color: var(--blue);
}
.catalog-main :deep(.catalog-explorer) {
  flex: 1;
  min-height: 0;
}
.catalog-info {
  padding: 16px;
  overflow: auto;
}
.catalog-info > * {
  margin-bottom: 12px;
}
.catalog-info .code-editor {
  height: 400px;
}
.catalog-editor {
  width: min(900px, calc(100vw - 32px));
  max-height: 90dvh;
  overflow: auto;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 8px;
  color: var(--color-text);
}
.catalog-editor::backdrop {
  background: var(--color-overlay);
}
.catalog-editor .form-panel {
  padding: 0;
  margin: 0;
  border: 0;
}
.catalog-editor fieldset > * {
  margin-bottom: 12px;
}
.catalog-editor .muted,
small {
  color: var(--color-muted);
  font-size: var(--font-small);
}
@media (max-width: 1100px) {
  .catalog-workspace {
    grid-template-columns: 200px minmax(0, 1fr);
    height: auto;
  }
}
@media (max-width: 700px) {
  .catalog-workspace {
    grid-template-columns: 1fr;
  }
  .catalog-profiles {
    border-right: 0;
    border-bottom: 1px solid var(--line);
  }
  .catalog-profile-items {
    max-height: 220px;
  }
  .catalog-editor .field-grid {
    grid-template-columns: 1fr;
  }
}
</style>
