<script setup lang="ts">
import PageHeader from '../../../shared/components/layout/PageHeader.vue'
import JobWorkbench from '../components/JobWorkbench.vue'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { snapshotOptions, snapshotTime } from '../utils/snapshots'
import { requestId } from '../../../shared/utils/requestId'
import { jobsApi } from '../api/jobs'
import { request, jsonBody } from '../../../shared/api/http'
import { currentUser } from '../../permission/stores/auth'
import { useManualRefresh } from '../../../shared/composables/useManualRefresh'
import type { JobDraft } from '../../../shared/types/api'
interface Runtime {
  yaml: string
  uid: string
  namespace: string
  managed: boolean
  canAdopt: boolean
  registrationConflict: boolean
  application: boolean
  deleting: boolean
  flinkVersion: string
  image: string
  jobId?: string
  name: string
  desiredState: string
  jobState: string
  lifecycleState: string
  resourceVersion: string
  savepointInfo: unknown
  checkpointInfo: unknown
  snapshots: unknown[]
}
interface Operation {
  id: string
  action: string
  state: string
  message: string
  updatedAt: string
}
const jobAccess = ref<Record<string, { canEdit: boolean; canOperate: boolean }>>({})
const mayEdit = (job: JobDraft) =>
  !!developer.value && (!!admin.value || jobAccess.value[job.id]?.canEdit === true)
const mayOperate = (job: JobDraft) =>
  !!operator.value && (!!admin.value || jobAccess.value[job.id]?.canOperate === true)
const drafts = ref<JobDraft[]>([]),
  runtime = ref<Runtime[]>([]),
  query = ref(''),
  error = ref(''),
  message = ref(''),
  queriedAt = ref('')
const loading = ref(true),
  acting = ref(false),
  selected = ref<JobDraft | null>(null),
  history = ref<Operation[]>([])
const yamlDialog = ref<HTMLDialogElement>()
const yamlName = ref('')
const selectedYaml = computed(
  () => runtime.value.find((item) => item.name === yamlName.value)?.yaml || '',
)
function showYaml(name: string) {
  yamlName.value = name
  yamlDialog.value?.showModal()
}
const detailEpoch = ref(0)
const mode = ref('savepoint'),
  restorePath = ref('')
const controller = new AbortController()
const developer = computed(() =>
  currentUser.value?.roles.some((r) => ['ADMIN', 'DEVELOPER'].includes(r.replace('ROLE_', ''))),
)
const admin = computed(() => currentUser.value?.roles.includes('ROLE_ADMIN'))
const unregistered = computed(() =>
  runtime.value.filter(
    (job) => !job.managed && job.name.toLowerCase().includes(query.value.toLowerCase()),
  ),
)
const adopting = ref('')
const operator = computed(
  () => developer.value || currentUser.value?.roles.some((r) => r.endsWith('OPERATOR')),
)
const statusFilter = ref(''),
  versionFilter = ref(''),
  page = ref(1),
  pageSize = ref(10)
const selectedIds = ref<string[]>([]),
  operationQuery = ref('')
const filtered = computed(() =>
  drafts.value.filter(
    (job) =>
      (job.name + job.description + job.id).toLowerCase().includes(query.value.toLowerCase()) &&
      (!statusFilter.value || (state(job)?.jobState || 'UNDEPLOYED') === statusFilter.value) &&
      (!versionFilter.value || job.flinkVersion === versionFilter.value),
  ),
)
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize.value)))
const visible = computed(() =>
  filtered.value.slice(
    (Math.min(page.value, pages.value) - 1) * pageSize.value,
    Math.min(page.value, pages.value) * pageSize.value,
  ),
)
const statuses = computed(() => [...new Set(runtime.value.map((j) => j.jobState))])
const checkedJobs = computed(() => drafts.value.filter((j) => selectedIds.value.includes(j.id)))
const filteredHistory = computed(() =>
  history.value.filter((op) =>
    JSON.stringify(op).toLowerCase().includes(operationQuery.value.toLowerCase()),
  ),
)
watch([query, statusFilter, versionFilter, pageSize], () => (page.value = 1))
function togglePage(event: Event) {
  const ids = visible.value.map((j) => j.id)
  selectedIds.value = (event.target as HTMLInputElement).checked
    ? [...new Set([...selectedIds.value, ...ids])]
    : selectedIds.value.filter((id) => !ids.includes(id))
}
const operationDialog = ref<HTMLDialogElement>(),
  pendingAction = ref(''),
  targets = ref<JobDraft[]>([]),
  startMode = ref('normal'),
  paths = ref<Record<string, string>>({}),
  results = ref<{ name: string; state: string; message: string }[]>([])
