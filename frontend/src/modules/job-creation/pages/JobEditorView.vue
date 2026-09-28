<script setup lang="ts">
import FlinkImageSelect from '../../../shared/components/form/FlinkImageSelect.vue'
import HelpTip from '../../../shared/components/form/HelpTip.vue'
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
      ['templates', 'images', 'artifacts'].map((type) => request<any[]>('/api/catalog/' + type)),
    )
    templates.value = result[0]!
    images.value = result[1]!
    artifacts.value = result[2]!
    if (id.value) {
      Object.assign(form, await jobsApi.get(id.value))
      options.imageId =
        images.value.find((i) => imageAddress(i) === currentYamlImage.value)?.id || ''
    }
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
})
function syncYaml() {
  if (!form.yaml.trim()) return
  const doc = parseDocument(form.yaml)
  if (doc.errors.length) {
    error.value = 'YAML 有语法错误，请先修正后再同步名称和版本。'
    return
  }
  try {
    doc.setIn(['metadata', 'name'], form.name)
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
    form.yaml = item.yaml
    syncYaml()
  }
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
      name: form.name,
      args: args.value ? args.value.replace(/\r/g, '').split('\n') : [],
      flinkConfiguration: config.value,
    }),
  })
  form.yaml = result.yaml
  form.flinkVersion = result.flinkVersion
}
async function validate(dryRun = false) {
  if (!valid()) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    if (method.value === 'form') await render()
    const result = await request<{ yaml: string; message: string }>('/api/jobs/validate', {
      method: 'POST',
      ...jsonBody({ yaml: form.yaml, name: form.name, dryRun }),
    })
    preview.value = result.yaml
    message.value = result.message
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function save(deploy = false) {
  if (!valid()) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    if (method.value === 'form') await render()
    await request('/api/jobs/validate', {
      method: 'POST',
      ...jsonBody({ yaml: form.yaml, name: form.name, dryRun: false }),
    })
    const saved = id.value ? await jobsApi.update(id.value, form) : await jobsApi.create(form)
    Object.assign(form, saved)
    if (deploy) {
      await router.replace('/jobs/' + saved.id + '/edit')
      const result = await request<{ state: string; message: string }>(
        '/api/jobs/' + saved.id + '/actions/deploy',
        { method: 'POST', ...jsonBody({ requestId: requestId(), revision: saved.revision }) },
      )
      message.value = result.state + '：' + result.message
    } else await router.push('/jobs')
  } catch (e) {
    error.value = (e as Error).message
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
        <button type="button" :class="{ active: method === 'form' }" @click="method = 'form'">
          JAR 表单</button
        ><button type="button" :class="{ active: method === 'yaml' }" @click="method = 'yaml'">
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
              v-model="form.name"
              required
              pattern="[a-z0-9]([-a-z0-9]*[a-z0-9])?"
              maxlength="63"
              :disabled="!!id || loading"
              placeholder="order-stream" /><HelpTip
              text="小写字母、数字、连字符；同步到 YAML，创建后不可改名。"
          /></label>
          <label
            >镜像版本<FlinkImageSelect
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
              v-model="options.templateId"
              @change="method === 'yaml' && applyTemplate()"
            >
              <option value="">不使用模板</option>
              <option v-for="t in templates" :key="t.id" :value="t.id">{{ t.name }}</option></select
            ><HelpTip text="模板中的 PodTemplate 会保留。"
          /></label>
          <label
            >描述<input v-model="form.description" maxlength="500" placeholder="说明作业用途"
          /></label>
        </div>
      </section>
      <template v-if="method === 'form'">
        <section class="panel form-panel compact-form">
          <h3><span class="section-number">02</span> 程序与依赖</h3>
          <ArtifactPicker
            v-model="options.mainArtifactId"
            :artifacts="artifacts"
            category="main"
            :disabled="busy || uploading"
            @uploaded="uploaded"
            @uploading="uploading = $event"
          />
          <label v-if="!options.mainArtifactId"
            >容器内 JAR 路径<input
              v-model="options.jarURI"
              required
              placeholder="local:///opt/flink/lib/job.jar" /><HelpTip
              text="使用镜像内主包；附加依赖会挂载 /opt/flink/usrlib。"
          /></label>
          <ArtifactPicker
            v-model="options.dependencyArtifactIds"
            :artifacts="artifacts"
            category="dependency"
            :exclude="options.mainArtifactId"
            :disabled="busy || uploading"
            @uploaded="uploaded"
            @uploading="uploading = $event"
          />
          <div class="field-grid">
            <label
              >入口类<input
                v-model="options.entryClass"
                pattern="[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*"
                placeholder="com.example.Main" /><HelpTip
                text="留空时使用 JAR Manifest 中的入口类。" /></label
            ><label
              >程序参数<textarea v-model="args" rows="3" placeholder="--input&#10;orders" /><HelpTip
                text="每行一个参数，保留空格与引号。"
            /></label>
          </div>
        </section>
        <section class="panel form-panel compact-form">
          <h3><span class="section-number">03</span> 运行资源</h3>
          <div class="field-grid">
            <label
              >升级策略<select v-model="options.upgradeMode">
                <option>savepoint</option>
                <option>last-state</option>
                <option>stateless</option></select
              ><HelpTip text="保存快照 / 使用 HA 状态 / 无状态升级。"
            /></label>
            <label
              >并行度<input
                v-model.number="options.parallelism"
                type="number"
                required
                min="1"
                step="1" /><HelpTip text="并行执行实例数，必须为正整数。"
            /></label>
            <label
              >每个 TaskManager 的 Slot 数<input
                v-model.number="options.slots"
                type="number"
                required
                min="1"
                step="1" /><HelpTip text="每个 TaskManager 的逻辑调度槽位。"
            /></label>
            <div class="resource-pair resource-memory">
              <MemoryInput v-model="options.jmMemory" label="JobManager 内存" /><MemoryInput
                v-model="options.tmMemory"
                label="TaskManager 内存"
              />
            </div>
            <div class="resource-pair resource-cpu">
              <label
                >JobManager CPU<input
                  v-model.number="options.jmCpu"
                  type="number"
                  required
                  min="0.1"
                  step="any" /></label
              ><label
                >TaskManager CPU<input
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
          <FlinkConfigEditor v-model="config" :readonly="busy" @valid="configValid = $event" />
        </section>
      </template>
    </div>
    <section class="panel yaml-panel">
      <div class="panel-toolbar">
        <h3>{{ method === 'form' ? '生成后的 YAML' : 'FlinkDeployment YAML' }}</h3>
        <span class="badge neutral">YAML 1.2</span>
      </div>
      <CodeEditor v-model="form.yaml" :readonly="method === 'form'" />
      <div class="panel-footer">
        名称与版本同步更新 · 模板支持 PodTemplate<br /><code v-pre
          >"{{config:配置标识}}" / "{{secret:配置标识}}"</code
        >
      </div>
      <div class="preview-actions">
        <button type="button" class="button" :disabled="busy || uploading" @click="validate(false)">
          本地验证与预览</button
        ><button type="button" class="button" :disabled="busy || uploading" @click="validate(true)">
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
      ><button class="button" :disabled="busy || uploading || loading">保存草稿</button
      ><button
        type="button"
        class="button primary"
        :disabled="busy || uploading || loading"
        @click="save(true)"
      >
        保存并部署
      </button>
    </div>
  </form>
</template>
