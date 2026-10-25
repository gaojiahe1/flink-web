<script setup lang="ts">
import DraftListHeader from '../../../shared/components/layout/DraftListHeader.vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDevelopmentStatus } from '../../../shared/composables/useDevelopmentStatus'
import { Box, Trash2 } from '@lucide/vue'
import PageHeader from '../../../shared/components/layout/PageHeader.vue'
import ManualRefreshButton from '../../../shared/components/form/ManualRefreshButton.vue'
import { request } from '../../../shared/api/http'
import { currentUser } from '../../permission/stores/auth'
import type { JobDraft } from '../../../shared/types/api'
import { jobsApi } from '../../job-management/api/jobs'
import { confirmDialog } from '../../../shared/utils/confirmDialog'
const route = useRoute(),
  router = useRouter()
const jobs = ref<JobDraft[]>([]),
  error = ref(''),
  message = ref(''),
  deleting = ref(''),
  loading = ref(true),
  query = ref(''),
  collapsed = ref(false)
const editor = ref<{ canLeave: () => Promise<boolean>; allowNavigation: () => void }>()
const { loadStatus, statusError, status } = useDevelopmentStatus()
let requestVersion = 0,
  disposed = false
const editable = computed(() =>
  currentUser.value?.roles.some((r) => ['ROLE_ADMIN', 'ROLE_DEVELOPER'].includes(r)),
)
const filtered = computed(() =>
  jobs.value.filter((j) =>
    (j.name + j.description).toLowerCase().includes(query.value.toLowerCase()),
  ),
)
async function openFirst() {
  if (route.path === '/jar' && jobs.value[0])
    await router.replace('/jar/' + jobs.value[0].id + '/edit')
}
async function load() {
  const version = ++requestVersion
  const current = () => !disposed && version === requestVersion
  try {
    error.value = ''
    const records = await request<JobDraft[]>('/api/development/jar/jobs')
    if (!current()) return
    jobs.value = records
    loading.value = false
    await openFirst()
    await loadStatus()
  } catch (cause) {
    if (current()) error.value = (cause as Error).message
  } finally {
    if (current()) loading.value = false
  }
}
function opened(id: string) {
  if (!jobs.value.some((j) => j.id === id)) void load()
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
  const selected = route.params.id === job.id
  if (selected && editor.value && !(await editor.value.canLeave())) return
  deleting.value = job.id
  error.value = ''
  message.value = ''
  try {
    await jobsApi.remove(job)
    jobs.value = jobs.value.filter((j) => j.id !== job.id)
    message.value = 'JAR 草稿已删除'
    if (selected) {
      editor.value?.allowNavigation()
      await router.replace(jobs.value[0] ? '/jar/' + jobs.value[0].id + '/edit' : '/jar')
    }
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    deleting.value = ''
  }
}
watch(
  () => route.path,
  (path) => {
    if (path === '/jar' && !loading.value) void openFirst()
  },
)
onMounted(load)
onBeforeUnmount(() => {
  disposed = true
  requestVersion++
})
</script>
<template>
  <PageHeader
    title="JAR 开发"
    description="左侧选择草稿，右侧编辑主程序与部署配置；保存草稿不会自动部署。"
  >
    <template #actions><RouterLink class="button" to="/jobs">前往作业运维 ↗</RouterLink></template>
  </PageHeader>
  <p v-if="error" class="notice error" role="alert">{{ error }}</p>
  <p v-if="message" class="notice" role="status">{{ message }}</p>
  <section class="jar-workbench" :class="{ 'list-collapsed': collapsed }">
    <aside class="jar-explorer" aria-label="JAR 草稿列表">
      <DraftListHeader
        kind="JAR"
        :count="jobs.length"
        :collapsed="collapsed"
        @toggle="collapsed = !collapsed"
      />
      <template v-if="!collapsed">
        <RouterLink v-if="editable" class="button jar-create" to="/jar/new"
          >＋ 新建 JAR 作业</RouterLink
        >
        <div class="jar-list-tools">
          <input
            v-model="query"
            placeholder="搜索 JAR 草稿"
            aria-label="搜索 JAR 草稿"
          /><ManualRefreshButton :action="load" :disabled="!!deleting || loading" />
        </div>
        <p v-if="loading" class="jar-list-note">正在加载…</p>
        <p v-else-if="!filtered.length" class="jar-list-note">
          {{ query ? '没有匹配的草稿' : '暂无 JAR 草稿，可新建或从作业管理进入编辑。' }}
        </p>
        <div class="jar-draft-list">
          <div
            v-for="job in filtered"
            :key="job.id"
            class="jar-draft-row"
            :class="{ selected: route.params.id === job.id }"
          >
            <button
              class="jar-draft-open"
              :disabled="!!deleting"
              :aria-current="route.params.id === job.id ? 'page' : undefined"
              @click="router.push('/jar/' + job.id + '/edit')"
            >
              <Box :size="15" /><span
                ><strong :title="job.name">{{ job.name }}</strong
                ><small
                  >{{ status(job.id, job.deploymentName) }} · {{ job.flinkVersion }}</small
                ></span
              >
            </button>
            <button
              v-if="editable"
              class="text-button jar-draft-delete"
              :disabled="!!deleting"
              :aria-label="'删除 JAR 草稿「' + job.name + '」'"
              @click="remove(job)"
            >
              <Trash2 :size="13" />
            </button>
          </div>
        </div>
        <p v-if="statusError" class="jar-list-note field-error" role="alert">{{ statusError }}</p>
      </template>
    </aside>
    <div class="jar-edit-pane">
      <RouterView v-slot="{ Component }"
        ><component
          :is="Component"
          v-if="Component"
          :key="route.path"
          ref="editor"
          embedded
          @opened="opened"
      /></RouterView>
      <div v-if="route.path === '/jar'" class="jar-editor-empty">
        <Box :size="28" />
        <h3>{{ loading ? '正在加载草稿…' : '选择或新建一份 JAR 草稿' }}</h3>
        <p>在左侧列表选择作业，即可在这里继续编辑。</p>
        <RouterLink v-if="editable && collapsed" class="button primary" to="/jar/new"
          >新建 JAR 作业</RouterLink
        >
      </div>
    </div>
  </section>