const actionLabels: Record<string, string> = {
  start: '启动',
  stop: '停止',
  suspend: '停止',
  savepoint: 'Savepoint',
  checkpoint: 'Checkpoint',
}
function openAction(action: string, jobs: JobDraft[]) {
  if (!jobs.length) return
  if (jobs.some((job) => !mayOperate(job))) {
    error.value = '所选作业中包含没有操作权限的作业'
    return
  }
  error.value = ''
  targets.value = [...jobs]
  pendingAction.value = action
  startMode.value = 'normal'
  mode.value = 'savepoint'
  paths.value = {}
  results.value = []
  operationDialog.value?.showModal()
}
const readyToSubmit = computed(
  () =>
    pendingAction.value !== 'start' ||
    startMode.value === 'normal' ||
    targets.value.every((j) =>
      snapshotOptions(state(j)).some(
        (p) => p.type === startMode.value && p.path === paths.value[j.id],
      ),
    ),
)
async function confirmAction() {
  acting.value = true
  results.value = []
  try {
    for (const job of targets.value) {
      try {
        const command =
          pendingAction.value === 'start' && startMode.value !== 'normal'
            ? 'restore'
            : pendingAction.value
        const result = await request<Operation>('/api/jobs/' + job.id + '/actions/' + command, {
          method: 'POST',
          ...jsonBody({
            requestId: requestId(),
            revision: job.revision,
            ...(command === 'restore'
              ? { upgradeMode: 'savepoint' }
              : ['stop', 'suspend'].includes(pendingAction.value)
                ? { upgradeMode: mode.value }
                : {}),
            savepointPath: paths.value[job.id] || '',
          }),
        })
        results.value.push({ name: job.name, state: result.state, message: result.message })
      } catch (e) {
        results.value.push({ name: job.name, state: 'REJECTED', message: (e as Error).message })
      }
    }
    message.value =
      '已提交 ' +
      results.value.filter((r) => r.state === 'ACCEPTED').length +
      ' / ' +
      targets.value.length +
      ' 个操作；运行结果请手动刷新。'
  } finally {
    acting.value = false
  }
}
function badgeClass(value?: string) {
  return value === 'RUNNING'
    ? 'running'
    : ['FAILED', 'ERROR'].includes(value || '')
      ? 'failed'
      : ['SUSPENDED', 'CANCELED', 'FINISHED'].includes(value || '')
        ? 'suspended'
        : 'neutral'
}

