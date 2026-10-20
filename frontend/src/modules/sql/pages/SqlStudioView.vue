<script setup lang="ts">
import { showValidationError, clearValidationErrors } from '../../../shared/utils/fieldValidation'
import { confirmDialog } from '../../../shared/utils/confirmDialog'
import { useDevelopmentStatus } from '../../../shared/composables/useDevelopmentStatus'
import { Trash2, Box, ChevronLeft, ChevronRight } from '@lucide/vue'
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
import SqlDraftCreateDialog from '../components/SqlDraftCreateDialog.vue'
import SqlDeploymentAudit from '../components/SqlDeploymentAudit.vue'
import AiSqlAssistant from '../../ai/components/AiSqlAssistant.vue'
import SqlVerificationResults from '../components/SqlVerificationResults.vue'
import type { WizardTable } from '../../ai/utils/wizard'
import type { AiResult } from '../../ai/api/ai'
import CodeEditor from '../../../shared/components/editor/CodeEditor.vue'
import PodTemplateFields from '../../../shared/components/editor/PodTemplateFields.vue'
import ServiceAccountField from '../../../shared/components/form/ServiceAccountField.vue'
import {
  emptyPodTemplates,
  readPodTemplates,
  type PodTemplateOptions,
} from '../../../shared/utils/podTemplates'
import MemoryInput from '../../../shared/components/form/MemoryInput.vue'
import FlinkConfigEditor from '../../../shared/components/editor/FlinkConfigEditor.vue'
import { parseDocument } from 'yaml'
import { parseDeploymentYaml } from '../../../shared/utils/deploymentYaml'
import { useDeploymentConfigurationEditor } from '../../../shared/composables/useDeploymentConfigurationEditor'
import ArtifactPicker, { type Artifact } from '../../artifact/components/ArtifactPicker.vue'
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { request, jsonBody, ApiError } from '../../../shared/api/http'
import { verificationApi, verificationTerminal, type SqlVerification } from '../api/verification'
import { currentUser } from '../../permission/stores/auth'
import { requestId } from '../../../shared/utils/requestId'
import { systemInfo } from '../../system/stores/system'
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
  baseYaml: string
  deploymentYaml: string
  serviceAccount: string
  workspaceId: string
  imageId: string
  runnerArtifactId: string
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
  jmPodTemplate: Record<string, unknown>
  tmPodTemplate: Record<string, unknown>
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
const route = useRoute()
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
const initialRuntime = (): Runtime => ({
  baseYaml: '',
  deploymentYaml: '',
  serviceAccount: systemInfo.value?.namespace ? systemInfo.value.namespace + '-flink' : '',
  workspaceId: requestId(),
  catalogId: '',
  database: '',
  jmCpu: 1,
  tmCpu: 1,
  imageId: '',
  runnerArtifactId: '',
  parallelism: 1,
  slots: 1,
  jmMemory: '1024m',
  tmMemory: '2048m',
  upgradeMode: 'stateless',
  dependencyArtifactIds: [],
  flinkConfiguration: {},
  ...emptyPodTemplates(),
})
const form = reactive({ name: '', description: '', sql: '', revision: 0 })
const creating = ref(false),
  newRuntime = ref<Runtime>(),
  auditOpen = ref(false)
const runtime = reactive<Runtime>(initialRuntime())
const podOptions = computed<PodTemplateOptions>({
  get: () => ({
    podTemplate: JSON.parse(pod.value),
    jmPodTemplate: runtime.jmPodTemplate,
    tmPodTemplate: runtime.tmPodTemplate,
  }),
  set: (value) => {
    pod.value = JSON.stringify(value.podTemplate)
    runtime.jmPodTemplate = value.jmPodTemplate
    runtime.tmPodTemplate = value.tmPodTemplate
  },
})
const records = ref<SqlDraft[]>([]),
  images = ref<Catalog[]>([]),
  artifacts = ref<Artifact[]>([])
const stateConfigBusy = ref(false)
const artifactUploading = ref(false),
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
  sources = ref<(WizardTable & { id: string; name: string })[]>([]),
  sourceQuery = ref('')
