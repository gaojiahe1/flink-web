<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Folder, FileArchive, File, ChevronRight, ArrowUp } from '@lucide/vue'
import { request } from '../../../shared/api/http'
export type Artifact = {
  id: string
  name: string
  filename?: string
  category?: string
  uri?: string
  size?: number
  sha256?: string
}
const props = defineProps<{
  modelValue: string | string[]
  artifacts?: Artifact[]
  scopeId?: string
  category: 'main' | 'dependency'
  exclude?: string
  disabled?: boolean
  runner?: boolean
  single?: boolean
  fileLabel?: string
}>()
const emit = defineEmits<{
  'update:modelValue': [string | string[]]
  uploaded: [Artifact]
  uploading: [boolean]
}>()
const uploading = ref(false),
  busy = ref(false),
  error = ref(''),
  query = ref(''),
  scope = ref(props.runner ? 'runner' : props.scopeId ? 'job' : 'shared')
const singleSelection = computed(() => props.single || props.category === 'main')
const source = ref('s3'),
  address = ref('')
const root = ref(''),
  prefix = ref(''),
  bucket = ref(''),
  dirs = ref<string[]>([]),
  files = ref<Artifact[]>([]),
  nextToken = ref('')
const visibleDirs = computed(() =>
  dirs.value.filter((d) =>
    d.slice(prefix.value.length).toLowerCase().includes(query.value.toLowerCase()),
  ),
)
const label = computed(() =>
  props.fileLabel
    ? props.fileLabel
    : props.runner
      ? 'SQL Runner 文件'
      : props.category === 'main'
        ? '主 JAR 文件'
        : '附加依赖与配置文件',
)
const base = computed(() =>
  scope.value === 'all'
    ? ''
    : root.value +
      (scope.value === 'runner'
        ? 'shared/sql-runner/'
        : scope.value === 'job'
          ? 'jobs/' + props.scopeId + '/'
          : 'shared/'),
)
const selectedIds = computed(() =>
  Array.isArray(props.modelValue) ? props.modelValue : props.modelValue ? [props.modelValue] : [],
)
const selected = computed(() =>
  selectedIds.value.map(
    (id) =>
      [...files.value, ...(props.artifacts || [])].find((a) => a.id === id) || {
        id,
        name: id.split('/').pop() || id,
      },
  ),
)
const choices = computed(() =>
  files.value.filter(
    (a) =>
      a.id !== props.exclude &&
      (props.category !== 'main' || a.filename?.toLowerCase().endsWith('.jar')) &&
      a.name.toLowerCase().includes(query.value.toLowerCase()),
  ),
)

const breadcrumbs = computed(() => {
  const relative = prefix.value.startsWith(base.value) ? prefix.value.slice(base.value.length) : ''
  const parts = relative.split('/').filter(Boolean)
  return [
    {
      name:
        scope.value === 'runner'
          ? 'SQL Runner'
          : scope.value === 'all'
            ? '全部文件'
            : scope.value === 'job'
              ? '作业文件'
              : '共享文件',
      path: base.value,
    },
    ...parts.map((name, i) => ({ name, path: base.value + parts.slice(0, i + 1).join('/') + '/' })),
  ]
})
function navigate(path: string) {
  query.value = ''
  void load(path)
}
function fileSize(size?: number) {
  if (size === undefined) return ''
  return size >= 1024 * 1024
    ? (size / (1024 * 1024)).toFixed(1) + ' MB'
    : Math.max(1, Math.ceil(size / 1024)) + ' KB'
}

