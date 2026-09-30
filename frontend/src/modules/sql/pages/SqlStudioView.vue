<script setup lang="ts">
import { showValidationError } from '../../../shared/utils/fieldValidation'
import { confirmDialog } from '../../../shared/utils/confirmDialog'
import StateStorageForm from '../../../shared/components/form/StateStorageForm.vue'
import ManualRefreshButton from '../../../shared/components/form/ManualRefreshButton.vue'
import FlinkImageSelect from '../../../shared/components/form/FlinkImageSelect.vue'
import HelpTip from '../../../shared/components/form/HelpTip.vue'
import PageHeader from '../../../shared/components/layout/PageHeader.vue'
import MetadataBrowser from '../../datasource/components/MetadataBrowser.vue'
import {
  catalogsApi,
  type FlinkCatalog,
  type ColumnDefinition,
} from '../../flink-catalog/api/catalogs'
import SqlHistory from '../components/SqlHistory.vue'
import CodeEditor from '../../../shared/components/editor/CodeEditor.vue'
import MemoryInput from '../../../shared/components/form/MemoryInput.vue'
import FlinkConfigEditor from '../../../shared/components/editor/FlinkConfigEditor.vue'
import { parseDocument } from 'yaml'
import ArtifactPicker, { type Artifact } from '../../artifact/components/ArtifactPicker.vue'
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { request, jsonBody } from '../../../shared/api/http'
import { currentUser } from '../../permission/stores/auth'
import { requestId } from '../../../shared/utils/requestId'
import type { JobDraft } from '../../../shared/types/api'
type Catalog = {
  id: string
  name: string
  version?: string
  filename?: string
  flinkVersion?: string
  yaml?: string
}
type Runtime = {
  workspaceId: string
  imageId: string
  jarURI: string
  parallelism: number
  slots: number
  catalogId: string
  database: string
  jmCpu: number
  tmCpu: number
  jmMemory: string
  tmMemory: string
  upgradeMode: string
  dependencyArtifactIds: string[]
  flinkConfiguration: Record<string, string>
  podTemplate: Record<string, unknown>
}
type SqlDraft = {
  id: string
  name: string
  description: string
  sql: string
  runtime: Runtime
  revision: number
  jobId?: string
  publishedAt?: string
}
const router = useRouter()
const sqlNameInput = ref<HTMLInputElement>()
let nameCheck = 0
async function checkName() {
  const current = ++nameCheck
  const name = form.name
  sqlNameInput.value?.setCustomValidity('')
  if (selected.value || !name) return true
  const result = await request<{ available: boolean }>(
    '/api/jobs/name-availability?' + new URLSearchParams({ name, excludeId: linkedJob.value }),
  )
  if (nameCheck !== current || form.name !== name) return false
  const duplicate = !result.available || records.value.some((record) => record.name === name)
  sqlNameInput.value?.setCustomValidity(duplicate ? '作业名称已存在，请使用其他名称' : '')
  if (duplicate) showValidationError('作业名称已存在，请使用其他名称', document)
  return !duplicate
}
async function blurName() {
  try {
    await checkName()
  } catch (e) {
    if (sqlNameInput.value) sqlNameInput.value.setCustomValidity('名称校验失败，请重试')
    error.value = showValidationError('作业名称校验失败，请重试', document)
  }
}
const example =
  "-- 数据生成 → 控制台输出；运行镜像需包含 datagen / print 连接器\nCREATE TEMPORARY TABLE source_events (\n  id BIGINT,\n  payload STRING\n) WITH (\n  'connector' = 'datagen',\n  'rows-per-second' = '1'\n);\n\nCREATE TEMPORARY TABLE sink_events (\n  id BIGINT,\n  payload STRING\n) WITH ('connector' = 'print');\n\nINSERT INTO sink_events\nSELECT id, payload FROM source_events;"
