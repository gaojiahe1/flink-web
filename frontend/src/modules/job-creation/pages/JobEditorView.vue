<script setup lang="ts">
import DraftCreateDialog from '../../../shared/components/form/DraftCreateDialog.vue'
import StateStorageForm from '../../../shared/components/form/StateStorageForm.vue'
import { confirmDialog } from '../../../shared/utils/confirmDialog'
import { hydrateJar } from '../jarForm'
import FlinkImageSelect from '../../../shared/components/form/FlinkImageSelect.vue'
import HelpTip from '../../../shared/components/form/HelpTip.vue'
import {
  setFieldError,
  showValidationError,
  clearValidationErrors,
} from '../../../shared/utils/fieldValidation'
import PageHeader from '../../../shared/components/layout/PageHeader.vue'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { parseDocument } from 'yaml'
import CodeEditor from '../../../shared/components/editor/CodeEditor.vue'
import PodTemplateFields from '../../../shared/components/editor/PodTemplateFields.vue'
import ServiceAccountField from '../../../shared/components/form/ServiceAccountField.vue'
import { emptyPodTemplates, readPodTemplates } from '../../../shared/utils/podTemplates'
import MemoryInput from '../../../shared/components/form/MemoryInput.vue'
import FlinkConfigEditor from '../../../shared/components/editor/FlinkConfigEditor.vue'
import ArtifactPicker, { type Artifact } from '../../artifact/components/ArtifactPicker.vue'
import { jobsApi } from '../../job-management/api/jobs'
import { request, jsonBody } from '../../../shared/api/http'
import { requestId } from '../../../shared/utils/requestId'
import type { DraftInput } from '../../../shared/types/api'
import { currentUser } from '../../permission/stores/auth'
type Catalog = {
  id: string
  name: string
  yaml?: string
  version?: string
  flinkVersion?: string
  repository?: string
}
const props = defineProps<{ embedded?: boolean }>()
const emit = defineEmits<{ opened: [string] }>()
const route = useRoute(),
  router = useRouter(),
  id = computed(() => (typeof route.params.id === 'string' ? route.params.id : undefined))
const form = reactive<DraftInput>({
  name: '',
  description: '',
  flinkVersion: '1.20',
  yaml: '',
  revision: 0,
})
const options = reactive({
  serviceAccount: '',
  templateId: '',
  imageId: '',
  mainArtifactId: '',
  dependencyArtifactIds: [] as string[],
  jarURI: 'local:///opt/flink/usrlib/job.jar',
  entryClass: '',
  parallelism: 1,
  slots: 1,
  upgradeMode: 'savepoint',
  jmMemory: '2048m',
  tmMemory: '2048m',
  jmCpu: 1,
  tmCpu: 1,
})
const pods = ref(emptyPodTemplates()),
  podValid = ref(true)
const splitRatio = ref(62)
const splitArea = ref<HTMLFormElement>()
function moveSplit(event: PointerEvent) {
  if (!(event.currentTarget as HTMLElement).hasPointerCapture(event.pointerId)) return
  const bounds = splitArea.value?.getBoundingClientRect()
  if (bounds)
    splitRatio.value = Math.min(
      75,
      Math.max(45, ((event.clientX - bounds.left) / bounds.width) * 100),
    )
}
function beginSplit(event: PointerEvent) {
  event.preventDefault()
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  moveSplit(event)
}
function endSplit(event: PointerEvent) {
  const target = event.currentTarget as HTMLElement
  if (target.hasPointerCapture(event.pointerId)) target.releasePointerCapture(event.pointerId)
}
const nameConfirmed = ref(false)
function confirmName(value: string) {
  form.name = value
  nameConfirmed.value = true
}
const workspaceId = ref(requestId())
const entryClasses = ref<string[]>([]),
  inspectingEntry = ref(false),
  entryError = ref('')