let epoch = 0
async function load(path = base.value, append = false) {
  const serial = ++epoch
  busy.value = true
  error.value = ''
  try {
    if (!bucket.value) {
      const defaults = await request<{ rootPrefix: string; bucket: string }>(
        '/api/artifacts/defaults',
      )
      if (serial !== epoch) return
      root.value = defaults.rootPrefix
      bucket.value = defaults.bucket
      path = base.value
    }
    const data = await request<{
      directories: string[]
      objects: { key: string; size?: number }[]
      nextToken?: string
    }>(
      '/api/artifacts/objects?' +
        new URLSearchParams({
          prefix: path,
          ...(append && nextToken.value ? { token: nextToken.value } : {}),
        }),
    )
    if (serial !== epoch) return
    prefix.value = path
    dirs.value = append ? [...new Set([...dirs.value, ...data.directories])] : data.directories
    const objects = data.objects
      .filter((o) => !o.key.endsWith('/'))
      .map((o) => ({
        id: 's3:' + o.key,
        size: o.size,
        name: o.key.split('/').pop()!,
        filename: o.key.split('/').pop()!,
        uri: 's3://' + bucket.value + '/' + o.key,
      }))
    files.value = append ? [...files.value, ...objects] : objects
    nextToken.value = data.nextToken || ''
  } catch (e) {
    if (serial === epoch) error.value = (e as Error).message
  } finally {
    if (serial === epoch) busy.value = false
  }
}
watch(
  () => [props.scopeId, scope.value],
  () => {
    void load()
  },
  { immediate: true },
)
function choose(id: string) {
  if (singleSelection.value) emit('update:modelValue', id)
  else
    emit(
      'update:modelValue',
      selectedIds.value.includes(id)
        ? selectedIds.value.filter((v) => v !== id)
        : [...selectedIds.value, id],
    )
}
function remove(id: string) {
  emit('update:modelValue', singleSelection.value ? '' : selectedIds.value.filter((v) => v !== id))
}
function confirmAddress() {
  error.value = ''
  try {
    const raw = address.value.trim(),
      uri = new URL(raw)
    if (
      !['http:', 'https:', 's3:'].includes(uri.protocol) ||
      !uri.hostname ||
      uri.username ||
      uri.password ||
      uri.search ||
      uri.hash ||
      raw.includes('..') ||
      raw.includes('\\')
    )
      throw new Error('请填写不含凭据、查询参数和片段的 HTTP/HTTPS/S3 文件地址')
    const filename = uri.pathname.split('/').pop() || ''
    if (
      !/^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$/.test(filename) ||
      (props.category === 'main' && !filename.toLowerCase().endsWith('.jar'))
    )
      throw new Error(
        props.category === 'main'
          ? '地址须指向具体 .jar 文件'
          : '地址最后一层须为文件名，仅支持字母、数字、点、下划线和连字符',
      )
    emit('uploaded', { id: raw, name: filename, filename, uri: raw })
    if (singleSelection.value) emit('update:modelValue', raw)
    else emit('update:modelValue', [...new Set([...selectedIds.value, raw])])
    address.value = ''
  } catch (e) {
    error.value = (e as Error).message
  }
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  uploading.value = true
  emit('uploading', true)
  error.value = ''
  let ids = [...selectedIds.value]
  try {
    for (const file of Array.from(input.files || [])) {
      if (props.category === 'main' && !file.name.toLowerCase().endsWith('.jar'))
        throw new Error('主程序请选择 .jar 文件')
      const body = new FormData()
      body.append('file', file)
      body.append('prefix', prefix.value || base.value)
      const artifact = await request<Artifact>('/api/artifacts/upload', { method: 'POST', body })
      emit('uploaded', artifact)
      ids = [...new Set([...ids, artifact.id])]
      emit('update:modelValue', singleSelection.value ? artifact.id : ids)
    }
    await load(prefix.value)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    uploading.value = false
    emit('uploading', false)
    input.value = ''
  }
}
</script>
<template>
  <div class="artifact-picker">
    <p v-if="runner" class="muted">
      <a href="/api/sql/runner/download" class="text-button" download>下载平台 SQL Runner</a>
      · 上传到下方 SQL Runner 列表后选择。内置版本已验证 Flink 1.20.3 /
      1.20.5，其他版本按部署指南构建。
    </p>
    <div class="field-heading picker-heading">
      <strong>{{ label }}</strong>
      <div class="picker-actions">
        <button
          type="button"
          class="button refresh-files"
          :disabled="busy || uploading || disabled"
          @click="load(prefix || base)"
        >
          <svg
            viewBox="0 0 24 24"
            width="14"
            height="14"
            fill="none"
            stroke="currentColor"
            stroke-width="1.7"
            aria-hidden="true"
          >
            <path d="M20 7v5h-5M4 17v-5h5" />
            <path d="M6.1 7a7 7 0 0 1 11.6-1L20 9M4 15l2.3 3A7 7 0 0 0 18 17" />
          </svg>
          {{ busy ? '刷新中…' : '刷新文件' }}
        </button>
        <label
          class="button upload-button"
          :class="{ 'is-disabled': disabled || uploading || busy || !!error || !root }"
        >
          <svg
            viewBox="0 0 24 24"
            width="14"
            height="14"
            fill="none"
            stroke="currentColor"
            stroke-width="1.7"
            aria-hidden="true"
          >
            <path d="M12 16V3m-5 5 5-5 5 5M4 15v5h16v-5" />
          </svg>
          {{ uploading ? '正在上传…' : '上传文件' }}
          <input
            type="file"
            :aria-label="category === 'main' ? '上传主 JAR' : '上传依赖文件'"
            :accept="category === 'main' ? '.jar' : undefined"
            :multiple="!singleSelection"
            :disabled="disabled || uploading || busy || !!error || !root"
            @change="upload"
          />
        </label>
      </div>
    </div>

    <div class="picker-source" role="group" :aria-label="label + '来源'">
      <button
        type="button"
        class="button"
        :class="{ active: source === 's3' }"
        :disabled="disabled || uploading"
        @click="source = 's3'"
      >
        S3 文件
      </button>
      <button
        type="button"
        class="button"
        :class="{ active: source === 'address' }"
        :disabled="disabled || uploading"
        @click="source = 'address'"
      >
        HTTP / S3 地址
      </button>
    </div>
    <div v-if="source === 'address'" class="picker-browser external-file">
      <label
        >{{ label }}地址<input
          v-model="address"
          :aria-label="label + '地址'"
          :disabled="disabled || uploading"
          placeholder="https://host/path/file.jar 或 s3://bucket/path/file.jar"
          @keydown.enter.prevent="confirmAddress"
      /></label>
      <p class="muted">
        填写具体文件地址后确认；文件名取地址最后一层。HTTP 主机须在平台下载白名单中，S3
        使用平台连接与凭据。
      </p>
      <button
        type="button"
        class="button"
        :disabled="disabled || uploading || !address.trim()"
        @click="confirmAddress"
      >
        确认文件地址
      </button>
    </div>
    <div v-else class="picker-browser" :aria-busy="busy">
      <div class="browser-toolbar">
        <select v-model="scope" aria-label="文件目录" :disabled="disabled || uploading || busy">
          <option v-if="scopeId" value="job">当前作业目录</option>
          <option value="shared">共享目录</option>
          <option v-if="runner" value="runner">SQL Runner 列表</option>
          <option value="all">全部文件</option>
        </select>
        <span class="browser-count">{{ dirs.length }} 个目录 · {{ files.length }} 个文件</span>
      </div>
      <nav class="picker-breadcrumbs" :aria-label="label + '路径'">
        <button
          type="button"
          class="parent-folder"
          :disabled="prefix === base || busy || disabled"
          aria-label="上一级"
          title="返回上一级目录"
          @click="navigate(prefix.slice(0, -1).slice(0, prefix.slice(0, -1).lastIndexOf('/') + 1))"
        >
          <ArrowUp :size="15" />
        </button>
        <template v-for="(crumb, index) in breadcrumbs" :key="crumb.path">
          <ChevronRight v-if="index" :size="13" aria-hidden="true" />
          <button
            type="button"
            :title="crumb.name"
            :disabled="busy || disabled || index === breadcrumbs.length - 1"
            @click="navigate(crumb.path)"
          >
            {{ crumb.name }}
          </button>
        </template>
      </nav>
      <input
        v-model="query"
        :aria-label="'筛选' + label"
        placeholder="搜索当前目录中的文件或文件夹"
      />
      <div class="browser-entries" role="group" :aria-label="label + '目录内容'">
        <button
          v-for="dir in visibleDirs"
          :key="dir"
          type="button"
          class="folder-entry"
          :title="dir.slice(prefix.length).replace(/\/$/, '')"
          :disabled="busy || disabled || uploading"
          @click="navigate(dir)"
        >
          <Folder :size="19" aria-hidden="true" />
          <span>{{ dir.slice(prefix.length).replace(/\/$/, '') }}</span>
          <ChevronRight :size="15" aria-hidden="true" />
        </button>
        <label
          v-for="a in choices"
          :key="a.id"
          class="file-entry"
          :class="{ chosen: selectedIds.includes(a.id) }"
          :title="a.name"
        >
          <input
            :type="singleSelection ? 'radio' : 'checkbox'"
            :name="singleSelection ? 'file-' + scopeId : undefined"
            :checked="selectedIds.includes(a.id)"
            :disabled="disabled || uploading || busy"
            @change="choose(a.id)"
          />
          <component
            :is="a.filename?.endsWith('.jar') ? FileArchive : File"
            :size="18"
            aria-hidden="true"
          />
          <span>{{ a.name }}</span
          ><small>{{ fileSize(a.size) }}</small>
        </label>
        <div v-if="!visibleDirs.length && !choices.length" class="browser-empty">
          <Folder :size="20" aria-hidden="true" />
          <span>{{
            busy ? '正在读取目录…' : query ? '没有匹配的文件或目录' : '当前目录暂无可选文件'
          }}</span>
          <small v-if="!busy && !query"
            >可上传{{
              category === 'main' ? ' JAR 文件' : '依赖或配置文件'
            }}，或切换到共享目录</small
          >
        </div>
      </div>
      <div class="browser-footer">
        <small class="picker-path" :title="'s3://' + bucket + '/' + (prefix || base)"
          >s3://{{ bucket }}/{{ prefix || base }}</small
        >
        <button
          v-if="nextToken"
          type="button"
          class="text-button"
          :disabled="busy"
          @click="load(prefix, true)"
        >
          加载更多
        </button>
      </div>
    </div>
    <div class="picker-selection">
      <small>{{
        selected.length
          ? '已选择 ' + selected.length + ' 个文件'
          : category === 'main'
            ? runner
              ? '请选择 Runner 或确认 HTTP/S3 地址'
              : '当前使用镜像内 JAR 路径'
            : '尚未选择附加文件'
      }}</small>
      <button
        v-if="category === 'main' && selected.length"
        type="button"
        class="text-button"
        :disabled="disabled || uploading"
        @click="emit('update:modelValue', '')"
      >
        {{ runner ? '清除选择' : '改用镜像内 JAR' }}
      </button>
    </div>
    <div class="file-chips">
      <span v-for="a in selected" :key="a.id" class="file-chip"
        >{{ a.filename || a.name
        }}<button
          type="button"
          :disabled="disabled || uploading"
          :aria-label="'移除 ' + a.name"
          @click="remove(a.id)"
        >
          ×
        </button></span
      >
    </div>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
  </div>
