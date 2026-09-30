<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { request, jsonBody } from '../../../shared/api/http'
import { confirmDialog } from '../../../shared/utils/confirmDialog'
import { currentUser } from '../../permission/stores/auth'
import PageHeader from '../../../shared/components/layout/PageHeader.vue'
type FileEntry = { key: string; size: number; modifiedAt: string }
const downloadUrl = (key: string) => '/api/artifacts/download?' + new URLSearchParams({ key })
const canWrite = computed(() =>
  currentUser.value?.roles.some((r) => ['ROLE_ADMIN', 'ROLE_DEVELOPER'].includes(r)),
)
const bucket = ref(''),
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
const crumbs = computed(() => prefix.value.slice(root.value.length).split('/').filter(Boolean))
async function load(path?: string, append = false) {
  busy.value = true
  error.value = ''
  try {
    const data = await request<{
      bucket: string
      rootPrefix: string
      prefix: string
      directories: string[]
      objects: FileEntry[]
      nextToken?: string
    }>(
      '/api/artifacts/objects?' +
        new URLSearchParams({
          ...(path ? { prefix: path } : {}),
          ...(append && nextToken.value ? { token: nextToken.value } : {}),
        }),
    )
    bucket.value = data.bucket
    root.value = data.rootPrefix
    prefix.value = data.prefix
    dirs.value = append ? [...new Set([...dirs.value, ...data.directories])] : data.directories
    objects.value = append ? [...objects.value, ...data.objects] : data.objects
    nextToken.value = data.nextToken || ''
    selected.value = []
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
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
    message.value = '已上传 ' + count + ' 个文件'
    await load(prefix.value)
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
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function remove() {
  if (
    !(await confirmDialog(
      '删除选中的 ' + selected.value.length + ' 个文件？正在运行或后续启动的作业可能引用这些文件。',
    ))
  )
    return
  busy.value = true
  error.value = ''
  try {
    for (const key of selected.value)
      await request('/api/artifacts/objects?' + new URLSearchParams({ key }), { method: 'DELETE' })
    message.value = '所选文件已删除'
    await load(prefix.value)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
onMounted(() => load())
</script>
<template>
  <PageHeader eyebrow="OBJECT STORAGE"
    ><template #title>对象浏览器</template
    ><template #description
      >浏览 S3 文件与目录，作业依赖从 jobs/作业 ID/ 或 shared/ 目录选择。</template
    ></PageHeader
  >
  <div v-if="error" class="notice error" role="alert">{{ error }}</div>
  <div v-if="message" class="notice">{{ message }}</div>
  <section class="panel object-browser">
    <div class="panel-toolbar">
      <div>
        <h3>▣ {{ bucket || 'S3' }}</h3>
        <small>{{ root }} · {{ objects.length }} 个对象</small>
      </div>
      <div class="toolbar-actions">
        <button class="button" :disabled="busy" @click="load(prefix)">↻ 刷新</button
        ><label v-if="canWrite" class="button primary upload-button"
          >上传<input type="file" multiple :disabled="busy || !prefix" @change="upload"
        /></label>
      </div>
    </div>
    <div class="object-body">
      <main>
        <div class="filter-bar">
          <button class="text-button" :disabled="busy" @click="load(root)">{{ bucket }}</button
          ><template v-for="(part, index) in crumbs" :key="index"
            ><span>/</span
            ><button class="text-button" :disabled="busy" @click="load(breadcrumb(index))">
              {{ part }}
            </button></template
          ><button
            v-if="canWrite"
            class="button"
            :disabled="busy || !prefix"
            @click="folderDialog?.showModal()"
          >
            新建目录
          </button>
        </div>
        <input
          class="object-filter"
          v-model="query"
          placeholder="筛选当前目录中的对象"
          aria-label="筛选对象"
        />
        <table>
          <thead>
            <tr>
              <th>
                <input
                  type="checkbox"
                  aria-label="选择当前页文件"
                  :checked="shown.length > 0 && shown.every((o) => selected.includes(o.key))"
                  @change="
                    selected = ($event.target as HTMLInputElement).checked
                      ? shown.map((o) => o.key)
                      : []
                  "
                />
              </th>
              <th>名称</th>
              <th>最后修改</th>
              <th>大小</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="prefix !== root">
              <td></td>
              <td>
                <button
                  class="text-button"
                  :disabled="busy"
                  @click="
                    load(prefix.slice(0, -1).slice(0, prefix.slice(0, -1).lastIndexOf('/') + 1))
                  "
                >
                  ↑ 上一级
                </button>
              </td>
              <td></td>
              <td></td>
            </tr>
            <tr v-for="dir in shownDirs" :key="dir">
              <td></td>
              <td>
                <button class="text-button" :disabled="busy" @click="load(dir)">
                  📁 {{ dir.slice(prefix.length).replace(/\/$/, '') }}
                </button>
              </td>
              <td>—</td>
              <td>—</td>
            </tr>
            <tr v-for="file in shown" :key="file.key">
              <td>
                <input
                  type="checkbox"
                  v-model="selected"
                  :value="file.key"
                  :aria-label="'选择 ' + file.key"
                />
              </td>
              <td>{{ file.key.slice(prefix.length) }}</td>
              <td>{{ new Date(file.modifiedAt).toLocaleString() }}</td>
              <td>{{ size(file.size) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="!busy && !shown.length && !shownDirs.length" class="empty-state">
          当前目录没有对象
        </p>
        <button v-if="nextToken" class="button" :disabled="busy" @click="load(prefix, true)">
          加载更多
        </button>
      </main>
      <aside>
        <h3>已选择 {{ selected.length }} 个对象</h3>
        <div class="object-actions">
          <a v-if="selected.length === 1" class="button" :href="downloadUrl(selected[0]!)">下载</a
          ><span v-else class="muted">选择单个文件后可下载</span
          ><button
            v-if="canWrite"
            class="button danger"
            :disabled="busy || !selected.length"
            @click="remove"
          >
            删除所选文件
          </button>
        </div>
        <p class="muted">文件直接存入 S3，无需制品登记。同名文件不会覆盖。</p>
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
.object-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 250px;
}
.object-body main {
  min-width: 0;
  padding: 16px;
}
.object-body aside {
  border-left: 1px solid var(--border, #e4e8ee);
  padding: 20px;
}
.object-actions {
  display: grid;
  gap: 14px;
  margin: 20px 0;
}
.object-filter {
  width: 100%;
  margin: 12px 0;
}
th:first-child,
td:first-child {
  width: 34px;
}
td input,
th input {
  width: auto;
}
.filter-bar {
  flex-wrap: wrap;
}
@media (max-width: 900px) {
  .object-body {
    grid-template-columns: 1fr;
  }
  .object-body aside {
    border-left: 0;
    border-top: 1px solid #e4e8ee;
  }
}
</style>