const workbench = ref<HTMLElement>()
const inspectorWidth = ref(Math.min(380, Math.max(300, window.innerWidth * 0.26)))
const inspectorMax = ref(860)
const resizing = ref(false)
let resizeObserver: ResizeObserver | undefined
let resizeStart = { x: 0, width: 0 }
function clampInspector(width = inspectorWidth.value) {
  const element = workbench.value
  if (!element || element.clientWidth < 610 || window.innerWidth <= 1000) return
  const explorer =
    sideTab.value === 'ai'
      ? 0
      : parseFloat(getComputedStyle(element).getPropertyValue('--explorer-width'))
  inspectorMax.value = Math.max(
    300,
    Math.min(sideTab.value === 'ai' ? 1400 : 860, element.clientWidth - explorer - 42 - 8 - 260),
  )
  inspectorWidth.value = Math.round(Math.min(inspectorMax.value, Math.max(300, width)))
}
function startResize(event: PointerEvent) {
  if (event.button !== 0) return
  clampInspector()
  resizeStart = { x: event.clientX, width: inspectorWidth.value }
  resizing.value = true
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  event.preventDefault()
}
function resizeInspector(event: PointerEvent) {
  if (resizing.value)
    clampInspector(
      resizeStart.width + (sideTab.value === 'ai' ? 1 : -1) * (event.clientX - resizeStart.x),
    )
}
function resizeWithKeys(event: KeyboardEvent) {
  const widths: Record<string, number> = {
    ArrowLeft: inspectorWidth.value + (sideTab.value === 'ai' ? -40 : 40),
    ArrowRight: inspectorWidth.value + (sideTab.value === 'ai' ? 40 : -40),
    Home: 300,
    End: inspectorMax.value,
  }
  if (!(event.key in widths)) return
  event.preventDefault()
  clampInspector(widths[event.key])
}
function resetInspector() {
  clampInspector(
    sideTab.value === 'ai'
      ? ((workbench.value?.clientWidth || 1000) - 50) * (aiStep.value === 1 ? 0.7 : 0.3)
      : Math.min(380, Math.max(300, window.innerWidth * 0.26)),
  )
}
onMounted(() => {
  resizeObserver = new ResizeObserver(() => clampInspector())
  if (workbench.value) resizeObserver.observe(workbench.value)
})
onUnmounted(() => resizeObserver?.disconnect())
watch(explorerCollapsed, () => nextTick(() => clampInspector()))
watch(sideTab, () => nextTick(resetInspector))
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
const configurationEditor = useDeploymentConfigurationEditor<Runtime>({
  snapshot: () => ({
    form,
    runtime,
    config: config.value,
    pod: pod.value,
    podTemplateId: podTemplateId.value,
  }),
  payload: () => payload(),
  convert: (value, signal) =>
    request<{ yaml: string; runtime: Runtime; formIssue?: string }>('/api/sql/configuration', {
      method: 'POST',
      signal,
      ...jsonBody(value),
    }),
  applyRuntime: (value, pods) => {
    Object.assign(runtime, value)
    config.value = JSON.stringify(value.flinkConfiguration || {}, null, 2)
    podOptions.value = pods
  },
})
const {
  mode: editorMode,
  yaml: deploymentYaml,
  configValid,
  podValid,
  savedSnapshot,
  snapshot,
  dirty,
} = configurationEditor
const check = ref<{
  level: string
  statementCount: number
  insertCount: number
  message: string
} | null>(null)
const verification = ref<SqlVerification | null>(null)
const sqlEditor = ref<InstanceType<typeof CodeEditor>>()
const aiContextVersion = ref(0)
const aiStep = ref(1)
const aiAssistant = ref<InstanceType<typeof AiSqlAssistant>>()
const aiOpened = ref(false)
const aiNavigation = ref([true, false, false])
const aiReview = ref<AiResult>(),
  aiSqlCollapsed = ref(false)
watch(aiStep, () => {
  aiSqlCollapsed.value = false
  void nextTick(resetInspector)
})
watch(sideTab, (value) => {
  if (value === 'ai') aiOpened.value = true
})
watch(
  () => {
    const context = JSON.parse(snapshot())
    delete context.value.form.revision
    return JSON.stringify(context)
  },
  () => {
    aiContextVersion.value++
  },
  { flush: 'sync' },
)