const state = (job: JobDraft) => runtime.value.find((item) => item.name === job.name)
async function load() {
  const [items, permissions] = await Promise.all([
    jobsApi.list(),
    request<Record<string, { canEdit: boolean; canOperate: boolean }>>('/api/platform/job-access'),
  ])
  drafts.value = items
  jobAccess.value = permissions
  loading.value = false
}
const {
  refresh,
  run: queryRun,
  busy,
  disabled,
  remaining,
} = useManualRefresh(async () => {
  error.value = ''
  try {
    const result = await request<{ items: Runtime[]; queriedAt: string }>('/api/jobs/refresh', {
      method: 'POST',
      signal: controller.signal,
    })
    runtime.value = result.items
    queriedAt.value = result.queriedAt
    await load()
  } catch (e) {
    if ((e as Error).name !== 'AbortError') {
      error.value = (e as Error).message
      runtime.value = []
      queriedAt.value = ''
    }
  }
})
// 快照选择和列表共用当前页面的 3 秒节流；不增加轮询或共享缓存。
const snapshotsRefresh = { refresh, busy, disabled, remaining }
watch(startMode, (value) => {
  paths.value = {}
  if (value !== 'normal') void snapshotsRefresh.refresh()
})
onMounted(async () => {
  try {
    await load()
  } catch (e) {
    error.value = (e as Error).message
    loading.value = false
  }
  void refresh()
})
onUnmounted(() => controller.abort())
async function adopt(job: Runtime) {
  if (
    !window.confirm(
      '纳管「' +
        job.name +
        '」？将保存现有 YAML 并绑定 Kubernetes UID，纳管本身不会修改配置或重启作业。',
    )
  )
    return
  adopting.value = job.uid
  error.value = ''
  message.value = ''
  try {
    const saved = await request<JobDraft>('/api/jobs/adopt', {
      method: 'POST',
      ...jsonBody({
        name: job.name,
        uid: job.uid,
        resourceVersion: job.resourceVersion,
      }),
    })
    await load()
    job.managed = true
    job.canAdopt = false
    job.jobId = saved.id
    message.value =
      '已纳管 ' + job.name + '，运行配置未修改。后续可查看 YAML、启停、快照和 Flink UI。'
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    adopting.value = ''
  }
}
async function details(job: JobDraft) {
  selected.value = job
  detailEpoch.value++
  try {
    history.value = await request<Operation[]>('/api/jobs/' + job.id + '/operations')
  } catch (e) {
    error.value = (e as Error).message
  }
}
async function action(job: JobDraft, action: string) {
  if (
    !window.confirm('确认对「' + job.name + '」执行 ' + action + '？操作提交后需手动刷新确认结果。')
  )
    return
  acting.value = true
  error.value = ''
  message.value = ''
  try {
    const result = await request<Operation>('/api/jobs/' + job.id + '/actions/' + action, {
      method: 'POST',
      ...jsonBody({
        requestId: requestId(),
        revision: job.revision,
        upgradeMode: mode.value,
        savepointPath: restorePath.value,
      }),
    })
    message.value = result.state + '：' + result.message
    await details(job)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    acting.value = false
  }
}
function canStop(job: JobDraft) {
  const live = state(job)
  return (
    mayOperate(job) &&
    !acting.value &&
    live?.managed &&
    live.jobState === 'RUNNING' &&
    live.desiredState !== 'suspended'
  )
}
function canDelete(job: JobDraft) {
  const live = state(job)
  return (
    mayEdit(job) &&
    !acting.value &&
    !!queriedAt.value &&
    (!live ||
      (live.managed &&
        !live.deleting &&
        live.desiredState !== 'suspended' &&
        ['FAILED', 'FINISHED', 'CANCELED'].includes(live.jobState)))
  )
}
async function deleteJob(job: JobDraft) {
  if (!canDelete(job)) return
  if (state(job)) {
    await action(job, 'delete')
    message.value += '。资源实际删除后，请手动刷新，再删除平台草稿。'
  } else await remove(job)
}
async function restored(job: JobDraft) {
  selected.value = job
  await load()
}
async function remove(job: JobDraft) {
  if (!window.confirm('删除平台草稿「' + job.name + '」？已部署作业需要先删除集群资源。')) return
  try {
    await jobsApi.remove(job)
    await load()
    selected.value = null
  } catch (e) {
    error.value = (e as Error).message
  }
}

