<script setup lang="ts">
import { showValidationError } from '../../../shared/utils/fieldValidation'
import { confirmDialog } from '../../../shared/utils/confirmDialog'
import { computed, ref, watch } from 'vue'
import { workbenchApi } from '../api/workbench'
import LogViewer from '../../../shared/components/editor/LogViewer.vue'
import { permissionApi } from '../../permission/api/permission'
import { currentUser } from '../../permission/stores/auth'
import type { JobDraft } from '../../../shared/types/api'
import type {
  Release,
  Snapshot,
  JobAccess,
  Group,
  OperationObservation,
  Diagnostic,
} from '../../../shared/types/workbench'
const props = defineProps<{
  job: JobDraft
  query: (task: () => Promise<void>) => Promise<void>
  queryDisabled: boolean
  remaining: number
}>()
const emit = defineEmits<{ restored: [JobDraft] }>()
const tab = ref('history'),
  releases = ref<Release[]>([]),
  snapshots = ref<Snapshot[]>([]),
  acl = ref<JobAccess>({
    revision: 0,
    owner: '',
    project: '',
    canEdit: false,
    canOperate: false,
    canManage: false,
  }),
  groups = ref<Group[]>([]),
  operations = ref<OperationObservation[]>([])
const error = ref(''),
  message = ref(''),
  busy = ref(false),
  diagnostic = ref<Diagnostic | null>(null),
  log = ref(''),
  pod = ref(''),
  container = ref(''),
  previous = ref(false)
const releaseId = ref(''),
  compareWith = ref('current'),
  editGroups = ref(''),
  readGroups = ref(''),
  operateGroups = ref(''),
  links = ref('[]')
