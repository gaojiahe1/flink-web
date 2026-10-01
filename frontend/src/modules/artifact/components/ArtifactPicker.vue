<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { request } from '../../../shared/api/http'
export type Artifact = {
  id: string
  name: string
  filename?: string
  category?: string
  uri?: string
  sha256?: string
}
const props = defineProps<{
  modelValue: string | string[]
  artifacts?: Artifact[]
  scopeId?: string
  category: 'main' | 'dependency'
  exclude?: string
  disabled?: boolean
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
  scope = ref('job')
const root = ref(''),
  prefix = ref(''),
  bucket = ref(''),
  dirs = ref<string[]>([]),
  files = ref<Artifact[]>([]),
  nextToken = ref('')
const folderQuery = ref('')
const visibleDirs = computed(() =>
  dirs.value.filter((d) =>
    d.slice(prefix.value.length).toLowerCase().includes(folderQuery.value.toLowerCase()),
  ),
)
const label = computed(() => (props.category === 'main' ? '主 JAR 文件' : '附加依赖与配置文件'))
const base = computed(
  () => root.value + (scope.value === 'job' ? 'jobs/' + props.scopeId + '/' : 'shared/'),
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
let epoch = 0
async function load(path = base.value, append = false) {
  const serial = ++epoch
  busy.value = true
  error.value = ''
  try {
    if (!root.value) {
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
      objects: { key: string }[]
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
    if (props.scopeId || scope.value === 'shared') void load()
  },
  { immediate: true },
)
function choose(id: string) {
  if (props.category === 'main') emit('update:modelValue', id)
  else
    emit(
      'update:modelValue',
      selectedIds.value.includes(id)
        ? selectedIds.value.filter((v) => v !== id)
        : [...selectedIds.value, id],
    )
}
function remove(id: string) {
  emit(
    'update:modelValue',
    props.category === 'main' ? '' : selectedIds.value.filter((v) => v !== id),
  )
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
      emit('update:modelValue', props.category === 'main' ? artifact.id : ids)
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
          :class="{ 'is-disabled': disabled || uploading || !root }"
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
            :multiple="category === 'dependency'"
            :disabled="disabled || uploading || !root"
            @change="upload"
          />
        </label>
      </div>
    </div>
    <div class="picker-browser">
      <select v-model="scope" aria-label="文件目录">
        <option value="job">当前作业目录</option>
        <option value="shared">共享目录</option>
      </select>
      <small class="picker-path">s3://{{ bucket }}/{{ prefix || base }}</small>
      <div class="picker-navigation">
        <button
          type="button"
          class="button"
          :disabled="prefix === base || busy"
          @click="load(prefix.slice(0, -1).slice(0, prefix.slice(0, -1).lastIndexOf('/') + 1))"
        >
          ← 上一级
        </button>
        <details v-if="dirs.length" class="folder-menu">
          <summary>
            子目录 <span class="count">{{ dirs.length }}</span
            ><span>▾</span>
          </summary>
          <div class="folder-popover">
            <input v-model="folderQuery" placeholder="查找子目录" aria-label="查找子目录" />
            <div class="folder-list" role="list">
              <button
                v-for="dir in visibleDirs"
                :key="dir"
                type="button"
                :title="dir"
                :disabled="busy"
                @click="
                  load(dir)
                  ;($event.currentTarget as HTMLElement).closest('details')?.removeAttribute('open')
                  folderQuery = ''
                "
              >
                ▣ {{ dir.slice(prefix.length).replace(/\/$/, '') }}
              </button>
              <small v-if="!visibleDirs.length">没有匹配的目录</small>
            </div>
          </div>
        </details>
      </div>
      <input v-model="query" :aria-label="'筛选' + label" placeholder="按文件名筛选当前目录" />
      <select
        v-if="category === 'main'"
        :value="modelValue"
        aria-label="主 JAR 文件"
        :disabled="disabled || uploading"
        @change="choose(($event.target as HTMLSelectElement).value)"
      >
        <option value="">使用镜像内 JAR 路径</option>
        <option
          v-for="a in selected.filter((a) => !choices.some((c) => c.id === a.id))"
          :key="a.id"
          :value="a.id"
        >
          {{ a.name }}（已选）
        </option>
        <option v-for="a in choices" :key="a.id" :value="a.id">{{ a.name }}</option>
      </select>
      <div v-else class="object-choices">
        <label v-for="a in choices" :key="a.id"
          ><input
            type="checkbox"
            :checked="selectedIds.includes(a.id)"
            :disabled="disabled || uploading"
            @change="choose(a.id)"
          />{{ a.name }}</label
        ><small v-if="!choices.length">当前目录没有文件</small>
      </div>
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
  min-height: 32px;
  white-space: nowrap;
}
.picker-actions .is-disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.picker-browser {
  display: grid;
  gap: 8px;
  padding: 12px;
  background: var(--surface-soft, #f7f9fc);
  border: 1px solid var(--line, #e1e7ef);
  border-radius: 8px;
}
.picker-browser input,
.picker-browser select {
  margin: 0;
  background: white;
}
.picker-path {
  overflow-wrap: anywhere;
  line-height: 1.6;
}
.picker-browser .toolbar-actions:has(button:only-child:disabled) {
  display: none;
}

.picker-navigation {
  display: flex;
  gap: 8px;
  align-items: center;
}
.folder-menu {
  position: relative;
  min-width: 160px;
}
.folder-menu summary {
  cursor: pointer;
  display: flex;
  gap: 8px;
  align-items: center;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 6px 10px;
  background: white;
  font-size: 12px;
}
.folder-menu summary > span:last-child {
  margin-left: auto;
}
.folder-popover {
  position: absolute;
  z-index: 20;
  top: calc(100% + 5px);
  left: 0;
  width: min(360px, 65vw);
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: white;
  box-shadow: 0 8px 24px #172b4d20;
}
.folder-list {
  max-height: 220px;
  overflow: auto;
  display: grid;
  margin-top: 8px;
}
.folder-list button {
  border: 0;
  background: transparent;
  padding: 9px 6px;
  color: var(--text);
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: pointer;
}
.folder-list button:hover {
  background: var(--surface-soft, #f5f8ff);
  color: var(--primary);
}
.object-choices {
  max-height: 160px;
  overflow: auto;
}
.object-choices label {
  display: flex;
  gap: 8px;
  padding: 6px;
}
.object-choices input {
  width: auto;
}
</style>