let entryEpoch = 0
watch(
  () => options.mainArtifactId,
  async (reference, previous) => {
    const epoch = ++entryEpoch
    entryClasses.value = []
    entryError.value = ''
    if (!reference) {
      inspectingEntry.value = false
      return
    }
    if (previous && reference !== previous) options.entryClass = ''
    inspectingEntry.value = true
    try {
      const result = await request<{ entryClasses: string[] }>('/api/artifacts/entry-classes', {
        method: 'POST',
        ...jsonBody({ reference }),
      })
      if (epoch !== entryEpoch) return
      entryClasses.value = result.entryClasses || []
      if (entryClasses.value.length === 1) options.entryClass = entryClasses.value[0]!
      else if (entryClasses.value.length > 1 && !entryClasses.value.includes(options.entryClass))
        options.entryClass = ''
    } catch (e) {
      if (epoch === entryEpoch) entryError.value = (e as Error).message
    } finally {
      if (epoch === entryEpoch) inspectingEntry.value = false
    }
  },
)
const baseYaml = ref('')
const yamlOnly = ref(false)
const formRewriteConfirmed = ref(false)
const switchingMethod = ref(false)
const deploymentName = ref('')
const nameInput = ref<HTMLInputElement>()
let nameCheck = 0
const nameError = ref('')
async function checkName() {
  const version = ++nameCheck
  const name = form.name
  nameError.value = ''
  nameInput.value?.setCustomValidity('')
  if (id.value || !name || !nameInput.value?.validity.valid) return !nameError.value
  try {
    const result = await request<{ available: boolean }>(
      '/api/jobs/name-availability?' + new URLSearchParams({ name }),
    )
    if (version !== nameCheck || form.name !== name) return false
    nameError.value = result.available ? '' : '作业名称已存在，请使用其他名称'
  } catch {
    if (version !== nameCheck || form.name !== name) return false
    nameError.value = '名称校验失败，请重试'
  }
  nameInput.value?.setCustomValidity(nameError.value)
  if (nameInput.value) setFieldError(nameInput.value, nameError.value)
  return !nameError.value
}
function nameChanged() {
  ++nameCheck
  nameError.value = ''
  nameInput.value?.setCustomValidity('')
  if (nameInput.value) setFieldError(nameInput.value, '')
}
const stateConfigBusy = ref(false)
const uploading = ref(false),
  configValid = ref(true)
const imageAddress = (image: Catalog) => image.repository + ':' + image.version
const currentYamlImage = computed(() => {
  try {
    return parseDocument(form.yaml).getIn(['spec', 'image']) || ''
  } catch {
    return ''
  }
})
const args = ref(''),
  config = ref<Record<string, string>>(
    id.value ? {} : { 'jobmanager.memory.jvm-metaspace.size': '512m' },
  ),
  method = ref(route.path.includes('/jar/') || route.path === '/jar/new' ? 'form' : 'yaml')
const templates = ref<Catalog[]>([]),
  images = ref<Catalog[]>([]),
  artifacts = ref<Artifact[]>([])
const busy = ref(false),
  loading = ref(Boolean(id.value)),
  error = ref(''),
  message = ref(''),
  preview = ref(''),
  submitted = ref(false),
  formElement = ref<HTMLFormElement>()
const savedEditor = ref('')
let navigating = false
let disposed = false
onBeforeUnmount(() => {
  disposed = true
})
const editorSnapshot = () =>
  JSON.stringify({
    name: form.name,
    description: form.description,
    flinkVersion: form.flinkVersion,
    yaml: form.yaml,
    options,
    args: args.value,
    config: config.value,
    pods: pods.value,
    method: method.value,
  })
