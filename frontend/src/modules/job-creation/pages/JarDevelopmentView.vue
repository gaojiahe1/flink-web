<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import PageHeader from '../../../shared/components/layout/PageHeader.vue'
import ManualRefreshButton from '../../../shared/components/form/ManualRefreshButton.vue'
import { request } from '../../../shared/api/http'
import { currentUser } from '../../permission/stores/auth'
import type { JobDraft } from '../../../shared/types/api'
import { jobsApi } from '../../job-management/api/jobs'
import { confirmDialog } from '../../../shared/utils/confirmDialog'
const jobs = ref<JobDraft[]>([]),
  error = ref(''),
  message = ref(''),
  deleting = ref(''),
  loading = ref(true),
  query = ref('')
const deployments = ref<Record<string, string>>({}),
  statusError = ref('')
const deploymentLabels: Record<string, string> = {
  DEPLOYED: '已部署',
  UNDEPLOYED: '未部署',
  CONFLICT: '资源冲突',
  UNKNOWN: '状态未知',
}
const editable = computed(() =>
  currentUser.value?.roles.some((r) => ['ROLE_ADMIN', 'ROLE_DEVELOPER'].includes(r)),
)
const filtered = computed(() =>
  jobs.value.filter((j) =>
    (j.name + j.description).toLowerCase().includes(query.value.toLowerCase()),
  ),
)
async function load() {
  try {
    error.value = ''
    jobs.value = await request<JobDraft[]>('/api/development/jar/jobs')
    loading.value = false
    deployments.value = {}
    statusError.value = ''
    try {
      deployments.value = await request<Record<string, string>>(
        '/api/development/jar/deployment-status',
      )
    } catch (e) {
      statusError.value = '部署状态查询失败：' + (e as Error).message
    }
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}
async function remove(job: JobDraft) {
  if (deleting.value) return
  if (
    !(await confirmDialog(
      '删除 JAR 草稿「' +
        job.name +
        '」及其作业管理记录、专属文件和状态目录？共享文件保留。已部署作业须先停止并确认集群资源已删除。',
      '删除 JAR 草稿',
    ))
  )
    return
  if (deleting.value) return
  deleting.value = job.id
  error.value = ''
  message.value = ''
  try {
    await jobsApi.remove(job)
    jobs.value = jobs.value.filter((item) => item.id !== job.id)
    message.value = 'JAR 草稿已删除'
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    deleting.value = ''
  }
}
onMounted(load)
</script>
<template>
  <PageHeader
    title="JAR 开发"
    description="管理 JAR 开发草稿、依赖和部署配置。已有作业首次点击编辑后会加入此列表。"
  >
    <template #actions>
      <RouterLink v-if="editable" class="button primary" to="/jar/new">＋ 新建 JAR 作业</RouterLink>
    </template>
  </PageHeader>
  <p v-if="error" class="notice error">{{ error }}</p>
  <p v-if="message" class="notice" role="status">{{ message }}</p>
  <p v-if="statusError" class="notice error" role="alert">{{ statusError }}</p>
  <section class="panel">
    <div class="filter-bar">
      <input
        v-model="query"
        placeholder="搜索 JAR 草稿"
        aria-label="搜索 JAR 草稿"
      /><ManualRefreshButton :action="load" :disabled="!!deleting" />
    </div>
    <p v-if="loading" class="empty-state">正在加载…</p>
    <div v-else-if="filtered.length" class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>作业名称</th>
            <th>Flink 版本</th>
            <th>部署状态</th>
            <th>修订</th>
            <th>最近修改</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="job in filtered" :key="job.id">
            <td>
              <strong>{{ job.name }}</strong
              ><small class="table-description">{{ job.description || '—' }}</small>
            </td>
            <td>
              <span class="version-pill">{{ job.flinkVersion }}</span>
            </td>
            <td>
              <span
                class="deployment-badge"
                :class="{ deployed: deployments[job.id] === 'DEPLOYED' }"
                >{{ deploymentLabels[deployments[job.id] || 'UNKNOWN'] || '状态未知' }}</span
              >
            </td>
            <td>{{ job.revision }}</td>
            <td>{{ new Date(job.updatedAt).toLocaleString('zh-CN') }}</td>
            <td>
              <div class="row-actions">
                <RouterLink class="text-button" :to="'/jar/' + job.id + '/edit'">{{
                  editable ? '编辑' : '查看'
                }}</RouterLink
                ><button
                  v-if="editable"
                  class="text-button danger"
                  :disabled="!!deleting"
                  @click="remove(job)"
                >
                  {{ deleting === job.id ? '删除中…' : '删除' }}
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-else class="empty-state">
      暂无 JAR 开发草稿。新建作业，或在作业管理中点击已有作业的编辑入口。
    </p>
  </section>
</template>
<style scoped>
.deployment-badge {
  display: inline-flex;
  padding: 3px 8px;
  border-radius: 4px;
  background: var(--color-background);
  color: var(--muted);
  font-size: 12px;
  white-space: nowrap;
}
.deployment-badge.deployed {
  background: var(--color-success-soft, #eaf7ee);
  color: var(--color-success, #27834b);
}
</style>