</template>
<style scoped>
.picker-heading {
  flex-wrap: wrap;
}
.picker-source {
  display: flex;
  gap: 6px;
  margin-bottom: 8px;
}
.picker-source .button {
  min-height: 28px;
  padding: 4px 10px;
  font-size: 12px;
}
.picker-source .active {
  border-color: var(--blue);
  color: var(--blue);
  background: var(--color-primary-soft, var(--color-primary-soft));
}
.external-file .button {
  justify-self: start;
}
.external-file p {
  margin: 0;
  line-height: 1.6;
}
.picker-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
  align-items: center;
}
.picker-actions .button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  min-height: var(--control-height);
  white-space: nowrap;
}
.picker-actions .is-disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.picker-browser {
  display: grid;
  gap: 10px;
  min-width: 0;
  padding: 12px;
  background: var(--color-surface);
  border: 1px solid var(--line);
  border-radius: 8px;
}
.browser-toolbar,
.picker-breadcrumbs,
.browser-footer,
.picker-selection {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
}
.browser-toolbar select {
  width: 180px;
  margin: 0;
}
.browser-count {
  margin-left: auto;
  color: var(--muted);
  font-size: 12px;
  white-space: nowrap;
}
.picker-breadcrumbs {
  overflow-x: auto;
  padding: 4px 0;
}
.picker-breadcrumbs button {
  border: 0;
  background: transparent;
  padding: 4px;
  color: var(--blue);
  cursor: pointer;
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex-shrink: 0;
}
.picker-breadcrumbs button:disabled {
  color: var(--muted);
  cursor: default;
}
.picker-breadcrumbs .parent-folder {
  display: flex;
  border: 1px solid var(--line);
  border-radius: 4px;
  margin-right: 4px;
}
.picker-browser > input {
  margin: 0;
  background: var(--color-background);
}
.browser-entries {
  max-height: 224px;
  overflow-y: auto;
  overflow-x: hidden;
  min-width: 0;
}
.folder-entry,
.file-entry {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 38px;
  padding: 7px 9px;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: transparent;
  text-align: left;
  cursor: pointer;
  margin: 0;
  font-size: 13px;
  color: var(--text);
  box-sizing: border-box;
}
.folder-entry svg {
  color: var(--color-warning);
  flex-shrink: 0;
}
.folder-entry svg:last-child {
  color: var(--muted);
  margin-left: auto;
}
.file-entry svg {
  color: var(--muted);
  flex-shrink: 0;
}
.file-entry input {
  width: 14px;
  margin: 0;
  flex-shrink: 0;
  accent-color: var(--blue);
}
.folder-entry span,
.file-entry span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.file-entry small {
  margin-left: auto;
  color: var(--muted);
  white-space: nowrap;
}
.folder-entry:hover,
.file-entry:hover,
.file-entry.chosen {
  background: var(--color-primary-soft);
}
.browser-empty {
  display: grid;
  place-items: center;
  gap: 4px;
  padding: 10px 8px;
  color: var(--muted);
  font-size: 13px;
  text-align: center;
}
.browser-empty small {
  font-size: 12px;
}
.browser-footer {
  border-top: 1px solid var(--line);
  padding-top: 8px;
}
.picker-path {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--muted);
  font-size: 11px;
}
.picker-selection {
  justify-content: space-between;
  margin-top: 8px;
  color: var(--muted);
}
@media (max-width: 600px) {
  .browser-toolbar {
    flex-wrap: wrap;
  }
  .browser-count {
    margin-left: 0;
  }
}
</style>
