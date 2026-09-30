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
    <div class="field-heading">
      <strong>{{ label }}</strong
      ><button type="button" class="text-button" :disabled="busy" @click="load(prefix)">
        刷新文件
      </button>
      <label class="button upload-button"
        >{{ uploading ? '正在上传…' : '上传文件'
        }}<input
          type="file"
          :aria-label="category === 'main' ? '上传主 JAR' : '上传依赖文件'"
          :accept="category === 'main' ? '.jar' : undefined"
          :multiple="category === 'dependency'"
          :disabled="disabled || uploading || !root"
          @change="upload"
      /></label>
    </div>
    <select v-model="scope" aria-label="文件目录">
      <option value="job">当前作业目录</option>
      <option value="shared">共享目录</option>
    </select>
    <small>s3://{{ bucket }}/{{ prefix || base }}</small>
    <div class="toolbar-actions">
      <button
        type="button"
        class="text-button"
        :disabled="prefix === base || busy"
        @click="load(prefix.slice(0, -1).slice(0, prefix.slice(0, -1).lastIndexOf('/') + 1))"
      >
        上一级</button
      ><button v-for="dir in dirs" :key="dir" type="button" class="text-button" @click="load(dir)">
        ▣ {{ dir.slice(prefix.length).replace(/\/$/, '') }}
      </button>
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
