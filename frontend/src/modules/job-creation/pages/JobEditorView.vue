<script setup lang="ts">
import StateStorageForm from '../../../shared/components/form/StateStorageForm.vue'
import { hydrateJar } from '../jarForm'
import FlinkImageSelect from '../../../shared/components/form/FlinkImageSelect.vue'
import HelpTip from '../../../shared/components/form/HelpTip.vue'
import {
  setFieldError,
  showValidationError,
  clearValidationErrors,
} from '../../../shared/utils/fieldValidation'
import PageHeader from '../../../shared/components/layout/PageHeader.vue'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { parseDocument } from 'yaml'
import CodeEditor from '../../../shared/components/editor/CodeEditor.vue'
import MemoryInput from '../../../shared/components/form/MemoryInput.vue'
import FlinkConfigEditor from '../../../shared/components/editor/FlinkConfigEditor.vue'
import ArtifactPicker, { type Artifact } from '../../artifact/components/ArtifactPicker.vue'
import { jobsApi } from '../../job-management/api/jobs'
import { request, jsonBody } from '../../../shared/api/http'
import { requestId } from '../../../shared/utils/requestId'
import type { DraftInput } from '../../../shared/types/api'
type Catalog = {
  id: string
  name: string
  yaml?: string
  version?: string
  flinkVersion?: string
  repository?: string
}
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
  templateId: '',
  imageId: '',
  mainArtifactId: '',
  dependencyArtifactIds: [] as string[],
  jarURI: 'local:///opt/flink/usrlib/job.jar',
  entryClass: '',
  parallelism: 1,
  slots: 1,
  upgradeMode: 'savepoint',
  jmMemory: '1024m',
  tmMemory: '2048m',
  jmCpu: 1,
  tmCpu: 1,
})
const workspaceId = ref(requestId())
const baseYaml = ref('')
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
  config = ref<Record<string, string>>({}),
  method = ref(route.path.includes('/jar/') ? 'form' : 'yaml')
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
onMounted(async () => {
  try {
    const result = await Promise.all(
      ['templates', 'images'].map((type) => request<any[]>('/api/catalog/' + type)),
    )
    templates.value = result[0]!
    images.value = result[1]!

    if (id.value) {
      const saved = await jobsApi.get(id.value)
      deploymentName.value = saved.deploymentName
      workspaceId.value = saved.id
      method.value = 'yaml'
      Object.assign(form, saved)
      options.imageId =
        images.value.find((i) => imageAddress(i) === currentYamlImage.value)?.id || ''
    }
  } catch (e) {
    error.value = showValidationError((e as Error).message)
  } finally {
    loading.value = false
  }
})
async function switchMethod(next: string) {
  if (busy.value || stateConfigBusy.value || loading.value || method.value === next) return
  error.value = ''
  if (next === 'form' && form.yaml.trim()) {
    try {
      const storage = await request<{ bucket: string; rootPrefix: string }>(
        '/api/artifacts/defaults',
      ).catch(() => undefined)
      const mapped = hydrateJar(form.yaml, images.value, storage)
      Object.assign(options, mapped.options)
      args.value = mapped.args
      config.value = mapped.config
      baseYaml.value = form.yaml
    } catch (e) {
      error.value = '此作业只能在 YAML 页面修改：' + (e as Error).message
      return
    }
  } else if (next === 'yaml' && method.value === 'form' && options.imageId && form.name) {
    try {
      await render()
    } catch (e) {
      error.value = (e as Error).message
      return
    }
  }
  method.value = next
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
      baseYaml: baseYaml.value,
      name: deploymentName.value || form.name,
      args: args.value ? args.value.replace(/\r/g, '').split('\n') : [],
      flinkConfiguration: config.value,
    }),
  })
  form.yaml = result.yaml
  form.flinkVersion = result.flinkVersion
}
watch(
  [form, options, args, config],
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
      ? await jobsApi.update(id.value, form)
      : await jobsApi.create({ ...form, workspaceId: workspaceId.value })
    Object.assign(form, saved)
    deploymentName.value = saved.deploymentName
    if (deploy) {
      await router.replace('/jobs/' + saved.id + '/edit')
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
  }
}
</script>
<template>
  <RouterLink to="/jobs" class="back-link">← 返回作业列表</RouterLink>
  <PageHeader eyebrow="JOB DEVELOPMENT"
    ><template #title>{{
      id ? '编辑作业' : method === 'form' ? '新建 JAR 作业' : '新建作业'
    }}</template
    ><template #description>配置主程序与运行参数，验证后保存或发布到当前工作空间。</template
    ><template #actions
      ><div class="segmented">
        <button type="button" :class="{ active: method === 'form' }" @click="switchMethod('form')">
          JAR 表单</button
        ><button type="button" :class="{ active: method === 'yaml' }" @click="switchMethod('yaml')">
          YAML 编辑
        </button>
      </div></template
    ></PageHeader
  >
  <div v-if="error" class="notice error" role="alert">{{ error }}</div>
  <div v-if="message" class="notice" role="status">{{ message }}</div>
  <form
    ref="formElement"
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
              :disabled="!!id || loading"
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
              >入口类<input
                data-field="entryClass"
                v-model="options.entryClass"
                pattern="[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*"
                placeholder="com.example.Main" /><HelpTip
                text="留空时使用 JAR Manifest 中的入口类。" /></label
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
        </section>
      </template>
    </div>
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
      <summary>实际提交配置预览（密钥为 Secret 引用）</summary>
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
