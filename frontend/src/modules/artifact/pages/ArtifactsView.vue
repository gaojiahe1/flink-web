<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { request, jsonBody } from '../../../shared/api/http'
import { confirmDialog } from '../../../shared/utils/confirmDialog'
import { currentUser } from '../../permission/stores/auth'
import {
  Database,
  Folder,
  File,
  RefreshCw,
  Upload,
  Download,
  Trash2,
  FolderPlus,
  ChevronRight,
  Search,
  ArrowUp,
  Info,
} from '@lucide/vue'
import PageHeader from '../../../shared/components/layout/PageHeader.vue'
type FileEntry = { key: string; size: number; modifiedAt: string }
const downloadUrl = (key: string) => '/api/artifacts/download?' + new URLSearchParams({ key })
const canWrite = computed(() =>
  currentUser.value?.roles.some((r) => ['ROLE_ADMIN', 'ROLE_DEVELOPER'].includes(r)),
)
const bucket = ref(''),
  managedPrefix = ref(''),
  root = ref(''),
  prefix = ref(''),
  query = ref(''),
  error = ref(''),
  message = ref(''),
  busy = ref(false),
  nextToken = ref('')
const dirs = ref<string[]>([]),
  objects = ref<FileEntry[]>([]),
  selected = ref<string[]>([])
const folderDialog = ref<HTMLDialogElement>(),
  folder = ref('')
