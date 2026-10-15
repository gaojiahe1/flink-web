<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import PageHeader from '../../../shared/components/layout/PageHeader.vue'
import ManualRefreshButton from '../../../shared/components/form/ManualRefreshButton.vue'
import SessionStateDetail from '../components/SessionStateDetail.vue'
import FlinkImageSelect from '../../../shared/components/form/FlinkImageSelect.vue'
import MemoryInput from '../../../shared/components/form/MemoryInput.vue'
import FlinkConfigEditor from '../../../shared/components/editor/FlinkConfigEditor.vue'
import PodTemplateFields from '../../../shared/components/editor/PodTemplateFields.vue'
import CodeEditor from '../../../shared/components/editor/CodeEditor.vue'
import PodStatusDrawer from '../../job-management/components/PodStatusDrawer.vue'
import { useDeploymentConfigurationEditor } from '../../../shared/composables/useDeploymentConfigurationEditor'
import ServiceAccountField from '../../../shared/components/form/ServiceAccountField.vue'
import { emptyPodTemplates } from '../../../shared/utils/podTemplates'
import HelpTip from '../../../shared/components/form/HelpTip.vue'
import ArtifactPicker from '../../artifact/components/ArtifactPicker.vue'
import { currentUser } from '../../permission/stores/auth'
import { request } from '../../../shared/api/http'
import { confirmDialog } from '../../../shared/utils/confirmDialog'
import { showValidationError, clearValidationErrors } from '../../../shared/utils/fieldValidation'
import { sessionsApi, type SessionCluster } from '../api/sessions'
const clusters = ref<SessionCluster[]>([]),
  images = ref<any[]>([]),
  error = ref(''),
  message = ref(''),
  busy = ref(false),
  loading = ref(true)
const admin = computed(() => currentUser.value?.roles.includes('ROLE_ADMIN'))
const operator = computed(() =>
  currentUser.value?.roles.some((r) => ['ROLE_ADMIN', 'ROLE_OPERATOR'].includes(r)),
)
const editing = ref(false),
  selected = ref(''),
  uploading = ref(false),
  pods = ref(emptyPodTemplates())