/** 应用前核对上下文，先准备完整配置再一次提交，避免覆盖新编辑或产生半份建议。 */
async function applyAiSuggestion(
  suggestion: AiResult,
  version: number,
  completed: (success: boolean) => void,
) {
  let applied = false
  await run(async () => {
    if (!editable.value) throw new Error('当前不可应用 AI 建议')
    if (version !== aiContextVersion.value) throw new Error('SQL 或配置已修改，请重新生成建议')
    configurationEditor.validate()
    if (!suggestion.sql || new TextEncoder().encode(suggestion.sql).length > 32768)
      throw new Error('AI SQL 为空或超过32 KiB')
    const document = editorMode.value === 'yaml' ? parseDeploymentYaml(deploymentYaml.value) : null
    const template = document
      ? readPodTemplates(document.toJS().spec).podTemplate
      : JSON.parse(pod.value)
    if (!template || typeof template !== 'object' || Array.isArray(template))
      throw new Error('PodTemplate 必须为对象')
    for (const env of suggestion.env || []) {
      template.spec ??= {}
      template.spec.containers ??= []
      let main = template.spec.containers.find((c: any) => c.name === 'flink-main-container')
      if (!main) {
        main = { name: 'flink-main-container', env: [] }
        template.spec.containers.push(main)
      }
      main.env ??= []
      const existing = main.env.find((item: any) => item.name === env.name)
      if (existing && JSON.stringify(existing) !== JSON.stringify(env))
        throw new Error('密钥环境变量已存在不同配置，请先核对')
      if (!existing) main.env.push(env)
    }
    const nextConfig = document
      ? document.toJS().spec.flinkConfiguration || {}
      : JSON.parse(config.value)
    for (const [key, value] of Object.entries(suggestion.flinkConfiguration || {})) {
      if (nextConfig[key] !== undefined && String(nextConfig[key]) !== value)
        throw new Error('AI 建议的运行配置与当前值冲突：' + key)
      nextConfig[key] = value
    }
    if (document) {
      document.setIn(['spec', 'podTemplate'], template)
      document.setIn(['spec', 'flinkConfiguration'], nextConfig)
      deploymentYaml.value = document.toString()
    } else {
      pod.value = JSON.stringify(template, null, 2)
      config.value = JSON.stringify(nextConfig, null, 2)
    }
    form.sql = suggestion.sql
    check.value = null
    await nextTick()
    message.value = 'AI 建议已应用到编辑器。尚未保存或执行，请重新检查和验证。'
    applied = true
  })
  completed(applied)
}
const needsSession = ref(false)
const verifying = computed(
  () => !!verification.value && !verificationTerminal(verification.value.state),
)
let pollTimer: ReturnType<typeof setTimeout> | undefined
let pollVersion = 0
let pollController: AbortController | undefined
function stopListening() {
  ++pollVersion
  clearTimeout(pollTimer)
  pollController?.abort()
}
async function listen() {
  const current = verification.value
  if (!current || verificationTerminal(current.state)) return
  const version = pollVersion
  pollController = new AbortController()
  try {
    const updated = await verificationApi.status(current.id, pollController.signal)
    if (version !== pollVersion) return
    verification.value = updated
  } catch (e) {
    if (version !== pollVersion) return
    error.value = (e as Error).message
  }
  if (version === pollVersion && verifying.value) pollTimer = setTimeout(listen, 2000)
}
onUnmounted(stopListening)