const developer = computed(() =>
  currentUser.value?.roles.some((r) => ['ROLE_ADMIN', 'ROLE_DEVELOPER'].includes(r)),
)
const operator = computed(() =>
  currentUser.value?.roles.some((r) =>
    ['ROLE_ADMIN', 'ROLE_DEVELOPER', 'ROLE_OPERATOR'].includes(r),
  ),
)
const selectedRelease = computed(() => releases.value.find((r) => r.id === releaseId.value))
const comparison = computed(() => {
  const a = String(selectedRelease.value?.sourceYaml || '').split('\n')
  const b = String(
    compareWith.value === 'current'
      ? props.job.yaml
      : releases.value.find((r) => r.id === compareWith.value)?.sourceYaml || '',
  ).split('\n')
  return Array.from({ length: Math.min(2000, Math.max(a.length, b.length)) }, (_, i) => ({
    line: i + 1,
    before: a[i] ?? '',
    after: b[i] ?? '',
    changed: a[i] !== b[i],
  }))
})
const pods = computed(() => diagnostic.value?.pods || [])
const containers = computed(() => pods.value.find((p) => p.name === pod.value)?.containers || [])
let logEpoch = 0
watch(pod, () => (container.value = containers.value[0] || ''))
watch(
  [pod, container, previous],
  () => {
    log.value = ''
    logEpoch++
  },
  { flush: 'sync' },
)
watch(
  () => props.job.id,
  () => {
    diagnostic.value = null
    log.value = ''
    logEpoch++
    pod.value = ''
    tab.value = 'history'
    void load()
  },
  { immediate: true },
)
async function run(task: () => Promise<void>) {
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
  const id = props.job.id
  await run(async () => {
    const [r, s, a, g, o] = await Promise.all([
      workbenchApi.releases(id),
      workbenchApi.snapshots(id),
      workbenchApi.access(id),
      permissionApi.groups(),
      workbenchApi.operations(id),
    ])
    if (props.job.id !== id) return
    releases.value = r
    snapshots.value = s
    acl.value = a
    groups.value = g
    operations.value = o
    if (!r.some((x) => x.id === releaseId.value)) releaseId.value = r[0]?.id || ''
    readGroups.value = (a.readGroups || []).join(',')
    operateGroups.value = (a.operateGroups || []).join(',')
    editGroups.value = (a.editGroups || []).join(',')
    links.value = JSON.stringify(a.externalLinks || [], null, 2)
  })
}
function diagnose() {
  return props.query(() =>
    run(async () => {
      const id = props.job.id
      const result = await workbenchApi.diagnostics(id)
      if (id !== props.job.id) return
      diagnostic.value = result
      pod.value = result.pods[0]?.name || ''
      log.value = ''
    }),
  )
}
function readLog() {
  return props.query(() =>
    run(async () => {
      const id = props.job.id
      const epoch = ++logEpoch
      log.value = ''
      try {
        const result = await workbenchApi.logs(id, {
          pod: pod.value,
          container: container.value,
          lines: 10000,
          previous: previous.value,
        })
        if (id === props.job.id && epoch === logEpoch) log.value = result.text
      } catch (e) {
        if (id === props.job.id && epoch === logEpoch) throw e
      }
    }),
  )
}
async function restore() {
  if (
    !selectedRelease.value ||
    !(await confirmDialog(
      '将此版本恢复为新的草稿修订？不会自动部署。密钥引用按当前配置解析，运行状态不会回滚。',
    ))
  )
    return
  await run(async () => {
    const result = await workbenchApi.restore(props.job.id, releaseId.value, props.job.revision)
    emit('restored', result)
    message.value = '历史版本已恢复为草稿；请检查差异后手动部署。'
  })
}
function checkSnapshot(point: Snapshot) {
  return props.query(() =>
    run(async () => {
      const result = await workbenchApi.checkSnapshot(props.job.id, point.id)
      point.lastCheck = result
      message.value = result.message
    }),
  )
}
async function saveAccess() {
  await run(async () => {
    const split = (s: string) =>
      s
        .split(',')
        .map((x) => x.trim())
        .filter(Boolean)
    acl.value = await workbenchApi.saveAccess(props.job.id, {
      ...acl.value,
      readGroups: split(readGroups.value),
      operateGroups: split(operateGroups.value),
      editGroups: split(editGroups.value),
      externalLinks: JSON.parse(links.value),
    })
    message.value = '归属与权限已更新'
    await load()
  })
}
watch(tab, () => {
  error.value = ''
  message.value = ''
})
const time = (value?: string) => (value ? new Date(value).toLocaleString('zh-CN') : '—')
</script>
<template>
  <div class="job-workbench">
    <nav class="workbench-tabs" aria-label="作业详情">
      <button
        v-for="item in [
          ['history', '操作记录'],
          ['releases', '发布版本'],
          ['diagnostics', '诊断与日志'],
          ['snapshots', '快照台账'],
          ['operations', '操作观测'],
          ['access', '归属与链接'],
        ]"
        :key="item[0]"
        :class="{ active: tab === item[0] }"
        @click="tab = item[0]!"
      >
        {{ item[1] }}</button
      ><button class="text-button" :disabled="busy || queryDisabled" @click="props.query(load)">
        {{ remaining ? remaining + ' 秒后刷新' : '刷新记录' }}
      </button>
    </nav>
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <div v-if="message" class="notice" role="status">{{ message }}</div>
    <div v-if="tab === 'history'"><slot name="history" :records="operations" /></div>
    <div class="workbench-body" v-if="tab === 'releases'">
      <div v-if="releases.length" class="toolbar-actions release-controls">
        <label
          >发布版本<select data-field="releaseId" v-model="releaseId">
            <option v-for="release in releases" :key="release.id" :value="release.id">
              {{ time(release.createdAt) }} · {{ release.actor }} · 草稿 {{ release.draftRevision }}
            </option>
          </select></label
        ><label
          >对照版本<select data-field="compareWith" v-model="compareWith">
            <option value="current">当前草稿</option>
            <option v-for="release in releases" :key="release.id" :value="release.id">
              {{ time(release.createdAt) }}
            </option>
          </select></label
        ><button
          v-if="developer && acl.canEdit"
          class="button"
          :disabled="!selectedRelease || busy"
          @click="restore"
        >
          恢复为草稿
        </button>
      </div>
      <div v-if="!releases.length" class="release-empty">
        <strong>暂无发布版本</strong>
        <p>部署草稿通过预检查后，会生成发布版本。之后可在这里对比配置并恢复草稿。</p>
      </div>
      <template v-if="selectedRelease"
        ><div class="release-summary">
          <span>Flink {{ selectedRelease.flinkVersion }}</span
          ><span class="mono">{{ selectedRelease.image }}</span
          ><span>制品 {{ selectedRelease.artifacts?.length || 0 }} 个</span>
        </div>
        <p class="muted">
          内容 SHA-256：{{ selectedRelease.contentSha256 }}。镜像使用 tag
          时无法保证镜像标签不被外部覆盖。
        </p>
        <details>
          <summary>制品与配置引用</summary>
          <pre>{{
            JSON.stringify(
              {
                artifacts: selectedRelease.artifacts,
                configReferences: selectedRelease.configReferences,
              },
              null,
              2,
            )
          }}</pre>
        </details>
        <p class="muted">
          YAML 按行号对照，变化行着色（最多展示 2000 行）。恢复草稿不会恢复 Flink 状态。
        </p>
        <div class="diff-table">
          <div class="diff-header">
            <strong>所选发布版本</strong
            ><strong>{{ compareWith === 'current' ? '当前草稿' : '对照发布版本' }}</strong>
          </div>
          <div
            v-for="row in comparison"
            :key="row.line"
            class="diff-line"
            :class="{ changed: row.changed }"
          >
            <pre><span>{{ row.line }}</span>{{ row.before }}</pre>
            <pre><span>{{ row.line }}</span>{{ row.after }}</pre>
          </div>
        </div></template
      >
    </div>
    <div class="workbench-body" v-if="tab === 'diagnostics'">
      <div class="toolbar-actions">
        <button class="button" :disabled="busy || queryDisabled" @click="diagnose">
          {{ remaining ? remaining + ' 秒后可查询' : '查询事件与 Pod' }}</button
        ><span class="muted">与列表共用 3 秒冷却；不自动跟随或后台采集。</span>
      </div>
      <template v-if="diagnostic"
        ><p class="muted">本次查询 {{ time(diagnostic.queriedAt) }}</p>
        <div v-if="diagnostic.error" class="notice error">{{ diagnostic.error }}</div>
        <table>
          <thead>
            <tr>
              <th>Pod</th>
              <th>状态</th>
              <th>容器情况</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in pods" :key="item.name">
              <td>{{ item.name }}</td>
              <td>{{ item.phase }}</td>
              <td>
                <span v-for="state in item.states" :key="state.name"
                  >{{ state.name }} · 重启 {{ state.restarts }} · {{ state.reason }}
                  {{ state.message }}<br
                /></span>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="operator && acl.canOperate" class="toolbar-actions log-controls">
          <select data-field="podTemplate" v-model="pod" aria-label="日志 Pod">
            <option v-for="item in pods" :key="item.name">{{ item.name }}</option></select
          ><select data-field="container" v-model="container" aria-label="日志容器">
            <option v-for="item in containers" :key="item">{{ item }}</option></select
          ><label class="inline-check"
            ><input data-field="previous" type="checkbox" v-model="previous" />上一实例</label
          ><button class="button" :disabled="!container || busy || queryDisabled" @click="readLog">
            读取最新 10000 行
          </button>
        </div>
        <p v-if="operator && acl.canOperate" class="muted">
          最多最近 10000 行，超过 4 MiB 时保留末尾。
        </p>
        <LogViewer v-if="log" :text="log" />
        <h4>事件</h4>
        <div v-if="!diagnostic.events.length" class="empty-state">当前没有可见事件</div>
        <table v-else>
          <thead>
            <tr>
              <th>时间</th>
              <th>原因</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(event, index) in diagnostic.events" :key="index">
              <td>{{ time(event.time) }}</td>
              <td>{{ event.reason }}</td>
              <td>{{ event.message }}</td>
            </tr>
          </tbody>
        </table></template
      >
    </div>
    <div class="workbench-body" v-if="tab === 'snapshots'">
      <p class="muted">
        仅记录手动刷新时观测到的完成快照。存在性检查仅验证 _metadata，不保证共享状态完整或状态兼容。
      </p>
      <div v-if="!snapshots.length" class="empty-state">暂无已观测快照，请在作业列表手动刷新。</div>
      <div class="table-wrap">
        <table v-if="snapshots.length">
          <thead>
            <tr>
              <th>类型</th>
              <th>路径</th>
              <th>首次观测</th>
              <th>检查结果</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="point in snapshots" :key="point.id">
              <td>{{ point.type }}</td>
              <td class="mono">
                {{ point.path
                }}<small class="table-description"
                  >观测时发布：{{ point.observedReleaseId || '未知' }}</small
                >
              </td>
              <td>{{ time(point.firstObservedAt) }}</td>
              <td>
                {{
                  point.lastCheck ? (point.lastCheck.exists ? '元数据存在' : '不存在') : '未检查'
                }}
              </td>
              <td>
                <button
                  v-if="operator && acl.canOperate"
                  class="text-button"
                  :disabled="busy || queryDisabled"
                  @click="checkSnapshot(point)"
                >
                  检查路径
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <div class="workbench-body" v-if="tab === 'operations'">
      <p class="muted">
        ACCEPTED 表示 API
        接受。只有手动刷新观测到相同请求标识时，才记录状态；不将其自动认定为操作成功。
      </p>
      <table>
        <thead>
          <tr>
            <th>操作 / 发布版本</th>
            <th>提交结果</th>
            <th>最后观测</th>
            <th>时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="op in operations" :key="op.id">
            <td>
              {{ op.action }}<small class="table-description">{{ op.releaseId }}</small>
            </td>
            <td>
              {{ op.state }}<small class="table-description">{{ op.message }}</small>
            </td>
            <td>{{ op.observedState || '尚未观测' }} · {{ op.observedLifecycle || '—' }}</td>
            <td>{{ time(op.observedAt || op.updatedAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="workbench-body" v-if="tab === 'access'">
      <div class="external-links">
        <a
          v-for="link in acl.externalLinks || []"
          :key="link.url"
          class="button"
          :href="link.url"
          target="_blank"
          rel="noopener noreferrer"
          >{{ link.name }} ↗</a
        >
      </div>
      <p class="muted">
        负责人：{{ acl.owner || '旧作业尚未分配' }} · 业务标签：{{ acl.project || '未设置' }}
      </p>
      <form v-if="acl.canManage && developer" @submit.prevent="saveAccess">
        <div class="field-grid">
          <label>负责人账号<input data-field="owner" v-model="acl.owner" required /></label
          ><label
            >业务标签<input data-field="project" v-model="acl.project" maxlength="100" /></label
          ><label
            >只读用户组<input
              data-field="readGroups"
              v-model="readGroups"
              placeholder="多个组标识用逗号分隔" /></label
          ><label
            >运行维护用户组<input
              data-field="operateGroups"
              v-model="operateGroups"
              placeholder="允许启停、快照和诊断" /></label
          ><label
            >编辑用户组<input
              data-field="editGroups"
              v-model="editGroups"
              placeholder="允许编辑和发布，仍受平台角色限制"
          /></label>
        </div>
        <p class="muted">
          可见用户组：{{
            groups.map((g) => g.id + '（' + g.name + '）').join('、') ||
            '暂无，请在用户与权限中添加'
          }}
        </p>
        <label
          >外部监控 / 日志链接<textarea
            data-field="links"
            v-model="links"
            rows="4"
            placeholder='[{"name":"Grafana","url":"https://monitor.example.com/..."}]'
          />
        </label>
        <div class="form-actions">
          <button class="button primary" :disabled="busy">保存归属与链接</button>
        </div>
      </form>
    </div>
  </div>
</template>