const defaults = () => ({
  name: '',
  description: '',
  validation: false,
  revision: 0,
  runtime: {
    baseYaml: '',
    deploymentYaml: '',
    deploymentMode: 'native',
    tmReplicas: null as number | null,
    serviceAccount: '',
    imageId: '',
    runnerArtifactId: '',
    slots: 2,
    parallelism: 1,
    jmMemory: '2048m',
    tmMemory: '2048m',
    jmCpu: 1,
    tmCpu: 1,
    dependencyArtifactIds: [] as string[],
    flinkConfiguration: { 'jobmanager.memory.jvm-metaspace.size': '512m' } as Record<
      string,
      string
    >,
    ...emptyPodTemplates(),
  },
})
const form = reactive(defaults())
const configurationEditor = useDeploymentConfigurationEditor<SessionCluster['runtime']>({
  snapshot: () => ({ form, pods: pods.value }),
  payload: () => payload(),
  convert: (value, signal) => sessionsApi.configuration(value, signal),
  applyRuntime: (runtime) => {
    Object.assign(form.runtime, runtime)
    pods.value = {
      podTemplate: runtime.podTemplate || {},
      jmPodTemplate: runtime.jmPodTemplate || {},
      tmPodTemplate: runtime.tmPodTemplate || {},
    }
  },
})
const {
  mode: editorMode,
  yaml: deploymentYaml,
  configValid,
  podValid,
  dirty: configurationDirty,
} = configurationEditor
const hasValidation = computed(() => clusters.value.some((c) => c.validation))
const podCluster = ref<SessionCluster | null>(null)
const labels: Record<string, string> = {
  READY: '已就绪',
  STARTING: '启动中',
  DEPLOYING: '部署中',
  DEPLOYED_NOT_READY: '已部署，尚未就绪',
  STOPPED: '已停止',
  STOPPING: '停止中',
  MISSING: '资源缺失',
  ERROR: '部署异常',
}
async function run(task: () => Promise<void>) {
  if (busy.value) return
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
async function load() {
  clusters.value = await sessionsApi.list()
  await Promise.all(
    clusters.value.map(async (item) => {
      try {
        Object.assign(item, await sessionsApi.status(item.id))
      } catch (e) {
        item.state = 'ERROR'
        item.message = (e as Error).message
      }
    }),
  )
}
async function refreshCluster(item: SessionCluster) {
  try {
    const latest = await sessionsApi.status(item.id)
    delete item.registeredTaskManagers
    delete item.configuredTaskManagers
    delete item.taskManagerMessage
    Object.assign(item, latest)
  } catch (e) {
    item.state = 'ERROR'
    item.message = (e as Error).message
  }
}
async function refreshAll() {
  await run(async () => {
    await Promise.all(clusters.value.map(refreshCluster))
  })
}
function stateDescription(item: SessionCluster) {
  return [
    labels[item.state || ''] || item.state || '尚未查询',
    item.taskManagerMessage,
    item.queriedAt ? '查询时间：' + new Date(item.queriedAt).toLocaleString() : '尚未查询',
  ]
    .filter(Boolean)
    .join('\n')
}
function open(item?: SessionCluster) {
  clearValidationErrors()
  error.value = ''
  message.value = ''
  configValid.value = true
  selected.value = item?.id || ''
  const initial = defaults()
  Object.assign(form, initial, item ? JSON.parse(JSON.stringify(item)) : {})
  form.runtime = { ...initial.runtime, ...form.runtime }
  pods.value = {
    podTemplate: form.runtime.podTemplate || {},
    jmPodTemplate: form.runtime.jmPodTemplate || {},
    tmPodTemplate: form.runtime.tmPodTemplate || {},
  }
  podValid.value = true
  configurationEditor.reset(form.runtime)
  configurationEditor.markSaved()
  editing.value = true
}
function payload(): Omit<SessionCluster, 'id' | 'deploymentName' | 'desiredState'> {
  configurationEditor.validate()
  return {
    ...form,
    runtime: {
      ...form.runtime,
      ...pods.value,
      tmReplicas:
        form.runtime.tmReplicas == null || String(form.runtime.tmReplicas) === ''
          ? null
          : form.runtime.tmReplicas,
      deploymentYaml: editorMode.value === 'yaml' ? deploymentYaml.value : '',
    },
  }
}
watch(
  () => form.runtime.deploymentMode,
  (mode) => {
    if (mode === 'native') form.runtime.tmReplicas = null
  },
)
async function switchEditor(mode: 'form' | 'yaml') {
  await run(async () => {
    await configurationEditor.switchTo(mode)
  })
}
async function save() {
  await run(async () => {
    await sessionsApi.save(
      selected.value || (form.validation ? 'sql-validation' : form.name),
      payload(),
    )
    editing.value = false
    await load()
    message.value = 'Session 配置已保存，请点击启动。'
  })
}
watch(
  [form, pods, podValid],
  () => {
    clearValidationErrors()
    error.value = ''
    message.value = ''
  },
  { deep: true },
)
async function start(item: SessionCluster) {
  await run(async () => {
    const result = await sessionsApi.start(item)
    Object.assign(item, result)
    message.value = result.message || '启动请求已受理，请刷新查看状态。'
  })
}
async function stop(item: SessionCluster) {
  if (
    !(await confirmDialog(
      '停止 Session「' +
        item.name +
        '」？集群内任务将受影响；Operator 可能等待 Session 作业取消后才完成删除。',
    ))
  )
    return
  await run(async () => {
    const result = await sessionsApi.stop(item.id)
    delete item.registeredTaskManagers
    delete item.configuredTaskManagers
    delete item.taskManagerMessage
    Object.assign(item, result)
    message.value = '停止请求已受理，请刷新确认资源删除完成。'
  })
}
async function remove(item: SessionCluster) {
  if (!(await confirmDialog('删除已停止的 Session 配置「' + item.name + '」？'))) return
  await run(async () => {
    await sessionsApi.remove(item)
    await load()
    message.value = 'Session 配置已删除。'
  })
}
onMounted(async () => {
  try {
    images.value = await request<any[]>('/api/catalog/images')
    await load()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
})
</script>
<template>
  <PageHeader
    :title="editing ? (selected ? '配置 Session' : '新建 Session') : 'Session 集群'"
    :description="
      editing
        ? '配置集群用途、运行资源及依赖。保存后在集群列表中启动。'
        : '管理常驻 Flink Session。SQL 验证使用唯一的专用集群，与作业共用已登记的运行镜像。'
    "
  >
    <template #actions>
      <button
        v-if="editing"
        type="button"
        class="button"
        :disabled="busy || uploading"
        @click="editing = false"
      >
        返回集群列表
      </button>
      <button v-else-if="admin" class="button primary" :disabled="busy || loading" @click="open()">
        ＋ 新建 Session
      </button>
    </template>
  </PageHeader>
  <p v-if="error" class="notice error">{{ error }}</p>
  <p v-if="message" class="notice">{{ message }}</p>
  <section v-if="!editing" class="panel">
    <div class="filter-bar" style="justify-content: space-between">
      <span class="muted">{{
        hasValidation ? 'SQL 验证集群已登记' : '尚未创建 SQL 验证集群'
      }}</span>
      <ManualRefreshButton :action="refreshAll" :disabled="busy || loading" />
    </div>
    <p v-if="loading" class="empty-state">正在加载…</p>
    <div v-else-if="clusters.length" class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>集群</th>
            <th>用途</th>
            <th>期望状态</th>
            <th>观测状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in clusters" :key="item.id">
            <td>
              <strong>{{ item.name }}</strong
              ><small class="table-description"
                >{{ item.deploymentName }} · {{ item.description || '—' }}</small
              >
            </td>
            <td>
              <span class="badge" :class="{ running: item.validation }">{{
                item.validation ? 'SQL 验证 · 唯一' : '普通 Session'
              }}</span>
            </td>
            <td>
              {{
                { running: '运行', stopping: '停止中', stopped: '停止' }[
                  item.desiredState as 'running' | 'stopping' | 'stopped'
                ] || item.desiredState
              }}
            </td>
            <td>
              <SessionStateDetail
                :state="labels[item.state || ''] || item.state || '尚未查询'"
                :message="item.message"
                :detail="stateDescription(item)"
              >
                <small
                  v-if="
                    item.configuredTaskManagers !== undefined ||
                    item.registeredTaskManagers !== undefined
                  "
                  class="table-description"
                >
                  TaskManager：{{
                    item.registeredTaskManagers !== undefined
                      ? `已注册 ${item.registeredTaskManagers}`
                      : '尚未查询注册数量'
                  }}
                  {{
                    item.configuredTaskManagers !== undefined
                      ? ` · 配置 ${item.configuredTaskManagers}`
                      : ' · 按需分配'
                  }}
                </small>
                <small v-if="item.taskManagerMessage" class="table-description">{{
                  item.taskManagerMessage
                }}</small>
              </SessionStateDetail>
            </td>
            <td>
              <div class="row-actions">
                <button
                  class="text-button"
                  :disabled="busy || !item.uid || ['STOPPED', 'MISSING'].includes(item.state || '')"
                  @click="podCluster = item"
                >
                  Pod 状态
                </button>
                <button
                  v-if="operator"
                  class="text-button"
                  :disabled="
                    busy ||
                    !['STOPPED', 'MISSING', 'ERROR'].includes(item.state || '') ||
                    item.desiredState === 'stopping'
                  "
                  @click="start(item)"
                >
                  启动
                </button>
                <button
                  v-if="operator"
                  class="text-button danger"
                  :disabled="busy || item.state === 'STOPPED'"
                  @click="stop(item)"
                >
                  停止
                </button>
                <button
                  v-if="admin"
                  class="text-button"
                  :disabled="busy || item.desiredState !== 'stopped'"
                  @click="open(item)"
                >
                  配置
                </button>
                <button
                  v-if="admin"
                  class="text-button danger"
                  :disabled="busy || item.state !== 'STOPPED'"
                  @click="remove(item)"
                >
                  删除
                </button>
                <a
                  v-if="item.uid && !['STOPPED', 'MISSING'].includes(item.state || '')"
                  class="text-button"
                  :href="'/flink-ui/sessions/' + encodeURIComponent(item.id) + '/'"
                  target="_blank"
                  rel="noopener"
                  >Flink UI ↗</a
                >
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-else class="empty-state">
      暂无 Session 集群。管理员可创建普通 Session 或唯一的 SQL 验证集群。
    </p>
  </section>
  <PodStatusDrawer
    v-if="podCluster"
    :key="podCluster.id"
    :job="podCluster"
    kind="session"
    :can-read-logs="!!operator"
    @close="podCluster = null"
  />
  <form v-if="editing" class="job-editor-form session-editor" @submit.prevent="save">
    <div class="filter-bar" role="group" aria-label="Session 配置编辑方式">
      <button
        type="button"
        class="button"
        :class="{ primary: editorMode === 'form' }"
        :disabled="busy || uploading"
        @click="switchEditor('form')"
      >
        表单编辑
      </button>
      <button
        type="button"
        class="button"
        :class="{ primary: editorMode === 'yaml' }"
        :disabled="busy || uploading"
        @click="switchEditor('yaml')"
      >
        YAML 编辑
      </button>
    </div>
    <section v-if="editorMode === 'yaml'" class="panel">
      <p class="muted">
        编辑 Session FlinkDeployment；资源名称和 namespace 保持固定，Runner
        与下载器由文件选择配置管理。
      </p>
      <CodeEditor
        v-model="deploymentYaml"
        label="Session YAML 编辑器"
        :readonly="busy || uploading"
      />
    </section>
    <fieldset
      v-show="editorMode === 'form'"
      class="session-form-grid"
      :disabled="busy || uploading || editorMode === 'yaml'"
    >
      <div class="editor-form-column">
        <section class="panel form-panel compact-form">
          <h3><span class="section-number">01</span> 基本信息</h3>
          <div class="field-grid">
            <label
              >集群名称<input
                data-field="name"
                aria-label="集群名称"
                v-model="form.name"
                required
                pattern="[a-z0-9]([\-a-z0-9]*[a-z0-9])?"
                maxlength="45"
                :disabled="!!selected"
                placeholder="sql-check" /><HelpTip
                text="小写字母、数字和连字符，最多 45 字符；保存后名称不可修改。"
            /></label>
            <ServiceAccountField v-model="form.runtime.serviceAccount" />
            <label
              >运行镜像<FlinkImageSelect
                data-field="imageId"
                v-model="form.runtime.imageId"
                :images="images"
                label="运行镜像"
                :required="true"
            /></label>
            <label class="wide-field"
              >描述<input v-model="form.description" maxlength="500" placeholder="说明集群用途"
            /></label>
          </div>
          <div class="session-purpose">
            <label class="checkbox-label"
              ><input
                type="checkbox"
                v-model="form.validation"
                :disabled="!!selected || hasValidation"
              />SQL 验证专用 Session（仅允许一个）</label
            >
            <p class="muted">
              {{
                hasValidation && !form.validation
                  ? '已存在 SQL 验证集群，本集群作为普通 Session 使用。'
                  : 'SQL 验证须选择与此集群相同的运行镜像；普通 Session 用于其他常驻任务。'
              }}
            </p>
            <p v-if="form.validation" class="muted">
              专用集群使用平台固定日志配置；验证按变更事件累计，1000
              行或两分钟后停止，同时仅允许一个 SQL 验证。
            </p>
          </div>
        </section>
        <section class="panel form-panel compact-form">
          <h3><span class="section-number">02</span> 运行资源</h3>
          <div class="field-grid">
            <MemoryInput
              data-field="jmMemory"
              v-model="form.runtime.jmMemory"
              label="JobManager 内存"
            />
            <MemoryInput
              data-field="tmMemory"
              v-model="form.runtime.tmMemory"
              label="TaskManager 内存"
            />
            <label
              >JobManager CPU<input
                data-field="jmCpu"
                v-model.number="form.runtime.jmCpu"
                type="number"
                required
                min="0.1"
                step="any"
            /></label>
            <label
              >TaskManager CPU<input
                data-field="tmCpu"
                v-model.number="form.runtime.tmCpu"
                type="number"
                required
                min="0.1"
                step="any"
            /></label>
            <label
              >每个 TaskManager 的 Slot 数<input
                data-field="slots"
                v-model.number="form.runtime.slots"
                type="number"
                required
                min="1"
                step="1" /><HelpTip
                text="每个 TaskManager 的逻辑调度槽位；SQL 验证的并行度由 SQL 草稿配置。"
            /></label>
            <label
              >资源分配模式<select
                data-field="deploymentMode"
                v-model="form.runtime.deploymentMode"
              >
                <option value="native">Native · 按需分配</option>
                <option value="standalone">Standalone · 固定数量</option>
              </select></label
            >
            <label
              >TaskManager 数量<input
                data-field="tmReplicas"
                aria-label="TaskManager 数量"
                v-model.number="form.runtime.tmReplicas"
                type="number"
                min="1"
                step="1"
                :disabled="form.runtime.deploymentMode !== 'standalone'"
                placeholder="留空沿用默认行为"
            /></label>
            <p class="muted wide-field">
              {{
                form.runtime.deploymentMode === 'native'
                  ? 'Native 模式根据作业需求创建或释放 TaskManager；固定数量请显式选择 Standalone。'
                  : form.runtime.tmReplicas
                    ? `理论 Slot 总数：${form.runtime.tmReplicas * form.runtime.slots}`
                    : '可指定固定 TaskManager 数量；已有配置留空时沿用默认行为。'
              }}
            </p>
          </div>
        </section>
      </div>
      <div class="editor-form-column">
        <section class="panel form-panel compact-form">
          <h3><span class="section-number">03</span> 程序与依赖</h3>
          <ArtifactPicker
            data-field="runnerArtifactId"
            v-model="form.runtime.runnerArtifactId"
            category="main"
            runner
            :disabled="busy || uploading"
            @uploading="uploading = $event"
          />
          <p class="muted">
            手动选择与运行镜像兼容的 SQL Runner，支持 HTTP/HTTPS 文件地址或
            s3://桶名/对象路径，不会自动选择。
          </p>
          <div class="session-dependencies">
            <ArtifactPicker
              v-model="form.runtime.dependencyArtifactIds"
              :artifacts="[]"
              category="dependency"
              :disabled="busy || uploading"
              @uploading="uploading = $event"
            />
          </div>
          <p class="muted session-hint">
            连接器 JAR 可随每次 SQL 验证提交。环境变量和非 JAR 配置文件需在集群启动前准备。
          </p>
        </section>
        <section class="panel form-panel compact-form">
          <h3><span class="section-number">04</span> Flink 配置</h3>
          <FlinkConfigEditor
            data-field="flinkConfiguration"
            v-model="form.runtime.flinkConfiguration"
            :readonly="busy || uploading"
            @valid="configValid = $event"
          />
          <PodTemplateFields
            v-model="pods"
            :readonly="busy || uploading"
            @valid="podValid = $event"
          />
        </section>
      </div>
    </fieldset>
    <div class="form-actions editor-actionbar">
      <span class="muted"
        >{{ configurationDirty ? '有未保存的配置 · ' : '' }}保存配置后不会自动启动集群</span
      >
      <button type="button" class="button" :disabled="busy || uploading" @click="editing = false">
        取消
      </button>
      <button class="button primary" :disabled="busy || uploading">
        {{ busy ? '保存中…' : '保存配置' }}
      </button>
    </div>
  </form>
</template>
<style scoped>
.session-form-grid {
  margin: 0;
  padding: 0;
  border: 0;
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  align-items: start;
}
.session-purpose {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}
.session-purpose .muted,
.session-hint {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.7;
}
.session-dependencies {
  margin-top: 16px;
}
@media (max-width: 1100px) {
  .session-form-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