watch(
  [
    () => form.sql,
    () => form.name,
    () => form.description,
    runtime,
    config,
    pod,
    podTemplateId,
    podValid,
    deploymentYaml,
  ],
  () => {
    clearValidationErrors()
    error.value = ''
    message.value = ''
    preview.value = ''
    check.value = null
  },
  { deep: true },
)
const editable = computed(() =>
  currentUser.value?.roles.some((r) => r === 'ROLE_ADMIN' || r === 'ROLE_DEVELOPER'),
)
const filtered = computed(() =>
  records.value.filter((r) =>
    (r.name + r.description).toLowerCase().includes(query.value.toLowerCase()),
  ),
)
const bytes = computed(() => new TextEncoder().encode(form.sql).length)
watch(
  () => systemInfo.value?.namespace,
  (namespace) => {
    if (!namespace || runtime.serviceAccount) return
    const pristine = !dirty.value
    runtime.serviceAccount = namespace + '-flink'
    if (pristine) savedSnapshot.value = snapshot()
  },
  { immediate: true },
)
const { loadStatus, statusError, status } = useDevelopmentStatus()
async function refreshDrafts() {
  await Promise.all([load(), loadStatus()])
}
async function load() {
  records.value = await request<SqlDraft[]>('/api/sql/jobs')
  if (selected.value) {
    const current = records.value.find((r) => r.id === selected.value)
    linkedJob.value = current?.jobId || ''
  }
}
onMounted(async () => {
  void loadStatus()
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
    const requested =
      typeof route.query.draft === 'string'
        ? records.value.find((r) => r.id === route.query.draft)
        : undefined
    if (requested) await open(requested, true)
    else if (route.query.draft) error.value = '对应 SQL 草稿不存在或没有访问权限'
    else if (records.value[0]) await open(records.value[0], true)
  } catch (e) {
    error.value = showValidationError((e as Error).message)
  } finally {
    loading.value = false
  }
})
async function mayDiscard() {
  if (verifying.value) {
    if (!(await confirmDialog('切换草稿将取消当前 SQL 验证，确认继续？'))) return false
    await cancelVerification()
    if (verifying.value) return false
  }
  return !dirty.value || (await confirmDialog('当前 SQL 有未保存的修改，确认切换？'))
}
async function open(record: SqlDraft, force = false) {
  if (!force && !(await mayDiscard())) return
  selected.value = record.id
  stopListening()
  verification.value = null
  needsSession.value = false
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
  configurationEditor.reset(runtime)
  preview.value = ''
  check.value = null
  catalogPreview.value = ''
  error.value = ''
  message.value = ''
  await nextTick()
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
  newRuntime.value = initialRuntime()
  creating.value = true
}
async function created(id: string) {
  creating.value = false
  await run(async () => {
    await load()
    const record = await request<SqlDraft>('/api/sql/jobs/' + encodeURIComponent(id))
    await open(record, true)
    await router.replace({ query: { ...route.query, draft: id } })
    message.value = '空白 SQL 草稿已创建，作业名称已锁定。'
  })
}
async function applyPodTemplate() {
  const template = templates.value.find((t) => t.id === podTemplateId.value)
  if (!template?.yaml) return
  const doc = parseDocument(template.yaml)
  if (doc.errors.length) {
    error.value = '模板 YAML 存在语法错误'
    return
  }
  let value: PodTemplateOptions
  try {
    value = readPodTemplates(doc.toJS()?.spec)
  } catch (e) {
    error.value = (e as Error).message
    return
  }
  if (!Object.values(value).some((pod) => Object.keys(pod).length)) {
    error.value = '模板不包含 PodTemplate'
    return
  }
  if (
    (Object.values(podOptions.value).some((pod) => Object.keys(pod).length) || !podValid.value) &&
    !(await confirmDialog('替换当前 PodTemplate？已注入的数据源环境变量也会被替换。'))
  )
    return
  podOptions.value = value
  message.value = '已复制模板 PodTemplate，可继续编辑'
  error.value = ''
}
function payload(): Pick<SqlDraft, 'name' | 'description' | 'sql' | 'revision' | 'runtime'> {
  configurationEditor.validate()
  return {
    ...form,
    runtime: {
      ...runtime,
      deploymentYaml: editorMode.value === 'yaml' ? deploymentYaml.value : '',
      flinkConfiguration:
        editorMode.value === 'yaml' ? runtime.flinkConfiguration : JSON.parse(config.value),
      podTemplate: editorMode.value === 'yaml' ? runtime.podTemplate : JSON.parse(pod.value),
    },
  }
}
async function switchEditor(mode: 'form' | 'yaml') {
  return run(async () => {
    await configurationEditor.switchTo(mode)
  })
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
async function run(task: () => Promise<void>, fieldErrors = true) {
  if (busy.value || stateConfigBusy.value) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await task()
  } catch (e) {
    error.value = fieldErrors ? showValidationError((e as Error).message) : (e as Error).message
  } finally {
    busy.value = false
  }
}
function save() {
  if (!selected.value) return create()
  return run(async () => {
    await persist()
    message.value = 'SQL 草稿已保存，运行中的作业不受影响。'
  })
}
function validate() {
  return run(async () => {
    check.value = null
    const before = snapshot()
    const result = await request<{
      level: string
      statementCount: number
      insertCount: number
      message: string
    }>('/api/sql/validate', {
      method: 'POST',
      ...jsonBody({ sql: form.sql, runtime: payload().runtime }),
    })
    if (before === snapshot()) {
      check.value = result
      message.value = result.message
    } else message.value = 'SQL 或配置已修改，请重新检查。'
  })
}
function verifySql() {
  if (!check.value || verifying.value) return
  maximized.value = false
  clearValidationErrors()
  return run(async () => {
    needsSession.value = false
    try {
      const clusters = await request<{ id: string; validation: boolean }[]>('/api/sessions')
      if (!clusters.some((c) => c.validation)) {
        needsSession.value = true
        throw new Error('请前往 Session 集群创建并启动 SQL 验证专用集群。')
      }
      await persist()
      verification.value = await verificationApi.submit(selected.value, form.revision, requestId())
      stopListening()
      void listen()
    } catch (e) {
      if (e instanceof ApiError && e.code.startsWith('VALIDATION_SESSION'))
        needsSession.value = true
      throw e
    }
  }, false)
}
async function cancelVerification() {
  const id = verification.value?.id
  if (!id) return
  try {
    verification.value = await verificationApi.cancel(id)
    if (verificationTerminal(verification.value.state)) stopListening()
  } catch (e) {
    error.value = (e as Error).message
  }
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
function auditPreview(signal: AbortSignal) {
  return request<{ yaml: string }>('/api/sql/preview', {
    method: 'POST',
    signal,
    ...jsonBody(payload()),
  })
}
async function publish(deploy: boolean) {
  if (
    deploy &&
    !(await confirmDialog(
      '确认保存并部署当前 SQL？Runner 和连接器通过文件挂载加载。已有同名部署会按升级策略变更。',
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
    } else await router.push('/jobs')
  })
}
async function remove(record?: SqlDraft) {
  const id = record?.id || selected.value
  if (!id || busy.value || (id === selected.value && verifying.value)) return
  const revision = id === selected.value ? form.revision : record!.revision
  const name = record?.name || form.name
  if (
    !(await confirmDialog(
      '删除 SQL 草稿「' +
        name +
        '」？' +
        (id === selected.value && dirty.value ? '当前未保存的修改也将丢弃。' : '') +
        '已生成部署的草稿须先在作业管理删除关联记录。',
      '删除 SQL 草稿',
    ))
  )
    return
  return run(async () => {
    await request('/api/sql/jobs/' + encodeURIComponent(id) + '?revision=' + revision, {
      method: 'DELETE',
    })
    if (id === selected.value) {
      savedSnapshot.value = snapshot()
      await create()
      if (route.query.draft === id)
        await router.replace({ path: '/sql', query: { ...route.query, draft: undefined } })
    }
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
    if (editorMode.value === 'form' && !podValid.value)
      throw new Error('请先修正 PodTemplate YAML，再插入数据源')
    const document = editorMode.value === 'yaml' ? parseDeploymentYaml(deploymentYaml.value) : null
    const before = snapshot()
    const snippet = await request<{ sql: string; env: { name: string; value: string }[] }>(
      '/api/sql/datasources/' + id + '/snippet',
    )
    if (before !== snapshot() || (editorMode.value === 'form' && !podValid.value))
      throw new Error('SQL 或配置已修改，请重新插入数据源')
    const template = document
      ? readPodTemplates(document.toJS().spec).podTemplate
      : JSON.parse(pod.value)
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
    if (document) {
      document.setIn(['spec', 'podTemplate'], template)
      deploymentYaml.value = document.toString()
    } else pod.value = JSON.stringify(template, null, 2)
    form.sql = snippet.sql + '\n' + form.sql
    check.value = null
    message.value =
      '已插入建表语句和密钥引用。继续编写 INSERT，检查通过后可验证数据或在部署配置中部署并启动。'
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
watch(
  () => route.query.draft,
  async (value) => {
    if (loading.value || typeof value !== 'string' || value === selected.value) return
    const record = records.value.find((r) => r.id === value)
    if (record) await open(record)
  },
)
</script>

<template>
  <PageHeader eyebrow="DATA DEVELOPMENT" class="studio-title"
    ><template #title>SQL 开发</template
    ><template #description>编写 SQL → SQL 检查 → 发布为 Application 作业</template
    ><template #actions
      ><span v-if="sideTab === 'ai' && selected" class="muted">{{ form.name }}</span>
      <button v-if="sideTab === 'ai' && editable" class="button" :disabled="busy" @click="create">
        新建 SQL 草稿
      </button>
      <RouterLink class="button" to="/jobs">前往作业运维 ↗</RouterLink></template
    ></PageHeader
  >
  <div v-if="error" class="notice error" role="alert">{{ error }}</div>
  <div v-if="message" class="notice" role="status">{{ message }}</div>
  <div v-if="needsSession" class="notice">
    <RouterLink class="text-button" to="/sessions"
      >前往 Session 集群，创建或启动 SQL 验证集群 ↗</RouterLink
    >
  </div>
  <nav v-if="sideTab === 'ai'" class="sql-wizard-steps" aria-label="SQL 需求向导">
    <button
      v-for="(label, index) in ['确认需求', '查看 SQL', '检查和保存']"
      :key="label"
      :class="{ active: aiStep === index + 1 }"
      :aria-current="aiStep === index + 1 ? 'step' : undefined"
      :disabled="!aiNavigation[index]"
      @click="aiAssistant?.nextStep(index + 1)"
    >
      <span>{{ index + 1 }}</span
      >{{ label }}
    </button>
  </nav>
  <section
    ref="workbench"
    class="sql-workbench"
    :style="{ '--inspector-width': inspectorWidth + 'px' }"
    :class="{
      'with-inspector': !!sideTab,
      'ai-guided': sideTab === 'ai',
      'sql-preview-collapsed': sideTab === 'ai' && aiSqlCollapsed,
      'explorer-collapsed': explorerCollapsed,
      'studio-maximized': maximized,
      'inspector-resizing': resizing,
    }"
  >
    <aside class="sql-explorer development-explorer">
      <button
        class="explorer-toggle text-button"
        :aria-label="explorerCollapsed ? '展开草稿栏' : '收起草稿栏'"
        @click="explorerCollapsed = !explorerCollapsed"
      >
        <ChevronRight v-if="explorerCollapsed" :size="16" /><ChevronLeft v-else :size="16" />
      </button>
      <div v-show="!explorerCollapsed" class="explorer-content">
        <div class="explorer-title">
          <strong>SQL 草稿</strong
          ><HelpTip
            text="支持 CREATE CATALOG / DATABASE、CREATE TABLE / VIEW、USE CATALOG / DATABASE、INSERT INTO / OVERWRITE；Session 验证也支持源表加 SELECT。Application 发布需要 INSERT，多个 INSERT 合并为一个作业。"
          /><span class="count">{{ records.length }}</span>
        </div>
        <button
          v-if="editable"
          class="button full-width"
          :disabled="busy || artifactUploading || stateConfigBusy || loading"
          @click="create"
        >
          ＋ 新建 SQL
        </button>
        <div class="development-list-tools">
          <input
            data-field="query"
            v-model="query"
            class="sql-search"
            placeholder="搜索 SQL 草稿"
            aria-label="搜索 SQL 草稿"
          />
          <ManualRefreshButton :action="refreshDrafts" :disabled="busy || loading" />
        </div>
        <p v-if="statusError" class="development-list-note" role="alert">{{ statusError }}</p>
        <div class="sql-file-list" aria-label="SQL 草稿列表">
          <p v-if="loading" class="muted">正在加载…</p>
          <p v-else-if="!filtered.length" class="muted empty-tree">
            暂无草稿<br />点击新建并确认名称
          </p>
          <div v-for="record in filtered" :key="record.id" class="sql-file-row">
            <button
              class="sql-file"
              :title="record.name"
              :class="{ active: selected === record.id }"
              :disabled="busy"
              @click="open(record)"
            >
              <Box :size="15" /><span
                >{{ record.name
                }}<small>{{
                  status(record.jobId) +
                  ' · ' +
                  (images.find((i) => i.id === record.runtime?.imageId)?.flinkVersion || '未选版本')
                }}</small></span
              >
            </button>
            <button
              v-if="editable"
              class="text-button danger sql-file-delete"
              :aria-label="'删除 SQL 草稿「' + record.name + '」'"
              :title="record.jobId ? '先在作业管理删除关联记录，再删除 SQL 草稿' : '删除 SQL 草稿'"
              :disabled="busy || (selected === record.id && verifying)"
              @click="remove(record)"
            >
              <Trash2 :size="14" />
            </button>
          </div>
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
      <div v-if="sideTab !== 'ai' || aiStep === 3" class="sql-toolbar">
        <div class="toolbar-actions">
          <button
            v-if="editable"
            class="button"
            :disabled="busy || artifactUploading || stateConfigBusy || loading"
            @click="save"
          >
            保存 SQL</button
          ><button
            v-if="verifying"
            class="button danger"
            :disabled="busy"
            @click="cancelVerification"
          >
            停止验证</button
          ><button
            v-if="editable"
            class="button"
            :disabled="busy || artifactUploading || stateConfigBusy || loading"
            @click="validate"
          >
            SQL 检查</button
          ><button
            v-if="editable"
            class="button"
            :disabled="
              !check || busy || verifying || artifactUploading || stateConfigBusy || loading
            "
            :title="
              check
                ? '提交到 SQL 验证 Session，最多采集 1000 条变更并合并最终结果'
                : '请先通过 SQL 检查；修改 SQL 或配置后需重新检查'
            "
            @click="verifySql"
          >
            SQL 验证</button
          ><button
            v-if="editable"
            class="text-button"
            :disabled="busy || artifactUploading || stateConfigBusy || loading"
            @click="render"
          >
            预览 YAML
          </button>
          <button
            class="text-button"
            :disabled="!selected || busy || loading"
            @click="auditOpen = true"
          >
            核对部署 SQL
          </button>
        </div>
        <span class="muted">{{ bytes.toLocaleString() }} / 32,768 B</span>
      </div>
      <div v-if="sideTab !== 'ai' || aiStep === 3" class="catalog-context">
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
        <button
          class="text-button"
          :disabled="!editable || loading || busy"
          title="Ctrl / Cmd + Shift + F"
          @click="sqlEditor?.format()"
        >
          格式化 SQL
        </button>
      </div>
      <div
        v-if="sideTab === 'ai' && aiStep === 2 && aiReview?.sql"
        class="notice sql-review-notice"
      >
        AI 建议 SQL · 仅供审阅，尚未应用到草稿
      </div>
      <CodeEditor
        v-if="sideTab === 'ai' && aiStep === 2 && aiReview?.sql"
        :model-value="aiReview.sql"
        language="sql"
        label="AI 建议 SQL"
        readonly
      />
      <CodeEditor
        v-else
        ref="sqlEditor"
        data-field="sql"
        v-model="form.sql"
        language="sql"
        label="SQL 编辑器"
        :readonly="!editable || !selected || loading || busy || (sideTab === 'ai' && aiStep === 2)"
        @update:model-value="check = null"
      />
      <div v-if="sideTab !== 'ai' || aiStep === 3" class="sql-diagnostics">
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
        <details class="sql-runtime-guide">
          <summary>SQL Runner 与语法验证器怎么用？</summary>
          <p>
            SQL 检查使用平台内置的精确版本语法验证器，检查结果为 PARSER；没有对应版本时显示 BASIC。
          </p>
          <p>
            SQL Runner 默认使用共享目录 artifacts/shared/sql-runner/flink-web-sql-runner.jar。
            可以显式选择其他 Runner；生成部署草稿或部署并启动后在作业列表查看。
          </p>
          <p>
            试运行使用 SQL 验证：管理员先配置并启动与 SQL 镜像相同的 SQL 验证专用
            Session。检查通过后点击 SQL 验证，结果在本页下方查看，最多 1000
            条变更事件、两分钟，并将写入目标换为固定日志输出；旧 Runner 需重新选择新版文件。
          </p>
        </details>
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
    <div
      v-if="sideTab"
      class="sql-inspector-resizer"
      role="separator"
      aria-label="调整辅助面板宽度"
      aria-orientation="vertical"
      :aria-valuemin="300"
      :aria-valuemax="inspectorMax"
      :aria-valuenow="inspectorWidth"
      tabindex="0"
      title="拖动调整宽度，方向键微调，双击恢复"
      @pointerdown="startResize"
      @pointermove="resizeInspector"
      @pointerup="resizing = false"
      @pointercancel="resizing = false"
      @lostpointercapture="resizing = false"
      @keydown="resizeWithKeys"
      @dblclick="resetInspector"
    />
    <aside v-show="sideTab === 'runtime'" class="sql-runtime">
      <div class="field-heading">
        <h3>部署配置</h3>
        <button class="text-button" aria-label="收起部署配置" @click="sideTab = ''">×</button>
      </div>
      <p class="muted runtime-intro">发布后统一在作业管理页启停、刷新状态和创建快照。</p>
      <div class="filter-bar" role="group" aria-label="SQL 部署配置编辑方式">
        <button
          type="button"
          class="button"
          :class="{ primary: editorMode === 'form' }"
          :disabled="busy || !editable || artifactUploading || stateConfigBusy || loading"
          @click="switchEditor('form')"
        >
          表单编辑
        </button>
        <button
          type="button"
          class="button"
          :class="{ primary: editorMode === 'yaml' }"
          :disabled="busy || !editable || artifactUploading || stateConfigBusy || loading"
          @click="switchEditor('yaml')"
        >
          YAML 编辑
        </button>
      </div>
      <section v-if="editorMode === 'yaml'">
        <p class="muted">
          编辑部署配置；Runner 和下载器由文件选择管理，执行参数在发布时由 SQL 脚本生成。
        </p>
        <CodeEditor
          v-model="deploymentYaml"
          label="SQL 部署 YAML 编辑器"
          :readonly="busy || !editable"
        />
      </section>
      <fieldset :disabled="!editable || busy || loading">
        <h4>基本信息</h4>
        <label
          >作业名称<input
            data-field="name"
            ref="sqlNameInput"
            v-model="form.name"
            @blur="blurName"
            @input="sqlNameInput?.setCustomValidity('')"
            required
            pattern="[a-z0-9]([\-a-z0-9]*[a-z0-9])?"
            placeholder="orders-sql"
            maxlength="63"
            disabled /><HelpTip
            text="名称在新建草稿时确认且全局唯一，之后不可修改。发布后自动分配编号，资源名为 flink-编号。"
        /></label>
        <label
          >描述<input
            data-field="description"
            v-model="form.description"
            maxlength="500"
            placeholder="说明作业用途"
        /></label>
        <div v-show="editorMode === 'form'">
          <label
            >镜像版本<FlinkImageSelect
              data-field="imageId"
              v-model="runtime.imageId"
              :images="images"
              label="镜像版本" /><HelpTip
              text="与 JAR 作业共用 Flink 镜像；Runner 和依赖通过下载器挂载，镜像需包含 Table Planner。"
          /></label>
          <ServiceAccountField
            v-model="runtime.serviceAccount"
            :disabled="busy || !editable"
            :auto-default="false"
          />
          <StateStorageForm
            v-model="configValues"
            :version="images.find((i) => i.id === runtime.imageId)?.flinkVersion || '1.20'"
            :scope-id="runtime.workspaceId"
            @busy="stateConfigBusy = $event"
            :disabled="busy || !editable"
          />
          <h4>运行资源</h4>
          <div class="runtime-grid">
            <label
              >并行度<input
                data-field="parallelism"
                v-model.number="runtime.parallelism"
                type="number"
                min="1"
                step="1" /></label
            ><label
              >每个 TaskManager 的 Slot 数<input
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
              label="JobManager 内存"
            /><MemoryInput
              data-field="tmMemory"
              v-model="runtime.tmMemory"
              label="TaskManager 内存"
            />
          </div>
          <div class="runtime-grid">
            <label
              >JobManager CPU<input
                data-field="jmCpu"
                v-model.number="runtime.jmCpu"
                type="number"
                min="0.1"
                step="any" /></label
            ><label
              >TaskManager CPU<input
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
            <summary>程序与依赖及 Flink 配置</summary>
            <p class="muted">
              未覆盖 Runner 时，默认使用共享目录
              artifacts/shared/sql-runner/flink-web-sql-runner.jar；请确保文件已上传。
            </p>
            <ArtifactPicker
              data-field="runnerArtifactId"
              v-model="runtime.runnerArtifactId"
              category="main"
              runner
              :disabled="busy || !editable || artifactUploading"
              @uploading="artifactUploading = $event"
            />
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
            <h4>Flink 配置</h4>
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
              应用 PodTemplate
            </button>
            <PodTemplateFields
              v-model="podOptions"
              :key="runtime.workspaceId"
              :readonly="busy || !editable"
              @valid="podValid = $event"
            />
          </details>
        </div>
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
        :disabled="busy || verifying"
        @click="remove()"
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
    <AiSqlAssistant
      v-if="aiOpened"
      v-show="sideTab === 'ai'"
      :key="selected"
      ref="aiAssistant"
      :active="sideTab === 'ai'"
      v-model:step="aiStep"
      :check-summary="check?.message"
      :dirty="dirty"
      :sql="form.sql"
      :sql-id="selected"
      :flink-version="images.find((i) => i.id === runtime.imageId)?.flinkVersion || ''"
      :context-key="aiContextVersion"
      :editable="!!editable && !busy && !loading"
      :sql-collapsed="aiSqlCollapsed"
      @close="sideTab = ''"
      @create="create"
      @toggle-sql="aiSqlCollapsed = !aiSqlCollapsed"
      @review="aiReview = $event"
      @apply="applyAiSuggestion"
      @check="validate"
      @save="save"
      @run="verifySql"
      :can-verify="!!check && !verifying && !artifactUploading && !stateConfigBusy"
      @navigation="aiNavigation = $event"
    />
    <nav class="studio-rail" aria-label="SQL 辅助面板">
      <button
        v-if="editable"
        :class="{ active: sideTab === 'ai' }"
        :aria-expanded="sideTab === 'ai'"
        @click="sideTab = sideTab === 'ai' ? '' : 'ai'"
      >
        AI 助手
      </button>
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
  <SqlDraftCreateDialog
    v-if="creating && newRuntime"
    :runtime="newRuntime"
    @close="creating = false"
    @created="created"
  />
  <SqlDeploymentAudit
    v-if="auditOpen"
    :sql="form.sql"
    :job-id="linkedJob"
    :context-key="aiContextVersion"
    :preview="auditPreview"
    @close="auditOpen = false"
  />
  <SqlVerificationResults
    v-if="verification"
    :key="verification.id"
    :verification="verification"
    :busy="busy"
    @stop="cancelVerification"
  />
</template>