function resetFilters() {
  query.value = ''
  statusFilter.value = ''
  versionFilter.value = ''
}
</script>
<template>
  <PageHeader eyebrow="WORKLOADS"
    ><template #title>作业管理</template
    ><template #description>单 namespace 管理 · 首次进入查询一次，之后按需刷新</template
    ><template #actions
      ><div v-if="developer" class="toolbar-actions">
        <RouterLink class="button primary" to="/jobs/jar/new">＋ 新建 JAR 作业</RouterLink>
      </div></template
    ></PageHeader
  >
  <div class="stats-grid">
    <div class="stat-card">
      <span>平台作业</span><strong>{{ drafts.length }}</strong
      ><small>持久化配置与提交记录</small>
    </div>
    <div class="stat-card">
      <span>运行中</span
      ><strong>{{
        queriedAt ? runtime.filter((j) => j.jobState === 'RUNNING').length : '—'
      }}</strong
      ><small>以最近一次集群查询为准</small>
    </div>
    <div class="stat-card">
      <span>已部署</span><strong>{{ queriedAt ? runtime.length : '—' }}</strong
      ><small>当前 namespace 的资源</small>
    </div>
    <div class="stat-card accent">
      <span>刷新间隔</span><strong>3 <em>秒</em></strong
      ><small>用户与标签页分别计时</small>
    </div>
  </div>
  <div v-if="error" class="notice error" role="alert">{{ error }}</div>
  <div v-if="message" class="notice">{{ message }}</div>

  <section class="panel jobs-panel">
    <div class="panel-toolbar">
      <div class="panel-heading">
        全部作业 <span class="count">{{ drafts.length }}</span
        ><span v-if="selectedIds.length" class="selection-summary"
          >已选 {{ selectedIds.length }} 项</span
        >
      </div>
      <div class="toolbar-actions">
        <template v-if="operator"
          ><button
            v-for="entry in [
              ['start', '启动'],
              ['stop', '停止'],
              ['savepoint', 'Savepoint'],
              ['checkpoint', 'Checkpoint'],
            ]"
            :key="entry[0]"
            class="button"
            :disabled="!checkedJobs.length || acting"
            @click="openAction(entry[0]!, checkedJobs)"
          >
            {{ entry[1] }}
          </button></template
        ><button class="button" :disabled="disabled" @click="refresh">
          {{ busy ? '正在查询…' : remaining ? remaining + ' 秒后刷新' : '↻ 刷新状态' }}
        </button>
      </div>
    </div>
    <div class="filter-bar">
      <input v-model="query" class="search" placeholder="搜索作业名称 / ID / 描述" /><select
        v-model="statusFilter"
        aria-label="状态筛选"
      >
        <option value="">全部状态</option>
        <option value="UNDEPLOYED">未部署 / 未查询</option>
        <option v-for="status in statuses" :key="status">{{ status }}</option></select
      ><select v-model="versionFilter" aria-label="版本筛选">
        <option value="">全部版本</option>
        <option v-for="version in [...new Set(drafts.map((j) => j.flinkVersion))]" :key="version">
          {{ version }}
        </option></select
      ><button class="text-button" @click="resetFilters">重置</button
      ><span class="filter-count">{{ filtered.length }} 个结果</span>
    </div>
    <div v-if="loading" class="empty-state">正在读取作业…</div>
    <div v-else-if="!filtered.length" class="empty-state">
      <div class="empty-icon">◇</div>
      <h3>{{ drafts.length ? '没有匹配的作业' : '当前没有 Flink 作业' }}</h3>
      <p>{{ drafts.length ? '调整筛选条件后重试。' : '创建第一个作业，开始实时计算任务。' }}</p>
    </div>
    <div v-else class="table-wrap">
      <table>
        <thead>
          <tr>
            <th class="check-column">
              <input
                type="checkbox"
                aria-label="选择当前页"
                :checked="visible.length > 0 && visible.every((j) => selectedIds.includes(j.id))"
                @change="togglePage"
              />
            </th>
            <th>作业名称</th>
            <th>Flink 版本</th>
            <th>期望状态</th>
            <th>观测状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="job in visible"
            :key="job.id"
            :class="{ 'failed-row': state(job)?.jobState === 'FAILED' }"
          >
            <td class="check-column">
              <input
                v-model="selectedIds"
                type="checkbox"
                :value="job.id"
                :aria-label="'选择 ' + job.name"
              />
            </td>
            <td>
              <button class="text-button job-name" @click="details(job)">{{ job.name }}</button
              ><small class="table-description">{{ job.description || 'Job ID: ' + job.id }}</small>
            </td>
            <td>
              <span class="version-pill">{{ job.flinkVersion }}</span>
            </td>
            <td>{{ state(job)?.desiredState || '—' }}</td>
            <td>
              <span class="badge" :class="badgeClass(state(job)?.jobState)">{{
                state(job)?.registrationConflict
                  ? '资源身份冲突'
                  : state(job)?.jobState || (queriedAt ? '未部署' : '未查询')
              }}</span>
            </td>
            <td class="row-actions">
              <button class="text-button" :disabled="!state(job)?.yaml" @click="showYaml(job.name)">
                当前 YAML
              </button>
              <template v-if="mayOperate(job)"
                ><button
                  class="text-button"
                  :disabled="acting || !state(job)?.managed || state(job)?.jobState === 'RUNNING'"
                  @click="openAction('start', [job])"
                >
                  启动</button
                ><button
                  class="text-button"
                  :disabled="!canStop(job)"
                  @click="openAction('stop', [job])"
                >
                  停止
                </button></template
              ><RouterLink v-if="mayEdit(job)" class="text-button" :to="'/jobs/' + job.id + '/edit'"
                >编辑</RouterLink
              ><button
                v-if="developer"
                class="text-button danger"
                :disabled="!canDelete(job)"
                title="运行中、停止或状态未知时禁止删除"
                @click="deleteJob(job)"
              >
                删除</button
              ><a
                v-if="state(job)?.managed"
                class="text-button"
                :href="'/flink-ui/' + job.id + '/'"
                target="_blank"
                rel="noopener"
                >Flink UI ↗</a
              >
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="pagination">
      <span
        >最近查询：{{ queriedAt ? new Date(queriedAt).toLocaleString('zh-CN') : '尚未成功查询' }} ·
        按需刷新</span
      >
      <div class="toolbar-actions">
        <select v-model.number="pageSize" aria-label="每页条数">
          <option :value="10">10 条 / 页</option>
          <option :value="20">20 条 / 页</option>
          <option :value="50">50 条 / 页</option></select
        ><button class="button" :disabled="page <= 1" @click="page--">上一页</button
        ><span>{{ Math.min(page, pages) }} / {{ pages }}</span
        ><button class="button" :disabled="page >= pages" @click="page++">下一页</button>
      </div>
    </div>
  </section>
  <dialog
    ref="operationDialog"
    class="operation-dialog"
    @cancel="acting && $event.preventDefault()"
  >
    <div class="panel-toolbar">
      <h3>
        {{ actionLabels[pendingAction] }}作业 <span class="count">{{ targets.length }}</span>
      </h3>
      <button
        class="text-button"
        :disabled="acting"
        aria-label="关闭操作弹窗"
        @click="operationDialog?.close()"
      >
        ×
      </button>
    </div>
    <div class="dialog-body">
      <div v-if="error" class="notice error" role="alert">{{ error }}</div>
      <p class="muted">目标：{{ targets.map((j) => j.name).join('、') }}</p>
      <template v-if="!results.length">
        <fieldset v-if="pendingAction === 'start'" :disabled="acting" class="choice-group">
          <legend>启动方式</legend>
          <label><input v-model="startMode" type="radio" value="normal" />按当前策略启动</label
          ><label
            ><input v-model="startMode" type="radio" value="savepoint" />从 Savepoint 恢复</label
          ><label
            ><input v-model="startMode" type="radio" value="checkpoint" />从 Checkpoint 恢复</label
          >
        </fieldset>
        <div v-if="pendingAction === 'start' && startMode !== 'normal'">
          <p class="muted">
            仅显示 Operator 已报告路径的已完成快照；与列表共用 3 秒刷新间隔。最近查询：{{
              queriedAt ? new Date(queriedAt).toLocaleString() : '尚未查询'
            }}。请确认 S3 对象仍存在。
          </p>
          <button
            class="button"
            :disabled="snapshotsRefresh.disabled.value || acting"
            @click="snapshotsRefresh.refresh"
          >
            {{
              snapshotsRefresh.remaining.value
                ? snapshotsRefresh.remaining.value + ' 秒后可刷新'
                : '刷新快照列表'
            }}</button
          ><label v-for="job in targets" :key="job.id" class="restore-choice"
            >{{ job.name
            }}<select v-model="paths[job.id]" :aria-label="job.name + ' 恢复快照'">
              <option value="">选择已完成的快照</option>
              <option
                v-for="point in snapshotOptions(state(job)).filter((p) => p.type === startMode)"
                :key="point.path"
                :value="point.path"
              >
                {{ snapshotTime(point.time) }} · {{ point.path }}
              </option></select
            ><small v-if="!snapshotOptions(state(job)).some((p) => p.type === startMode)"
              >暂无可恢复路径。旧版本 Operator 可能不提供 Checkpoint
              路径，请使用详情中的指定路径恢复。</small
            ></label
          >
        </div>
        <label v-if="['stop', 'suspend'].includes(pendingAction)" class="restore-choice"
          >停止策略<select v-model="mode" :disabled="acting">
            <option value="savepoint">生成 Savepoint 后停止</option>
            <option value="stateless">立即停止，不保留本次状态</option>
            <option value="last-state">使用 HA 保留状态</option>
          </select></label
        >
        <p class="muted dialog-note">
          批量操作逐个提交，失败不影响其他作业；API 接受不代表作业已完成操作。
        </p>
      </template>
      <table v-else>
        <thead>
          <tr>
            <th>作业</th>
            <th>提交结果</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="result in results" :key="result.name">
            <td>{{ result.name }}</td>
            <td>{{ result.state }}</td>
            <td>{{ result.message }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="dialog-footer">
      <button class="button" :disabled="acting" @click="operationDialog?.close()">
        {{ results.length ? '关闭' : '取消' }}</button
      ><button
        v-if="!results.length"
        class="button primary"
        :disabled="acting || !readyToSubmit || snapshotsRefresh.busy.value"
        @click="confirmAction"
      >
        {{ acting ? '正在提交…' : '确认' + actionLabels[pendingAction] }}
      </button>
    </div>
  </dialog>
  <section v-if="unregistered.length" class="panel detail-panel">
    <div class="panel-toolbar">
      <div>
        <h3>
          Namespace 中发现的既有作业 <span class="count">{{ unregistered.length }}</span>
        </h3>
        <p class="muted">这些资源尚未纳入平台管理。纳管只登记元数据，不改动现有作业。</p>
      </div>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>作业名称</th>
            <th>Flink 版本</th>
            <th>观测状态</th>
            <th>登记情况</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="job in unregistered" :key="job.uid || job.name">
            <td>
              <strong>{{ job.name }}</strong
              ><small class="table-description">{{ job.namespace }} · {{ job.uid }}</small>
            </td>
            <td>{{ job.flinkVersion }}</td>
            <td>
              <span class="badge">{{ job.jobState }}</span>
            </td>
            <td>
              {{
                job.registrationConflict
                  ? '同名草稿或 UID 绑定冲突'
                  : job.deleting
                    ? '正在删除'
                    : !job.application
                      ? 'Session 集群暂不支持'
                      : '可纳管'
              }}
            </td>
            <td>
              <button class="text-button" :disabled="!job.yaml" @click="showYaml(job.name)">
                当前 YAML
              </button>
              <button
                v-if="admin"
                class="button"
                :disabled="!job.canAdopt || !!adopting"
                @click="adopt(job)"
              >
                {{ adopting === job.uid ? '正在纳管…' : '纳管作业' }}</button
              ><span v-else class="muted">由管理员纳管</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
  <dialog ref="yamlDialog" class="yaml-snapshot-dialog">
    <div class="panel-toolbar">
      <h3>{{ yamlName }} · 当前 FlinkDeployment YAML</h3>
      <button class="text-button" @click="yamlDialog?.close()">关闭</button>
    </div>
    <p class="muted">
      最近刷新：{{
        queriedAt ? new Date(queriedAt).toLocaleString() : '尚未查询'
      }}。敏感字段已隐藏，此快照仅供查询。打开不会再次请求集群。
    </p>
    <pre class="yaml-snapshot-content">{{
      selectedYaml || '当前没有可用快照，请关闭后刷新列表。'
    }}</pre>
  </dialog>
  <section v-if="selected" class="panel detail-panel">
    <div class="panel-toolbar">
      <h3>{{ selected.name }}</h3>
      <button class="text-button" @click="selected = null">收起</button>
    </div>
    <div class="form-panel">
      <div v-if="mayOperate(selected)" class="toolbar-actions wrap">
        <label
          >状态升级策略<select v-model="mode">
            <option>savepoint</option>
            <option>last-state</option>
            <option>stateless</option>
          </select></label
        >
        <button
          v-if="mayEdit(selected)"
          class="button primary"
          :disabled="acting"
          @click="action(selected, 'deploy')"
        >
          部署草稿
        </button>
        <button
          v-for="entry in [
            ['start', '启动'],
            ['stop', '停止'],
            ['savepoint', 'Savepoint'],
            ['checkpoint', 'Checkpoint'],
          ]"
          :key="entry[0]"
          class="button"
          :disabled="acting || (entry[0] === 'stop' && !canStop(selected))"
          @click="
            ['start', 'stop', 'suspend', 'savepoint', 'checkpoint'].includes(entry[0]!)
              ? openAction(entry[0]!, [selected])
              : action(selected, entry[0]!)
          "
        >
          {{ entry[1] }}
        </button>
      </div>
      <p class="muted">
        savepoint：保存状态后变更；last-state：从 HA
        状态恢复；stateless：丢弃状态。新作业默认只保留最近一次已完成
        checkpoint；纳管作业保留原策略，savepoint 单独保留。
      </p>
      <div v-if="mayOperate(selected)" class="toolbar-actions">
        <input
          v-model="restorePath"
          placeholder="s3://bucket/job/savepoints/savepoint-xxx"
          aria-label="恢复路径"
        /><button
          class="button"
          :disabled="acting || !restorePath"
          @click="action(selected, 'restore')"
        >
          从指定路径恢复
        </button>
      </div>
      <details>
        <summary>最近快照信息</summary>
        <pre>{{
          JSON.stringify(
            {
              savepoint: state(selected)?.savepointInfo,
              checkpoint: state(selected)?.checkpointInfo,
              snapshots: state(selected)?.snapshots,
            },
            null,
            2,
          )
        }}</pre>
      </details>
      <div class="panel-toolbar">
        <h3>操作记录</h3>
        <input v-model="operationQuery" class="search" placeholder="筛选操作记录" />
      </div>
      <div v-if="!history.length" class="muted">暂无操作</div>
      <table v-else>
        <thead>
          <tr>
            <th>操作</th>
            <th>提交结果</th>
            <th>说明</th>
            <th>时间</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="op in filteredHistory
              .slice()
              .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))"
            :key="op.id"
          >
            <td>{{ op.action }}</td>
            <td>{{ op.state }}</td>
            <td>{{ op.message }}</td>
            <td>{{ new Date(op.updatedAt).toLocaleString() }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="developer" class="toolbar-actions">
        <button
          class="button danger"
          :disabled="!canDelete(selected)"
          @click="action(selected, 'delete')"
        >
          删除集群资源</button
        ><button
          class="text-button danger"
          :disabled="!canDelete(selected) || !!state(selected)"
          @click="remove(selected)"
        >
          删除平台草稿
        </button>
      </div>
    </div>
    <JobWorkbench
      :key="selected.id + detailEpoch"
      :job="selected"
      :query="queryRun"
      :query-disabled="disabled"
      :remaining="remaining"
      @restored="restored"
    />
  </section>
</template>