async function canLeave() {
  if (!props.embedded || navigating) return true
  if (!savedEditor.value && (loading.value || switchingMethod.value)) return true
  if (
    busy.value ||
    loading.value ||
    uploading.value ||
    stateConfigBusy.value ||
    switchingMethod.value
  ) {
    message.value = '正在加载或处理配置，请完成后再切换草稿。'
    return false
  }
  return (
    !savedEditor.value ||
    savedEditor.value === editorSnapshot() ||
    (await confirmDialog('当前 JAR 草稿有未保存的修改，确认切换？'))
  )
}
function allowNavigation() {
  navigating = true
}
defineExpose({ canLeave, allowNavigation })
onBeforeRouteLeave(canLeave)
onBeforeRouteUpdate((to, from) => (to.path === from.path ? true : canLeave()))
onMounted(async () => {
  try {
    const result = await Promise.all(
      ['templates', 'images'].map((type) => request<any[]>('/api/catalog/' + type)),
    )
    if (disposed) return
    templates.value = result[0]!
    images.value = result[1]!

    if (id.value) {
      const saved = await jobsApi.get(id.value)
      if (disposed) return
      const links =
        await request<Record<string, { type: string; sqlId?: string }>>('/api/development/links')
      if (disposed) return
      if (links[saved.id]?.type === 'sql') {
        const sqlId = links[saved.id]?.sqlId
        if (!sqlId) throw new Error('对应 SQL 草稿不存在或没有访问权限，请从 SQL 开发检查关联。')
        await router.replace({ path: '/sql', query: { draft: sqlId } })
        return
      }
      if (currentUser.value?.roles.some((r) => ['ROLE_ADMIN', 'ROLE_DEVELOPER'].includes(r)))
        await request('/api/development/jar/jobs/' + saved.id + '/open', { method: 'POST' })
      if (disposed) return
      deploymentName.value = saved.deploymentName
      workspaceId.value = saved.id
      method.value = 'yaml'
      Object.assign(form, saved)
      yamlOnly.value =
        parseDocument(saved.yaml).getIn(['metadata', 'labels', 'flink-web/editor']) !== 'jar-v1'
      options.imageId =
        images.value.find((i) => imageAddress(i) === currentYamlImage.value)?.id || ''
    }
  } catch (e) {
    if (disposed) return
    error.value = showValidationError((e as Error).message)
  } finally {
    loading.value = false
  }
  if (disposed) return
  if ((route.query.editor === 'jar' || (id.value && !yamlOnly.value)) && !error.value)
    await switchMethod('form')
  if (disposed) return
  await nextTick()
  savedEditor.value = editorSnapshot()
  if (id.value) emit('opened', id.value)
})
async function switchMethod(next: string) {
  if (
    busy.value ||
    stateConfigBusy.value ||
    loading.value ||
    switchingMethod.value ||
    method.value === next
  )
    return
  switchingMethod.value = true
  error.value = ''
  try {
    if (next === 'yaml' && !podValid.value)
      throw new Error('请先修正 PodTemplate YAML 再切换编辑方式')
    const legacy = next === 'form' && yamlOnly.value && !formRewriteConfirmed.value
    if (
      legacy &&
      !(await confirmDialog(
        '此作业来自旧资源。JAR 表单会重新生成部分 YAML，可能改变原有配置和格式。请在保存前检查生成的 YAML；解析失败将保留当前 YAML。是否继续？',
        '切换到 JAR 表单',
      ))
    )
      return
    if (next === 'form' && form.yaml.trim()) {
      try {
        const storage = await request<{ bucket: string; rootPrefix: string }>(
          '/api/artifacts/defaults',
        ).catch(() => undefined)
        if (disposed) return
        const mapped = hydrateJar(form.yaml, images.value, storage)
        Object.assign(options, mapped.options)
        pods.value = readPodTemplates(parseDocument(form.yaml).toJS()?.spec)
        args.value = mapped.args
        config.value = mapped.config
        baseYaml.value = form.yaml
        if (legacy) formRewriteConfirmed.value = true
      } catch (e) {
        error.value = '暂时无法切换到 JAR 表单，原 YAML 已保留：' + (e as Error).message
        return
      }
    } else if (next === 'yaml' && method.value === 'form' && options.imageId && form.name)
      await render()
    method.value = next
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    switchingMethod.value = false
  }
}