</template>
<style scoped>
.jar-workbench {
  display: grid;
  grid-template-columns: 230px minmax(0, 1fr);
  height: calc(100dvh - 205px);
  min-height: 520px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--panel-radius);
  overflow: hidden;
}
.jar-workbench.list-collapsed {
  grid-template-columns: 42px minmax(0, 1fr);
}
.jar-explorer {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 10px;
  background: #fafbfd;
  border-right: 1px solid var(--line);
}
.jar-explorer-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  font-size: 12px;
  min-height: 24px;
}
.jar-explorer-heading strong {
  display: flex;
  align-items: center;
  gap: 7px;
}
.jar-explorer-heading .badge {
  font-size: 10px;
  padding: 1px 5px;
}
.jar-create {
  font-size: 12px;
  justify-content: center;
  flex-shrink: 0;
}
.jar-list-tools {
  display: flex;
  gap: 4px;
  align-items: center;
}
.jar-list-tools input {
  min-width: 0;
  width: 0;
  flex: 1;
  font-size: 12px;
  padding: 7px 8px;
}
.jar-draft-list {
  min-height: 0;
  overflow: auto;
  flex: 1;
  scrollbar-width: thin;
}
.jar-list-note {
  font-size: 12px;
  line-height: 1.6;
  color: var(--muted);
  margin: 0;
}
.jar-draft-row {
  display: flex;
  align-items: center;
  gap: 3px;
  border-radius: 5px;
  margin-bottom: 4px;
}
.jar-draft-row.selected {
  background: #eaf1ff;
}
.jar-draft-open {
  height: var(--table-row-height);
  min-width: 0;
  flex: 1;
  display: flex;
  align-items: flex-start;
  gap: 7px;
  text-align: left;
  border: 0;
  background: transparent;
  padding: 10px 7px;
  color: var(--text);
}
.jar-draft-open > svg {
  flex-shrink: 0;
  margin-top: 1px;
  color: var(--blue);
}
.jar-draft-open > span {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.jar-draft-open strong {
  font-size: 12px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.jar-draft-open small {
  font-size: 11px;
  color: var(--muted);
}
.jar-draft-delete {
  padding: 6px;
  color: var(--muted);
}
.jar-draft-delete:hover {
  color: var(--color-danger);
}
.jar-edit-pane {
  container: jar-editor / inline-size;
  min-width: 0;
  min-height: 0;
  overflow: auto;
  padding: 16px;
  scrollbar-width: thin;
}
.jar-editor-empty {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--muted);
  font-size: 12px;
}
.jar-editor-empty h3,
.jar-editor-empty p {
  margin: 0;
}
.jar-edit-pane :deep(.embedded-editor-title) {
  margin-bottom: 16px;
  gap: 12px;
  align-items: center;
}
.jar-edit-pane :deep(.embedded-editor-title h1) {
  font-size: 17px;
}
.jar-edit-pane :deep(.embedded-editor-title .muted) {
  font-size: 12px;
  margin-top: 4px;
}
.jar-edit-pane :deep(.editor-actionbar) {
  bottom: 0;
  box-shadow: 0 -3px 12px #14243e08;
}
@container jar-editor (max-width:900px) {
  .jar-edit-pane :deep(.jar-resizer) {
    display: none;
  }
  .jar-edit-pane :deep(.job-editor-form.jar-split) {
    grid-template-columns: minmax(0, 1fr);
  }
  .jar-edit-pane :deep(.jar-split .editor-form-column) {
    height: auto;
    min-height: 0;
    overflow: visible;
    padding-right: 0;
  }
  .jar-edit-pane :deep(.jar-split > .yaml-panel) {
    height: 450px;
    min-height: 350px;
  }
  .jar-edit-pane :deep(.field-grid) {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@container jar-editor (max-width:600px) {
  .jar-edit-pane :deep(.field-grid),
  .jar-edit-pane :deep(.resource-pair) {
    grid-template-columns: minmax(0, 1fr);
  }
  .jar-edit-pane :deep(.page-actions) {
    width: 100%;
  }
  .jar-edit-pane :deep(.editor-actionbar) {
    flex-wrap: wrap;
  }
  .jar-edit-pane :deep(.editor-actionbar > .muted) {
    display: none;
  }
}
@media (max-width: 1100px) {
  .jar-workbench {
    grid-template-columns: 200px minmax(0, 1fr);
  }
}
@media (max-width: 760px) {
  .jar-workbench {
    height: auto;
    min-height: 0;
    grid-template-columns: minmax(0, 1fr);
  }
  .jar-workbench.list-collapsed {
    grid-template-columns: minmax(0, 1fr);
  }
  .jar-explorer {
    max-height: 240px;
    border-right: 0;
    border-bottom: 1px solid var(--line);
  }
  .list-collapsed .jar-explorer {
    padding: 6px 10px;
  }
  .jar-edit-pane {
    padding: 12px;
    overflow: visible;
  }
  .jar-edit-pane :deep(.embedded-editor-title) {
    flex-wrap: wrap;
  }
  .jar-editor-empty {
    min-height: 240px;
  }
}
</style>