const initialRuntime = (): Runtime => ({
  workspaceId: requestId(),
  catalogId: '',
  database: '',
  jmCpu: 1,
  tmCpu: 1,
  imageId: '',
  jarURI: 'local:///opt/flink/lib/flink-web-sql-runner.jar',
  parallelism: 1,
  slots: 1,
  jmMemory: '1024m',
  tmMemory: '2048m',
  upgradeMode: 'savepoint',
  dependencyArtifactIds: [],
  flinkConfiguration: {},
  podTemplate: {},
})
const form = reactive({ name: '', description: '', sql: example, revision: 0 })
const runtime = reactive<Runtime>(initialRuntime())
const records = ref<SqlDraft[]>([]),
  images = ref<Catalog[]>([]),
  artifacts = ref<Artifact[]>([])
const stateConfigBusy = ref(false)
const artifactUploading = ref(false),
  configValid = ref(true),
  templates = ref<Catalog[]>([]),
  podTemplateId = ref('')
const flinkCatalogs = ref<FlinkCatalog[]>([]),
  catalogPreview = ref(''),
  explorerCollapsed = ref(false),
  maximized = ref(false)
const currentCatalog = computed(() => flinkCatalogs.value.find((c) => c.id === runtime.catalogId))
const canBrowse = computed(() =>
  currentUser.value?.roles.some((r) => ['ROLE_ADMIN', 'ROLE_DEVELOPER'].includes(r)),
)
const sideTab = ref(''),
  sources = ref<{ id: string; name: string; connectorType: string }[]>([]),
  sourceQuery = ref('')
const filteredSources = computed(() =>
  sources.value.filter((s) =>
    (s.name + s.id + s.connectorType).toLowerCase().includes(sourceQuery.value.toLowerCase()),
  ),
)
const configValues = computed({
  get: () => {
    try {
      return JSON.parse(config.value)
    } catch {
      return {}
    }
  },
  set: (value) => (config.value = JSON.stringify(value)),
})
function uploaded(file: Artifact) {
  artifacts.value = [...artifacts.value.filter((a) => a.id !== file.id), file]
}
const selected = ref(''),
  linkedJob = ref(''),
  query = ref(''),
  busy = ref(false),
  loading = ref(true)
const error = ref(''),
  message = ref(''),
  preview = ref(''),
  config = ref('{}'),
  pod = ref('{}')