function syncYaml() {
  if (!form.yaml.trim()) return
  const doc = parseDocument(form.yaml)
  if (doc.errors.length) {
    error.value = 'YAML 有语法错误，请先修正后再同步名称和版本。'
    return
  }
  try {
    doc.setIn(['metadata', 'name'], deploymentName.value || form.name)
    doc.setIn(['spec', 'flinkVersion'], 'v' + form.flinkVersion.split('.').slice(0, 2).join('_'))
    const image = images.value.find((i) => i.id === options.imageId)
    if (image) doc.setIn(['spec', 'image'], imageAddress(image))
    form.yaml = doc.toString()
  } catch {
    error.value = 'YAML 必须是 FlinkDeployment 对象。'
  }
}
watch(
  () => [form.name, form.flinkVersion],
  () => {
    if (!loading.value) syncYaml()
  },
)
watch(
  () => options.imageId,
  () => {
    const image = images.value.find((i) => i.id === options.imageId)
    if (image?.flinkVersion) {
      form.flinkVersion = image.flinkVersion
      if (!loading.value) syncYaml()
    }
  },
)
function applyTemplate() {
  const item = templates.value.find((t) => t.id === options.templateId)
  if (item?.yaml) {
    try {
      const doc = parseDocument(item.yaml)
      if (doc.errors.length) throw new Error('模板 YAML 无法解析')
      if (method.value === 'form') {
        const spec = doc.toJS()?.spec || {}
        pods.value = readPodTemplates(spec)
        if (spec.serviceAccount) options.serviceAccount = spec.serviceAccount
        baseYaml.value = item.yaml
        config.value = { ...(spec.flinkConfiguration || {}) }
        if (spec.job?.args) {
          if (
            !Array.isArray(spec.job.args) ||
            spec.job.args.some((v: unknown) => typeof v !== 'string' || /[\\r\\n]/.test(v))
          )
            throw new Error('模板参数无法映射，请在 YAML 模式应用')
          args.value = spec.job.args.join('\\n')
        }
        for (const field of ['jarURI', 'entryClass', 'parallelism', 'upgradeMode'] as const)
          if (spec.job?.[field] !== undefined) (options as any)[field] = spec.job[field]
        for (const [manager, prefix] of [
          ['jobManager', 'jm'],
          ['taskManager', 'tm'],
        ])
          for (const field of ['cpu', 'memory'])
            if (spec[manager!]?.resource?.[field] !== undefined)
              (options as any)[prefix! + field[0]!.toUpperCase() + field.slice(1)] =
                spec[manager!].resource[field]
        if (config.value['taskmanager.numberOfTaskSlots'])
          options.slots = Number(config.value['taskmanager.numberOfTaskSlots'])
      }
      form.yaml = item.yaml
      syncYaml()
    } catch (e) {
      error.value = (e as Error).message
      method.value = 'yaml'
      form.yaml = item.yaml
    }
  } else baseYaml.value = ''
}
function uploaded(a: Artifact) {
  artifacts.value = [...artifacts.value.filter((item) => item.id !== a.id), a]
}
function valid() {
  submitted.value = true
  if (method.value === 'form' && !podValid.value) {
    error.value = '请先修正 PodTemplate YAML'
    return false
  }
  if (method.value === 'form' && !configValid.value) {
    error.value = '请先修正 Flink 配置'
    return false
  }
  if (method.value === 'yaml') {
    if (deploymentName.value) syncYaml()
    const doc = parseDocument(form.yaml)
    const image = images.value.find((i) => i.id === options.imageId)
    if (image) {
      form.flinkVersion = image.flinkVersion || form.flinkVersion
      syncYaml()
    } else if (!doc.errors.length)
      form.flinkVersion = String(doc.getIn(['spec', 'flinkVersion']) || '')
        .replace(/^v/, '')
        .replace('_', '.')
  }
  return formElement.value?.reportValidity() ?? false
}
async function render() {
  const result = await request<{ yaml: string; flinkVersion: string }>('/api/jobs/render-form', {
    method: 'POST',
    ...jsonBody({
      ...options,
      ...pods.value,
      baseYaml: baseYaml.value,
      jobId: id.value,
      allowFormRewrite: formRewriteConfirmed.value,
      name: deploymentName.value || form.name,
      args: args.value ? args.value.replace(/\r/g, '').split('\n') : [],
      flinkConfiguration: config.value,
    }),
  })
  form.yaml = result.yaml
  form.flinkVersion = result.flinkVersion
}
watch(
  [form, options, args, config, pods, podValid],
  () => {
    clearValidationErrors(formElement.value || document)
    error.value = ''
    message.value = ''
    preview.value = ''
  },
  { deep: true },
)
async function validate(dryRun = false) {
  if (busy.value || stateConfigBusy.value || !valid()) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    if (!(await checkName()) || !valid()) return
    if (method.value === 'form') await render()
    const result = await request<{ yaml: string; message: string }>('/api/jobs/validate', {
      method: 'POST',
      ...jsonBody({ yaml: form.yaml, name: deploymentName.value || form.name, dryRun }),
    })
    preview.value = result.yaml
    message.value = result.message
  } catch (e) {
    error.value = showValidationError((e as Error).message)
  } finally {
    busy.value = false
  }
}
async function save(deploy = false) {
  if (busy.value || stateConfigBusy.value || !valid()) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    if (!(await checkName()) || !valid()) return
    if (method.value === 'form') await render()
    await request('/api/jobs/validate', {
      method: 'POST',
      ...jsonBody({ yaml: form.yaml, name: deploymentName.value || form.name, dryRun: false }),
    })
    const saved = id.value
      ? await jobsApi.update(id.value, { ...form, allowFormRewrite: formRewriteConfirmed.value })
      : await jobsApi.create({ ...form, workspaceId: workspaceId.value })
    Object.assign(form, saved)
    deploymentName.value = saved.deploymentName
    savedEditor.value = editorSnapshot()
    navigating = true
    if (deploy) {
      await router.replace('/jar/' + saved.id + '/edit')
      const result = await request<{ state: string; message: string }>(
        '/api/jobs/' + saved.id + '/actions/deploy',
        { method: 'POST', ...jsonBody({ requestId: requestId(), revision: saved.revision }) },
      )
      if (result.state === 'ACCEPTED') await router.push('/jobs')
      else message.value = result.state + '：' + result.message
    } else await router.push('/jobs')
  } catch (e) {
    error.value = showValidationError((e as Error).message)
  } finally {
    busy.value = false
    navigating = false
  }
}
</script>
<template>
  <DraftCreateDialog
    v-if="!id && !nameConfirmed"
    kind="JAR"
    @named="confirmName"
    @close="router.push('/jar')"
  />
  <RouterLink v-if="!embedded" to="/jar" class="back-link">← 返回 JAR 开发</RouterLink>
  <PageHeader
    :eyebrow="embedded ? undefined : 'JOB DEVELOPMENT'"
    :class="{ 'embedded-editor-title': embedded }"
    ><template #title>{{
      id ? '编辑作业' : method === 'form' ? '新建 JAR 作业' : '新建作业'
    }}</template
    ><template #description>配置主程序与运行参数，验证后保存或发布到当前工作空间。</template
    ><template #actions
      ><div class="segmented">
        <button
          type="button"
          :class="{ active: method === 'form' }"
          :disabled="loading || switchingMethod"
          :title="yamlOnly ? '切换前需确认可能改变原 YAML' : ''"
          @click="switchMethod('form')"
        >
          JAR 表单</button
        ><button type="button" :class="{ active: method === 'yaml' }" @click="switchMethod('yaml')">
          YAML 编辑
        </button>
      </div></template
    ></PageHeader
  >
  <div v-if="error" class="notice error" role="alert">{{ error }}</div>
  <div v-if="message" class="notice" role="status">{{ message }}</div>
  <p v-if="yamlOnly" class="notice" role="status">
    此作业来自既有资源。可切换到 JAR 表单，但生成内容可能改变原 YAML；保存前请检查配置。
  </p>
  <form
    :ref="
      (el) => {
        formElement = el as HTMLFormElement
        splitArea = el as HTMLFormElement
      }
    "
    :style="{ '--jar-form-width': splitRatio + 'fr', '--jar-yaml-width': 100 - splitRatio + 'fr' }"
    class="job-editor-form"
    :class="{ submitted: submitted, 'jar-split': method === 'form' }"
    @submit.prevent="save(false)"
  >
    <div class="editor-form-column">
      <section class="panel form-panel compact-form">
        <h3><span class="section-number">01</span> 基本信息</h3>
        <div class="field-grid">
          <label
            >作业名称<input
              data-field="name"
              ref="nameInput"
              v-model="form.name"
              @blur="checkName"
              @input="nameChanged"
              required
              pattern="[a-z0-9]([\-a-z0-9]*[a-z0-9])?"
              maxlength="63"
              :disabled="!!id || nameConfirmed || loading"
              placeholder="order-stream" /><HelpTip
              text="小写字母、数字、连字符；名称唯一，保存后自动分配编号，资源名为 flink-编号。"
          /></label>
          <label
            >镜像版本<FlinkImageSelect
              data-field="imageId"
              v-model="options.imageId"
              :images="images"
              :required="method === 'form' || !currentYamlImage"
              :fallback="
                currentYamlImage ? 'Flink ' + form.flinkVersion + '（当前 YAML）' : undefined
              " /><HelpTip
              text="镜像与 Flink 版本绑定选择，同时更新 YAML 的 image 和 flinkVersion。"
          /></label>
          <label
            >默认模板<select
              data-field="templateId"
              v-model="options.templateId"
              @change="applyTemplate"
            >
              <option value="">不使用模板</option>
              <option v-for="t in templates" :key="t.id" :value="t.id">{{ t.name }}</option></select
            ><HelpTip text="模板中的 PodTemplate 会保留。"
          /></label>
          <label
            >描述<input
              data-field="description"
              v-model="form.description"
              maxlength="500"
              placeholder="说明作业用途"
          /></label>
          <ServiceAccountField
            v-if="method === 'form'"
            v-model="options.serviceAccount"
            :disabled="busy || loading"
          />
        </div>
      </section>
      <template v-if="method === 'form'">
        <section class="panel form-panel compact-form">
          <h3><span class="section-number">02</span> 程序与依赖</h3>
          <ArtifactPicker
            data-field="mainArtifactId"
            v-model="options.mainArtifactId"
            :artifacts="artifacts"
            :scope-id="workspaceId"
            category="main"
            :disabled="busy || uploading || stateConfigBusy"
            @uploaded="uploaded"
            @uploading="uploading = $event"
          />
          <label v-if="!options.mainArtifactId"
            >容器内 JAR 路径<input
              data-field="jarURI"
              v-model="options.jarURI"
              required
              placeholder="local:///opt/flink/lib/job.jar" /><HelpTip
              text="使用镜像内主包；附加依赖会挂载 /opt/flink/usrlib。"
          /></label>
          <ArtifactPicker
            data-field="dependencyArtifactIds"
            v-model="options.dependencyArtifactIds"
            :artifacts="artifacts"
            :scope-id="workspaceId"
            category="dependency"
            :exclude="options.mainArtifactId"
            :disabled="busy || uploading || stateConfigBusy"
            @uploaded="uploaded"
            @uploading="uploading = $event"
          />
          <div class="field-grid">
            <label
              >入口类<select
                v-if="entryClasses.length > 1"
                data-field="entryClass"
                v-model="options.entryClass"
                required
              >
                <option value="">请选择主 JAR 入口类</option>
                <option v-for="name in entryClasses" :key="name" :value="name">
                  {{ name }}
                </option></select
              ><input
                v-else
                data-field="entryClass"
                v-model="options.entryClass"
                pattern="[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*"
                placeholder="com.example.Main"
              /><HelpTip
                text="自动识别主 JAR 的入口类；一个入口自动填入，多个入口请选择。识别失败时可手动填写。"
              /><small v-if="inspectingEntry" class="muted" role="status"
                >正在识别主 JAR 入口类…</small
              ><small v-if="entryError" class="field-error" role="alert">{{ entryError }}</small
              ><small
                v-else-if="options.mainArtifactId && !inspectingEntry && !entryClasses.length"
                class="muted"
                >未发现入口类，可手动填写或使用 JAR Manifest 默认入口。</small
              ></label
            ><label
              >程序参数<textarea
                data-field="args"
                v-model="args"
                rows="3"
                placeholder="--input&#10;orders" /><HelpTip text="每行一个参数，保留空格与引号。"
            /></label>
          </div>
        </section>
        <StateStorageForm
          v-model="config"
          :version="form.flinkVersion"
          :scope-id="workspaceId"
          @busy="stateConfigBusy = $event"
          :disabled="busy"
        />
        <section class="panel form-panel compact-form">
          <h3><span class="section-number">03</span> 运行资源</h3>
          <div class="field-grid">
            <label
              >升级策略<select data-field="upgradeMode" v-model="options.upgradeMode">
                <option>savepoint</option>
                <option>last-state</option>
                <option>stateless</option></select
              ><HelpTip text="保存快照 / 使用 HA 状态 / 无状态升级。"
            /></label>
            <label
              >并行度<input
                data-field="parallelism"
                v-model.number="options.parallelism"
                type="number"
                required
                min="1"
                step="1" /><HelpTip text="并行执行实例数，必须为正整数。"
            /></label>
            <label
              >每个 TaskManager 的 Slot 数<input
                data-field="slots"
                v-model.number="options.slots"
                type="number"
                required
                min="1"
                step="1" /><HelpTip text="每个 TaskManager 的逻辑调度槽位。"
            /></label>
            <div class="resource-pair resource-memory">
              <MemoryInput
                data-field="jmMemory"
                v-model="options.jmMemory"
                label="JobManager 内存"
              /><MemoryInput
                data-field="tmMemory"
                v-model="options.tmMemory"
                label="TaskManager 内存"
              />
            </div>
            <div class="resource-pair resource-cpu">
              <label
                >JobManager CPU<input
                  data-field="jmCpu"
                  v-model.number="options.jmCpu"
                  type="number"
                  required
                  min="0.1"
                  step="any" /></label
              ><label
                >TaskManager CPU<input
                  data-field="tmCpu"
                  v-model.number="options.tmCpu"
                  type="number"
                  required
                  min="0.1"
                  step="any"
              /></label>
            </div>
          </div>
        </section>
        <section class="panel form-panel compact-form">
          <h3><span class="section-number">04</span> Flink 配置</h3>
          <FlinkConfigEditor
            data-field="flinkConfiguration"
            v-model="config"
            :readonly="busy"
            @valid="configValid = $event"
          />
          <PodTemplateFields v-model="pods" :readonly="busy" @valid="podValid = $event" />
        </section>
      </template>
    </div>
    <div
      v-if="method === 'form'"
      class="jar-resizer"
      role="separator"
      aria-label="调整表单与 YAML 宽度"
      aria-orientation="vertical"
      tabindex="0"
      :aria-valuenow="Math.round(splitRatio)"
      :aria-valuemin="45"
      :aria-valuemax="75"
      @pointerdown="beginSplit"
      @pointermove="moveSplit"
      @pointerup="endSplit"
      @pointercancel="endSplit"
      @keydown.left.prevent="splitRatio = Math.max(45, splitRatio - 2)"
      @keydown.right.prevent="splitRatio = Math.min(75, splitRatio + 2)"
      @keydown.home.prevent="splitRatio = 62"
    ></div>
    <section class="panel yaml-panel">
      <div class="panel-toolbar">
        <h3>{{ method === 'form' ? '生成后的 YAML' : 'FlinkDeployment YAML' }}</h3>
        <span class="badge neutral">YAML 1.2</span>
      </div>
      <CodeEditor data-field="yaml" v-model="form.yaml" :readonly="method === 'form'" />
      <div class="panel-footer">
        名称与版本同步更新 · 模板支持 PodTemplate<br /><code v-pre
          >"{{config:配置标识}}" / "{{secret:配置标识}}"</code
        >
      </div>
      <div class="preview-actions">
        <button
          type="button"
          class="button"
          :disabled="busy || uploading || stateConfigBusy"
          @click="validate(false)"
        >
          本地验证与预览</button
        ><button
          type="button"
          class="button"
          :disabled="busy || uploading || stateConfigBusy"
          @click="validate(true)"
        >
          集群 dry-run
        </button>
      </div>
    </section>
    <details v-if="preview" class="panel form-panel rendered-preview">
      <summary>实际提交配置预览（密钥已隐藏）</summary>
      <CodeEditor :model-value="preview" readonly label="提交 YAML 预览" />
    </details>
    <div class="form-actions editor-actionbar">
      <span class="muted">草稿保存后不会自动部署</span
      ><RouterLink class="button" to="/jobs">取消</RouterLink
      ><button class="button" :disabled="busy || uploading || stateConfigBusy || loading">
        保存草稿</button
      ><button
        type="button"
        class="button primary"
        :disabled="busy || uploading || stateConfigBusy || loading"
        @click="save(true)"
      >
        保存并部署
      </button>
    </div>
  </form>
</template>