const shown = computed(() =>
  objects.value.filter(
    (o) =>
      !o.key.endsWith('/') &&
      o.key.slice(prefix.value.length).toLowerCase().includes(query.value.toLowerCase()),
  ),
)
const shownDirs = computed(() =>
  dirs.value.filter((d) =>
    d.slice(prefix.value.length).toLowerCase().includes(query.value.toLowerCase()),
  ),
)
const chosen = computed(() => objects.value.find((o) => o.key === selected.value[0]))
const chosenDirectory = computed(() =>
  selected.value.length === 1 && selected.value[0]?.endsWith('/') ? selected.value[0] : '',
)
const shownKeys = computed(() => [...shownDirs.value, ...shown.value.map((o) => o.key)])
watch(query, () => {
  selected.value = []
})
const loadFailed = ref(false)
let loadEpoch = 0
const crumbs = computed(() => prefix.value.slice(root.value.length).split('/').filter(Boolean))
async function load(path?: string, append = false) {
  const epoch = ++loadEpoch
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const data = await request<{
      bucket: string
      rootPrefix: string
      managedPrefix?: string
      prefix: string
      directories: string[]
      objects: FileEntry[]
      nextToken?: string
    }>(
      '/api/artifacts/objects?' +
        new URLSearchParams({
          prefix: path ?? '',
          ...(append && nextToken.value ? { token: nextToken.value } : {}),
        }),
    )
    if (epoch !== loadEpoch) return
    loadFailed.value = false
    bucket.value = data.bucket
    root.value = data.rootPrefix
    managedPrefix.value = data.managedPrefix ?? data.rootPrefix
    prefix.value = data.prefix
    dirs.value = append ? [...new Set([...dirs.value, ...data.directories])] : data.directories
    objects.value = append ? [...objects.value, ...data.objects] : data.objects
    nextToken.value = data.nextToken || ''
    selected.value = []
  } catch (e) {
    if (epoch === loadEpoch) {
      loadFailed.value = true
      error.value = (e as Error).message
    }
  } finally {
    if (epoch === loadEpoch) busy.value = false
  }
}
function breadcrumb(index: number) {
  return root.value + crumbs.value.slice(0, index + 1).join('/') + '/'
}
function size(bytes: number) {
  return bytes < 1024
    ? bytes + ' B'
    : bytes < 1048576
      ? (bytes / 1024).toFixed(1) + ' KiB'
      : (bytes / 1048576).toFixed(1) + ' MiB'
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    let count = 0
    for (const file of Array.from(input.files || [])) {
      const body = new FormData()
      body.append('file', file)
      body.append('prefix', prefix.value)
      await request('/api/artifacts/upload', { method: 'POST', body })
      count++
    }
    await load(prefix.value)
    message.value = '已上传 ' + count + ' 个文件'
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
    input.value = ''
  }
}
async function mkdir() {
  if (!folder.value.trim() || !/^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$/.test(folder.value)) return
  busy.value = true
  error.value = ''
  try {
    await request('/api/artifacts/directories', {
      method: 'POST',
      ...jsonBody({ prefix: prefix.value + folder.value + '/' }),
    })
    folderDialog.value?.close()
    folder.value = ''
    await load(prefix.value)
    message.value = '目录已创建'
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function remove() {
  if (busy.value || !selected.value.length) return
  const keys = [...selected.value]
  const directoryCount = keys.filter((key) => key.endsWith('/')).length
  if (
    !(await confirmDialog(
      '删除选中的 ' +
        keys.length +
        ' 项？' +
        (directoryCount
          ? '其中 ' + directoryCount + ' 个目录及其全部子目录和文件将一起删除，无法撤销。'
          : '') +
        '正在运行或后续启动的作业可能引用这些文件。',
      directoryCount ? '删除目录与文件' : '删除文件',
    ))
  )
    return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    for (const key of keys)
      await request(
        key.endsWith('/')
          ? '/api/artifacts/directories?' + new URLSearchParams({ prefix: key })
          : '/api/artifacts/objects?' + new URLSearchParams({ key }),
        { method: 'DELETE' },
      )
    await load(prefix.value)
    message.value = '所选目录与文件已删除'
  } catch (e) {
    const failure = (e as Error).message
    await load(prefix.value)
    error.value = failure + '；可能已有部分内容删除，请刷新结果后重试。'
  } finally {
    busy.value = false
  }
}
onMounted(() => load())
watch(folder, () => {
  if (folderDialog.value?.open) error.value = ''
})
function openFolderDialog() {
  folder.value = ''
  error.value = ''
  folderDialog.value?.showModal()
}
</script>
<template>
  <PageHeader eyebrow="OBJECT STORAGE"
    ><template #title>文件与制品</template
    ><template #description
      >浏览 S3 文件与目录，作业依赖从 jobs/作业 ID/ 或 shared/ 目录选择。</template
    ></PageHeader
  >
  <div v-if="error" class="notice error" role="alert">{{ error }}</div>
  <div v-if="message" class="notice">{{ message }}</div>
  <section class="panel object-browser">
    <div class="storage-heading">
      <div class="bucket-symbol"><Database :size="23" /></div>
      <div class="bucket-title">
        <h2>{{ bucket || '对象存储' }}</h2>
        <small>{{
          loadFailed ? '存储连接不可用' : bucket ? '桶根目录' : '正在读取存储目录'
        }}</small>
      </div>
      <span class="storage-count"
        >{{ dirs.length }} 个目录 ·
        {{ objects.filter((o) => !o.key.endsWith('/')).length }} 个文件</span
      >
      <div class="toolbar-actions">
        <button class="button" :disabled="busy" @click="load(prefix)">
          <RefreshCw :size="14" />刷新
        </button>
        <button
          v-if="canWrite"
          class="button"
          :disabled="busy || !bucket || loadFailed"
          @click="openFolderDialog"
        >
          <FolderPlus :size="14" />新建目录
        </button>
        <label
          v-if="canWrite"
          class="button primary upload-button"
          :class="{ disabled: busy || !bucket || loadFailed }"
          :aria-disabled="busy || !bucket || loadFailed"
          ><Upload :size="14" />上传文件<input
            aria-label="上传文件"
            type="file"
            multiple
            :disabled="busy || !bucket || loadFailed"
            @change="upload"
        /></label>
      </div>
    </div>
    <nav class="storage-locations" aria-label="存储位置">
      <button :class="{ active: prefix === root }" :disabled="busy" @click="load(root)">
        <Database :size="14" />全部文件
      </button>
      <button
        :class="{ active: prefix.startsWith(managedPrefix + 'jobs/') }"
        :disabled="busy || !bucket"
        @click="load(managedPrefix + 'jobs/')"
      >
        <Folder :size="14" />作业目录
      </button>
      <button
        :class="{ active: prefix.startsWith(managedPrefix + 'shared/') }"
        :disabled="busy || !bucket"
        @click="load(managedPrefix + 'shared/')"
      >
        <Folder :size="14" />共享目录
      </button>
    </nav>
    <div class="object-body">
      <main>
        <div class="object-tools">
          <button
            class="button parent-folder"
            aria-label="上一级目录"
            :disabled="busy || prefix === root"
            @click="load(prefix.slice(0, -1).slice(0, prefix.slice(0, -1).lastIndexOf('/') + 1))"
          >
            <ArrowUp :size="15" />
          </button>
          <nav class="object-breadcrumb" aria-label="目录路径">
            <button :disabled="busy" @click="load(root)">{{ bucket || 'S3' }}</button>
            <template v-for="(part, index) in crumbs" :key="index"
              ><ChevronRight :size="13" /><button
                :title="part"
                :disabled="busy"
                @click="load(breadcrumb(index))"
              >
                {{ part }}
              </button></template
            >
          </nav>
        </div>
        <div class="object-search">
          <Search :size="16" /><input
            v-model="query"
            placeholder="按名称筛选当前目录"
            aria-label="筛选对象"
          /><span>{{ shownDirs.length + shown.length }} 项</span>
        </div>
        <div class="object-table" :aria-busy="busy">
          <table>
            <thead>
              <tr>
                <th>
                  <input
                    type="checkbox"
                    aria-label="选择当前页目录与文件"
                    :disabled="busy || !shownKeys.length"
                    :checked="
                      shownKeys.length > 0 && shownKeys.every((key) => selected.includes(key))
                    "
                    @change="
                      selected = ($event.target as HTMLInputElement).checked ? [...shownKeys] : []
                    "
                  />
                </th>
                <th>名称</th>
                <th>最后修改</th>
                <th>大小</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="dir in shownDirs"
                :key="dir"
                class="folder-row"
                :class="{ selected: selected.includes(dir) }"
              >
                <td>
                  <input
                    v-model="selected"
                    type="checkbox"
                    :value="dir"
                    :aria-label="'选择目录 ' + dir"
                    :disabled="busy"
                  />
                </td>
                <td>
                  <button class="object-name" :disabled="busy" :title="dir" @click="load(dir)">
                    <Folder :size="18" /><span>{{
                      dir.slice(prefix.length).replace(/\/$/, '')
                    }}</span>
                  </button>
                </td>
                <td class="muted">文件夹</td>
                <td>—</td>
              </tr>
              <tr
                v-for="file in shown"
                :key="file.key"
                :class="{ selected: selected.includes(file.key) }"
              >
                <td>
                  <input
                    v-model="selected"
                    type="checkbox"
                    :value="file.key"
                    :aria-label="'选择 ' + file.key"
                    :disabled="busy"
                  />
                </td>
                <td>
                  <span class="object-name" :title="file.key"
                    ><File :size="17" /><span>{{ file.key.slice(prefix.length) }}</span></span
                  >
                </td>
                <td>{{ new Date(file.modifiedAt).toLocaleString() }}</td>
                <td>{{ size(file.size) }}</td>
              </tr>
            </tbody>
          </table>
          <div v-if="!shown.length && !shownDirs.length" class="empty-state">
            <Folder :size="36" />
            <h3>
              {{
                busy
                  ? '正在加载文件…'
                  : loadFailed
                    ? '无法读取当前目录'
                    : query
                      ? '没有匹配的文件'
                      : '当前目录为空'
              }}
            </h3>
            <p>
              {{
                loadFailed
                  ? '检查存储配置和连接后，点击刷新重试。'
                  : query
                    ? '更换关键词再试。'
                    : '上传文件或创建子目录后，会显示在这里。'
              }}
            </p>
          </div>
        </div>
        <div class="object-footer">
          <span>{{
            selected.length ? '已选择 ' + selected.length + ' 项' : '选择目录或文件查看详情和操作'
          }}</span
          ><button v-if="nextToken" class="button" :disabled="busy" @click="load(prefix, true)">
            加载更多
          </button>
        </div>
      </main>
      <aside>
        <h3>
          <Info :size="16" />{{
            selected.length ? '已选择 ' + selected.length + ' 项' : '文件信息'
          }}
        </h3>
        <template v-if="chosenDirectory">
          <Folder class="detail-file-icon" :size="36" /><strong class="selected-name">{{
            chosenDirectory.slice(0, -1).split('/').pop()
          }}</strong>
          <dl>
            <dt>类型</dt>
            <dd>目录</dd>
            <dt>对象路径</dt>
            <dd class="object-key">{{ chosenDirectory }}</dd>
          </dl>
          <p class="inspector-placeholder">删除此目录会同时删除全部子目录和文件。</p>
        </template>
        <template v-else-if="selected.length === 1 && chosen"
          ><File class="detail-file-icon" :size="36" /><strong class="selected-name">{{
            chosen.key.split('/').pop()
          }}</strong>
          <dl>
            <dt>大小</dt>
            <dd>{{ size(chosen.size) }}</dd>
            <dt>最后修改</dt>
            <dd>{{ new Date(chosen.modifiedAt).toLocaleString() }}</dd>
            <dt>对象路径</dt>
            <dd class="object-key">{{ chosen.key }}</dd>
          </dl></template
        >
        <p v-else class="inspector-placeholder">
          {{
            selected.length
              ? '可批量删除选中的目录与文件，下载请只选一个文件。'
              : '选中目录或文件，查看完整路径和可用操作。'
          }}
        </p>
        <div class="object-actions">
          <a v-if="selected.length === 1 && chosen" class="button" :href="downloadUrl(selected[0]!)"
            ><Download :size="14" />下载</a
          ><button
            v-if="canWrite"
            class="button danger"
            :disabled="busy || !selected.length"
            @click="remove"
          >
            <Trash2 :size="14" />删除所选{{
              selected.some((key) => key.endsWith('/')) ? '目录与文件' : '文件'
            }}
          </button>
        </div>
        <small>同名文件不会覆盖。目录删除包含全部子目录与文件，请先确认没有作业依赖。</small>
      </aside>
    </div>
  </section>
  <dialog ref="folderDialog" class="operation-dialog">
    <form @submit.prevent="mkdir">
      <div class="panel-toolbar"><h3>新建目录</h3></div>
      <div class="dialog-body">
        <p v-if="error" class="notice error">{{ error }}</p>
        <label
          >目录名称<input
            v-model="folder"
            required
            pattern="[a-zA-Z0-9][a-zA-Z0-9_.\-]{0,127}"
            maxlength="128" /></label
        ><small>{{ prefix }}{{ folder }}/</small>
      </div>
      <div class="dialog-footer">
        <button type="button" class="button" @click="folderDialog?.close()">取消</button
        ><button class="button primary" :disabled="busy">创建目录</button>
      </div>
    </form>
  </dialog>
</template>
<style scoped>
.storage-heading {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  flex-wrap: wrap;
}
.bucket-symbol {
  width: 32px;
  height: 32px;
  border-radius: 6px;
  display: grid;
  place-items: center;
  background: var(--color-primary-soft);
  color: var(--primary);
}
.bucket-title h2 {
  font-size: 14px;
  margin: 0 0 3px;
}
.bucket-title small,
.storage-count {
  color: var(--muted);
  font-size: 12px;
}
.storage-heading .toolbar-actions {
  margin-left: auto;
}
.object-browser .button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.storage-locations {
  display: flex;
  gap: 20px;
  border-bottom: 1px solid var(--line);
  padding: 0 16px;
}
.storage-locations button {
  display: flex;
  align-items: center;
  gap: 7px;
  border: 0;
  background: transparent;
  color: var(--muted);
  padding: 8px 3px;
  cursor: pointer;
  border-bottom: 2px solid transparent;
}
.storage-locations button.active {
  color: var(--primary);
  border-color: var(--primary);
  font-weight: 600;
}
.object-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 265px;
}
.object-body main {
  min-width: 0;
  padding: 14px 16px;
}
.object-tools {
  display: flex;
  align-items: center;
  gap: 12px;
}
.object-breadcrumb {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  overflow: auto;
  white-space: nowrap;
}
.object-breadcrumb button {
  background: none;
  border: 0;
  padding: 5px;
  color: var(--muted);
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: pointer;
  flex-shrink: 0;
}
.object-breadcrumb button:last-child {
  color: var(--text);
  font-weight: 600;
}
.object-search {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 16px 0;
  color: var(--muted);
}
.object-search input {
  margin: 0;
  max-width: 360px;
}
.object-search > span {
  margin-left: auto;
  font-size: 12px;
}
.object-table {
  max-height: 55vh;
  min-height: 280px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 7px;
}
.object-table table {
  table-layout: fixed;
  width: 100%;
}
.object-table th {
  position: sticky;
  top: 0;
  background: var(--color-surface-soft);
  z-index: 1;
}
.object-table th:first-child,
.object-table td:first-child {
  width: 36px;
  padding: 12px 8px;
}
.object-table th:nth-child(3) {
  width: 170px;
}
.object-table th:last-child {
  width: 90px;
}
.object-table input {
  width: auto;
}
.object-table tr.selected {
  background: var(--color-primary-soft);
}
.object-table td {
  padding: 8px 10px;
  font-size: 12px;
}
.object-name {
  display: flex;
  align-items: center;
  gap: 9px;
  border: 0;
  background: transparent;
  min-width: 0;
  color: var(--text);
  width: 100%;
  text-align: left;
  padding: 0;
}
button.object-name {
  cursor: pointer;
}
.object-name svg {
  flex: none;
  color: var(--color-muted);
}
.folder-row .object-name svg {
  color: var(--color-warning);
  fill: var(--color-warning-soft);
}
.object-name span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.object-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 14px;
  color: var(--muted);
  font-size: 12px;
}
.object-body aside {
  padding: 24px 20px;
  border-left: 1px solid var(--line);
  background: var(--color-surface-soft);
  min-width: 0;
}
aside h3 {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 0 0 24px;
}
.detail-file-icon {
  display: block;
  margin: 20px auto 12px;
  color: var(--color-muted);
}
.selected-name {
  display: block;
  text-align: center;
  overflow-wrap: anywhere;
  margin-bottom: 24px;
}
dl {
  font-size: 12px;
}
dt {
  color: var(--muted);
  margin-top: 14px;
}
dd {
  margin: 5px 0;
  overflow-wrap: anywhere;
}
.object-key {
  font-family: Consolas, monospace;
}
.object-actions {
  display: grid;
  gap: 10px;
  margin: 24px 0;
}
.inspector-placeholder {
  line-height: 1.8;
  color: var(--muted);
  font-size: 12px;
}
aside > small {
  line-height: 1.7;
  color: var(--muted);
}
@media (max-width: 1000px) {
  .object-body {
    grid-template-columns: minmax(0, 1fr);
  }
  .object-body aside {
    border-left: 0;
    border-top: 1px solid var(--line);
  }
  .storage-count {
    display: none;
  }
}
@media (max-width: 600px) {
  .storage-heading,
  .object-body main {
    padding: 14px;
  }
  .object-table th:nth-child(3),
  .object-table td:nth-child(3) {
    display: none;
  }
}
</style>