const check = ref<{
  level: string
  statementCount: number
  insertCount: number
  message: string
} | null>(null)
const editable = computed(() =>
  currentUser.value?.roles.some((r) => r === 'ROLE_ADMIN' || r === 'ROLE_DEVELOPER'),
)
const filtered = computed(() =>
  records.value.filter((r) =>
    (r.name + r.description).toLowerCase().includes(query.value.toLowerCase()),
  ),
)
const bytes = computed(() => new TextEncoder().encode(form.sql).length)
const savedSnapshot = ref('')
const snapshot = () => JSON.stringify({ form, runtime, config: config.value, pod: pod.value })
const dirty = computed(() => savedSnapshot.value !== snapshot())
async function load() {
  records.value = await request<SqlDraft[]>('/api/sql/jobs')
}
onMounted(async () => {
  try {
    const result = await Promise.all([
      request<Catalog[]>('/api/catalog/images'),
      Promise.resolve([] as Artifact[]),
      load(),
      request<any[]>('/api/catalog/datasources'),
      request<Catalog[]>('/api/catalog/templates'),
      catalogsApi.list(),
    ])
    images.value = result[0]
    artifacts.value = result[1]
    sources.value = result[3]
    templates.value = result[4]
    flinkCatalogs.value = result[5]
    savedSnapshot.value = snapshot()
    if (!editable.value && records.value[0]) open(records.value[0], true)
  } catch (e) {
    error.value = showValidationError((e as Error).message)
  } finally {
    loading.value = false
  }
})
async function mayDiscard() {
  return !dirty.value || (await confirmDialog('当前 SQL 有未保存的修改，确认切换？'))
}
async function open(record: SqlDraft, force = false) {
  if (!force && !(await mayDiscard())) return
  selected.value = record.id
  linkedJob.value = record.jobId || ''
  Object.assign(form, {
    name: record.name,
    description: record.description,
    sql: record.sql,
    revision: record.revision,
  })
  Object.assign(runtime, initialRuntime(), JSON.parse(JSON.stringify(record.runtime)))
  if (record.jobId) runtime.workspaceId = record.jobId
  config.value = JSON.stringify(runtime.flinkConfiguration || {}, null, 2)
  pod.value = JSON.stringify(runtime.podTemplate || {}, null, 2)
  preview.value = ''
  check.value = null
  catalogPreview.value = ''
  error.value = ''
  message.value = ''
  savedSnapshot.value = snapshot()
}
async function reloadRestoredSql() {
  const id = selected.value
  if (!id) return
  const record = await request<SqlDraft>('/api/sql/jobs/' + id)
  if (id === selected.value) {
    open(record, true)
    await load()
    message.value = '已恢复 SQL 草稿，请检查后再发布。'
  }
}
async function create() {
  if (!(await mayDiscard())) return
  selected.value = ''
  linkedJob.value = ''
  Object.assign(form, { name: '', description: '', sql: example, revision: 0 })
  Object.assign(runtime, initialRuntime())
  config.value = '{}'
  pod.value = '{}'
  preview.value = ''
  check.value = null
  catalogPreview.value = ''
  error.value = ''
  message.value = ''
  savedSnapshot.value = snapshot()
}
async function applyPodTemplate() {
  const template = templates.value.find((t) => t.id === podTemplateId.value)
  if (!template?.yaml) return
  const doc = parseDocument(template.yaml)
  if (doc.errors.length) {
    error.value = '模板 YAML 存在语法错误'
    return
  }
  const value = doc.toJS()?.spec?.podTemplate
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    error.value = '模板不包含 spec.podTemplate'
    return
  }
  if (
    pod.value.trim() !== '{}' &&
    !(await confirmDialog('替换当前 PodTemplate？已注入的数据源环境变量也会被替换。'))
  )
    return
  pod.value = JSON.stringify(value, null, 2)
  message.value = '已复制模板 PodTemplate，可继续编辑'
  error.value = ''
}
function payload() {
  if (!configValid.value) throw new Error('请先修正 Flink 配置')
  return {
    ...form,
    runtime: {
      ...runtime,
      flinkConfiguration: JSON.parse(config.value),
      podTemplate: JSON.parse(pod.value),
    },
  }
}
async function persist() {
  if (!form.name || !/^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/.test(form.name)) {
    sideTab.value = 'runtime'
    await nextTick()
    throw new Error('请填写有效的 SQL 作业名称')
  }
  if (!(await checkName())) throw new Error('作业名称已存在，请使用其他名称')
  const record = await request<SqlDraft>('/api/sql/jobs/' + encodeURIComponent(form.name), {
    method: 'PUT',
    ...jsonBody(payload()),
  })
  selected.value = record.id
  form.revision = record.revision
  linkedJob.value = record.jobId || ''
  savedSnapshot.value = snapshot()
  await load()
  return record
}
async function run(task: () => Promise<void>) {
  if (busy.value || stateConfigBusy.value) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await task()
  } catch (e) {
    error.value = showValidationError((e as Error).message)
  } finally {
    busy.value = false
  }
}
function save() {
  return run(async () => {
    await persist()
    message.value = 'SQL 草稿已保存，运行中的作业不受影响。'
  })
}
function validate() {
  return run(async () => {
    check.value = null
    check.value = await request('/api/sql/validate', {
      method: 'POST',
      ...jsonBody({ sql: form.sql, runtime: payload().runtime }),
    })
    message.value = check.value!.message
  })
}
function render() {
  return run(async () => {
    const result = await request<{ yaml: string; message: string }>('/api/sql/preview', {
      method: 'POST',
      ...jsonBody(payload()),
    })
    preview.value = result.yaml
    message.value = result.message
  })
}
async function publish(deploy: boolean) {
  if (
    deploy &&
    !(await confirmDialog(
      '确认保存并部署当前 SQL？运行镜像需包含 SQL Runner 和连接器。已有同名部署会按升级策略变更。',
    ))
  )
    return
  return run(async () => {
    await persist()
    const job = await request<JobDraft>('/api/sql/jobs/' + selected.value + '/publish', {
      method: 'POST',
      ...jsonBody({ revision: form.revision }),
    })
    const updated = await request<SqlDraft>('/api/sql/jobs/' + selected.value)
    form.revision = updated.revision
    linkedJob.value = job.id
    savedSnapshot.value = snapshot()
    await load()
    if (deploy) {
      const result = await request<{ state: string; message: string }>(
        '/api/jobs/' + job.id + '/actions/deploy',
        { method: 'POST', ...jsonBody({ revision: job.revision, requestId: requestId() }) },
      )
      if (result.state === 'ACCEPTED') await router.push('/jobs')
      else message.value = result.state + '：' + result.message
    } else message.value = '已生成部署草稿。可前往作业管理部署、启停和创建快照。'
  })
}
async function remove() {
  if (!selected.value || !(await confirmDialog('删除 SQL 草稿「' + form.name + '」？'))) return
  return run(async () => {
    await request('/api/sql/jobs/' + selected.value + '?revision=' + form.revision, {
      method: 'DELETE',
    })
    savedSnapshot.value = snapshot()
    create()
    await load()
    message.value = 'SQL 草稿已删除'
  })
}
async function refreshSources() {
  try {
    sources.value = await request('/api/catalog/datasources')
  } catch (e) {
    error.value = showValidationError((e as Error).message)
  }
}
function insertSource(id: string) {
  return run(async () => {
    const snippet = await request<{ sql: string; env: { name: string; value: string }[] }>(
      '/api/sql/datasources/' + id + '/snippet',
    )
    const template = JSON.parse(pod.value)
    if (!template || typeof template !== 'object' || Array.isArray(template))
      throw new Error('PodTemplate 必须为 JSON 对象')
    if (snippet.env.length) {
      template.spec ??= {}
      template.spec.containers ??= []
      let main = template.spec.containers.find((c: any) => c.name === 'flink-main-container')
      if (!main) {
        main = { name: 'flink-main-container', env: [] }
        template.spec.containers.push(main)
      }
      main.env ??= []
      for (const env of snippet.env) {
        const existing = main.env.find((item: any) => item.name === env.name)
        if (existing && JSON.stringify(existing) !== JSON.stringify(env))
          throw new Error('数据源环境变量已存在且配置不同，请先检查 PodTemplate')
        if (!existing) main.env.push(env)
      }
    }
    pod.value = JSON.stringify(template, null, 2)
    form.sql = snippet.sql + '\n' + form.sql
    check.value = null
    message.value = '已插入建表语句和密钥引用。继续编写 INSERT，使用“执行 SQL”部署到 Flink。'
  })
}
function invalidateContext() {
  check.value = null
  catalogPreview.value = ''
  preview.value = ''
}
function selectCatalog() {
  runtime.database = currentCatalog.value?.defaultDatabase || ''
  catalogPreview.value = ''
  check.value = null
  preview.value = ''
}
function previewCatalog() {
  return run(async () => {
    const result = await catalogsApi.snippet(runtime.catalogId, runtime.database)
    catalogPreview.value = result.sql
    message.value = result.notice
  })
}
function refreshCatalogs() {
  return run(async () => {
    flinkCatalogs.value = await catalogsApi.list()
  })
}
function insertTable(value: { database: string; table: string; columns: ColumnDefinition[] }) {
  const quote = (name: string) =>
    String.fromCharCode(96) +
    name.replaceAll(String.fromCharCode(96), String.fromCharCode(96).repeat(2)) +
    String.fromCharCode(96)
  const reference = [runtime.catalogId, value.database, value.table].map(quote).join('.')
  const fields = value.columns.map((column) => quote(column.name)).join(', ')
  form.sql +=
    '\n\n-- Catalog 表引用示例：请将 target_table 替换为已定义的目标表\nINSERT INTO target_table\nSELECT ' +
    fields +
    '\nFROM ' +
    reference +
    ';\n'
  check.value = null
  message.value = '已追加完整表引用示例，请选择目标表后再检查和发布。'
}
function executeSql() {
  sideTab.value = 'runtime'
  void publish(true)
}
</script>

<template>
  <PageHeader eyebrow="DATA DEVELOPMENT" class="studio-title"
    ><template #title>SQL 开发</template
    ><template #description>编写 SQL → SQL 检查 → 发布为 Application 作业</template
    ><template #actions
      ><RouterLink class="button" to="/jobs">前往作业运维 ↗</RouterLink></template
    ></PageHeader
  >
  <div v-if="error" class="notice error" role="alert">{{ error }}</div>
  <div v-if="message" class="notice" role="status">{{ message }}</div>
  <section
    class="sql-workbench"
    :class="{
      'with-inspector': !!sideTab,
      'explorer-collapsed': explorerCollapsed,
      'studio-maximized': maximized,
    }"
  >
    <aside class="sql-explorer">
      <button
        class="explorer-toggle text-button"
        :aria-label="explorerCollapsed ? '展开草稿栏' : '收起草稿栏'"
        @click="explorerCollapsed = !explorerCollapsed"
      >
        {{ explorerCollapsed ? '›' : '‹ 收起' }}
      </button>
      <div v-show="!explorerCollapsed" class="explorer-content">
        <div class="explorer-title">
          <strong>SQL 草稿</strong
          ><HelpTip
            text="支持 CREATE CATALOG / DATABASE、CREATE TABLE / VIEW、USE CATALOG / DATABASE、INSERT INTO / OVERWRITE；多个 INSERT 合并为一个作业。保存草稿不会执行 SQL。"
          /><span class="count">{{ records.length }}</span>
        </div>
        <button
          v-if="editable"
          class="button primary full-width"
          :disabled="busy || artifactUploading || stateConfigBusy || loading"
          @click="create"
        >
          ＋ 新建 SQL
        </button>
        <input
          data-field="query"
          v-model="query"
          class="sql-search"
          placeholder="搜索 SQL 草稿"
          aria-label="搜索 SQL 草稿"
        />
        <div class="sql-file-list" aria-label="SQL 草稿列表">
          <p v-if="loading" class="muted">正在加载…</p>
          <p v-else-if="!filtered.length" class="muted empty-tree">
            暂无草稿<br />从右侧编辑器开始创建
          </p>
          <button
            v-for="record in filtered"
            :key="record.id"
            class="sql-file"
            :title="record.name"
            :class="{ active: selected === record.id }"
            :disabled="busy"
            @click="open(record)"
          >
            <span class="sql-file-icon">SQL</span
            ><span
              >{{ record.name }}<small>{{ record.jobId ? '已生成部署' : '开发草稿' }}</small></span
            >
          </button>
        </div>
      </div>
    </aside>
    <div class="sql-center">
      <div class="sql-tabbar">
        <span
          ><span class="sql-file-icon">SQL</span> {{ form.name || 'untitled' }}.sql
          <span v-if="dirty" class="unsaved-dot" title="有未保存的修改">●</span></span
        ><span class="muted"
          >{{ selected ? '修订 ' + form.revision : '新建草稿' }}
          <button class="text-button" @click="maximized = !maximized">
            {{ maximized ? '退出全屏' : '全屏编辑' }}
          </button></span
        >
      </div>
      <div class="sql-toolbar">
        <div class="toolbar-actions">
          <button
            v-if="editable"
            class="button"
            :disabled="busy || artifactUploading || stateConfigBusy || loading"
            @click="save"
          >
            保存 SQL</button
          ><button
            v-if="editable"
            class="button"
            :disabled="busy || artifactUploading || stateConfigBusy || loading"
            @click="validate"
          >
            SQL 检查</button
          ><button
            v-if="editable"
            class="text-button"
            :disabled="busy || artifactUploading || stateConfigBusy || loading"
            @click="render"
          >
            预览 YAML
          </button>
        </div>
        <button
          v-if="editable"
          class="button primary"
          :disabled="busy || artifactUploading || stateConfigBusy || loading"
          @click="executeSql"
        >
          执行 SQL</button
        ><span class="muted">{{ bytes.toLocaleString() }} / 32,768 B</span>
      </div>
      <div class="catalog-context">
        <label
          >当前 Catalog<select
            data-field="catalogId"
            v-model="runtime.catalogId"
            aria-label="当前 Catalog"
            :disabled="!editable || busy || loading"
            @change="selectCatalog"
          >
            <option value="">默认目录 / SQL 自行定义</option>
            <option v-for="item in flinkCatalogs" :key="item.id" :value="item.id">
              {{ item.name }} · {{ item.id }}
            </option>
            <option v-if="runtime.catalogId && !currentCatalog" :value="runtime.catalogId">
              {{ runtime.catalogId }}（登记已失效）
            </option>
          </select></label
        >
        <label
          >当前数据库<input
            data-field="database"
            v-model="runtime.database"
            :disabled="!runtime.catalogId || !editable || busy"
            placeholder="Catalog 默认数据库"
            @input="invalidateContext"
        /></label>
        <button class="text-button" :disabled="!runtime.catalogId || busy" @click="previewCatalog">
          初始化 SQL
        </button>
      </div>
      <CodeEditor
        data-field="sql"
        v-model="form.sql"
        language="sql"
        label="SQL 编辑器"
        :readonly="!editable || loading"
        @update:model-value="check = null"
      />
      <div class="sql-diagnostics">
        <div class="diagnostic-heading">
          检查结果 <span v-if="check" class="badge">{{ check.level }}</span>
        </div>
        <p v-if="check">
          {{ check.statementCount }} 条语句 / {{ check.insertCount }} 个写入。{{ check.message }}
        </p>
        <p v-else>
          保存不执行 SQL。选择镜像后按精确 Flink 版本检查语法；未安装对应 Parser 时明确提示
          BASIC。连接器与字段语义仍由运行时校验。
        </p>
        <p class="muted">
          SQL 中可用 <code v-pre>'{{env:DB_PASSWORD}}'</code> 引用 Pod
          环境变量。密钥在配置管理注册，通过 PodTemplate 的 Secret 引用注入。
        </p>
      </div>
      <details v-if="catalogPreview" class="sql-preview" open>
        <summary>Catalog 初始化 SQL（发布时自动前置）</summary>
        <CodeEditor
          :model-value="catalogPreview"
          language="sql"
          readonly
          label="Catalog 初始化 SQL"
        />
      </details>
      <details v-if="preview" class="sql-preview">
        <summary>FlinkDeployment YAML 预览</summary>
        <CodeEditor :model-value="preview" readonly label="SQL 部署 YAML" />
      </details>
    </div>
    <aside v-if="sideTab === 'runtime'" class="sql-runtime">
      <div class="field-heading">
        <h3>部署配置</h3>
        <button class="text-button" aria-label="收起部署配置" @click="sideTab = ''">×</button>
      </div>
      <p class="muted runtime-intro">发布后统一在作业管理页启停、刷新状态和创建快照。</p>
      <fieldset :disabled="!editable || busy || loading">
        <label
          >SQL 作业名称<input
            data-field="name"
            ref="sqlNameInput"
            v-model="form.name"
            @blur="blurName"
            @input="sqlNameInput?.setCustomValidity('')"
            required
            pattern="[a-z0-9]([\-a-z0-9]*[a-z0-9])?"
            placeholder="orders-sql"
            maxlength="63"
            :disabled="!!selected" /><HelpTip
            text="小写字母、数字、连字符；名称唯一，发布后自动分配编号，资源名为 flink-编号。"
        /></label>
        <label
          >描述<input
            data-field="description"
            v-model="form.description"
            maxlength="500"
            placeholder="描述数据流用途"
        /></label>
        <label
          >运行镜像<FlinkImageSelect
            data-field="imageId"
            v-model="runtime.imageId"
            :images="images"
            label="运行镜像" /><HelpTip
            text="镜像需包含 SQL Runner、Table Planner 及匹配版本的连接器。"
        /></label>
        <StateStorageForm
          v-model="configValues"
          :version="images.find((i) => i.id === runtime.imageId)?.flinkVersion || '1.20'"
          :scope-id="runtime.workspaceId"
          @busy="stateConfigBusy = $event"
          :disabled="busy || !editable"
        />
        <div class="runtime-grid">
          <label
            >并行度<input
              data-field="parallelism"
              v-model.number="runtime.parallelism"
              type="number"
              min="1"
              step="1" /></label
          ><label
            >每个 TM Slot<input
              data-field="slots"
              v-model.number="runtime.slots"
              type="number"
              min="1"
              step="1"
          /></label>
        </div>
        <div class="runtime-grid">
          <MemoryInput
            data-field="jmMemory"
            v-model="runtime.jmMemory"
            label="JM 内存"
          /><MemoryInput data-field="tmMemory" v-model="runtime.tmMemory" label="TM 内存" />
        </div>
        <div class="runtime-grid">
          <label
            >JM CPU<input
              data-field="jmCpu"
              v-model.number="runtime.jmCpu"
              type="number"
              min="0.1"
              step="any" /></label
          ><label
            >TM CPU<input
              data-field="tmCpu"
              v-model.number="runtime.tmCpu"
              type="number"
              min="0.1"
              step="any"
          /></label>
        </div>
        <label
          >升级策略<select data-field="upgradeMode" v-model="runtime.upgradeMode">
            <option value="savepoint">savepoint · 保存状态</option>
            <option value="stateless">stateless · 无状态</option>
            <option value="last-state">last-state · 需要 HA</option>
          </select></label
        >
        <details class="runtime-advanced">
          <summary>附加依赖与高级配置</summary>
          <label
            >SQL Runner 路径<input data-field="jarURI" v-model="runtime.jarURI" /><HelpTip
              text="镜像内 local:/// JAR 路径；默认 /opt/flink/lib/flink-web-sql-runner.jar。"
          /></label>
          <ArtifactPicker
            data-field="dependencyArtifactIds"
            v-model="runtime.dependencyArtifactIds"
            :artifacts="artifacts"
            :scope-id="runtime.workspaceId"
            category="dependency"
            :disabled="busy || !editable"
            @uploaded="uploaded"
            @uploading="artifactUploading = $event"
          />
          <h4>Flink 运行配置</h4>
          <FlinkConfigEditor
            data-field="flinkConfiguration"
            v-model="configValues"
            :readonly="busy || !editable"
            @valid="configValid = $event"
          />
          <label
            >从模板获取 PodTemplate<select data-field="podTemplateId" v-model="podTemplateId">
              <option value="">选择已登记模板</option>
              <option v-for="template in templates" :key="template.id" :value="template.id">
                {{ template.name }}
              </option>
            </select></label
          ><button
            type="button"
            class="button"
            :disabled="!podTemplateId || busy"
            @click="applyPodTemplate"
          >
            应用 PodTemplate</button
          ><label
            >PodTemplate<textarea
              data-field="podTemplate"
              v-model="pod"
              rows="6"
              class="code-input" /><HelpTip
              text="JSON 对象，可注入 Secret 环境变量；应用时复制模板 spec.podTemplate；已有内容会提示确认。"
          /></label>
        </details>
      </fieldset>
      <div v-if="editable" class="sql-publish">
        <button
          class="button"
          :disabled="busy || artifactUploading || stateConfigBusy || loading"
          @click="publish(false)"
        >
          生成部署草稿</button
        ><button
          class="button primary"
          :disabled="busy || artifactUploading || stateConfigBusy || loading"
          @click="publish(true)"
        >
          部署并启动
        </button>
      </div>
      <RouterLink v-if="linkedJob" class="text-button full-width" to="/jobs"
        >查看关联部署 ↗</RouterLink
      >
      <button
        v-if="editable && selected"
        class="text-button danger full-width"
        :disabled="busy"
        @click="remove"
      >
        删除 SQL 草稿
      </button>
    </aside>
    <aside v-if="sideTab === 'sources'" class="sql-runtime datasource-browser">
      <div class="field-heading">
        <h3>数据源</h3>
        <button class="text-button" aria-label="收起数据源" @click="sideTab = ''">×</button>
      </div>
      <p class="muted">
        插入建表 SQL 后可编写 INSERT 语句并部署执行。MySQL 为 JDBC 有界读取，不是 CDC。
      </p>
      <div class="filter-bar">
        <input
          data-field="sourceQuery"
          v-model="sourceQuery"
          placeholder="筛选数据源"
        /><ManualRefreshButton :action="refreshSources" :disabled="busy" />
      </div>
      <div v-for="source in filteredSources" :key="source.id" class="source-item">
        <div>
          <strong>{{ source.name }}</strong
          ><small>{{ source.connectorType.toUpperCase() }} · {{ source.id }}</small>
        </div>
        <button v-if="editable" class="button" :disabled="busy" @click="insertSource(source.id)">
          插入 SQL
        </button>
      </div>
      <p v-if="!filteredSources.length" class="empty-state">暂无匹配的数据源</p>
      <RouterLink class="text-button" to="/datasources">管理数据源 ↗</RouterLink>
    </aside>
    <aside v-if="sideTab === 'catalogs'" class="sql-runtime">
      <div class="field-heading">
        <h3>Catalog 资源</h3>
        <ManualRefreshButton :action="refreshCatalogs" :disabled="busy" label="刷新登记" />
      </div>
      <HelpTip
        text="选择 Catalog 后，发布时自动生成初始化 SQL。目录配置变更在下次预览或发布时生效；默认数据库须已存在。"
      />
      <RouterLink class="text-button" to="/catalogs">管理 Catalog ↗</RouterLink>
      <div v-if="currentCatalog" class="source-item">
        <div>
          <strong>{{ currentCatalog.name }}</strong
          ><small>{{ currentCatalog.type }} · 修订 {{ currentCatalog.revision }}</small>
        </div>
      </div>
      <MetadataBrowser
        v-if="currentCatalog?.type === 'jdbc' && canBrowse"
        :key="currentCatalog.id"
        :endpoint="'/api/sql/catalogs/' + currentCatalog.id + '/metadata'"
        :action-label="editable ? '插入表引用示例' : undefined"
        @select="insertTable"
      />
      <p v-else class="empty-state">
        {{
          !currentCatalog
            ? '请先选择 Catalog'
            : '当前目录在 Flink 运行时初始化；库表浏览仅支持 JDBC 且需开发权限。'
        }}
      </p>
    </aside>
    <aside v-if="sideTab === 'history'" class="sql-runtime">
      <SqlHistory
        :id="selected"
        :revision="form.revision"
        :sql="form.sql"
        :editable="!!editable"
        @restored="run(reloadRestoredSql)"
      />
    </aside>
    <nav class="studio-rail" aria-label="SQL 辅助面板">
      <button
        :class="{ active: sideTab === 'runtime' }"
        :aria-expanded="sideTab === 'runtime'"
        @click="sideTab = sideTab === 'runtime' ? '' : 'runtime'"
      >
        部署配置</button
      ><button
        :class="{ active: sideTab === 'sources' }"
        :aria-expanded="sideTab === 'sources'"
        @click="sideTab = sideTab === 'sources' ? '' : 'sources'"
      >
        数据源</button
      ><button
        :class="{ active: sideTab === 'history' }"
        :aria-expanded="sideTab === 'history'"
        @click="sideTab = sideTab === 'history' ? '' : 'history'"
      >
        版本历史</button
      ><button
        :class="{ active: sideTab === 'catalogs' }"
        :aria-expanded="sideTab === 'catalogs'"
        @click="sideTab = sideTab === 'catalogs' ? '' : 'catalogs'"
      >
        Catalog
      </button>
    </nav>
  </section>
</template>
