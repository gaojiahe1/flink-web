<script setup lang="ts">
import { computed, ref } from 'vue'
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
  artifacts: Artifact[]
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
  error = ref(''),
  query = ref('')
const label = computed(() => (props.category === 'main' ? '主 JAR 制品' : '附加依赖与配置文件'))
const choices = computed(() =>
  props.artifacts.filter(
    (a) =>
      a.id !== props.exclude &&
      (props.category !== 'main' || a.filename?.toLowerCase().endsWith('.jar')) &&
      (a.filename || a.name).toLowerCase().includes(query.value.toLowerCase()),
  ),
)
const selected = computed(() =>
  props.artifacts.filter((a) =>
    Array.isArray(props.modelValue) ? props.modelValue.includes(a.id) : props.modelValue === a.id,
  ),
)
async function upload(event: Event) {
  const input = event.target as HTMLInputElement,
    files = Array.from(input.files || [])
  uploading.value = true
  emit('uploading', true)
  error.value = ''
  try {
    for (const file of files) {
      if (props.category === 'main' && !file.name.toLowerCase().endsWith('.jar'))
        throw new Error('主程序请选择 .jar 文件')
      const body = new FormData()
      body.append('file', file)
      body.append('category', props.category)
      const artifact = await request<Artifact>('/api/artifacts/upload', { method: 'POST', body })
      emit('uploaded', artifact)
      emit(
        'update:modelValue',
        props.category === 'main'
          ? artifact.id
          : [
              ...new Set([
                ...(Array.isArray(props.modelValue) ? props.modelValue : []),
                artifact.id,
              ]),
            ],
      )
      // Vue 更新父组件后再处理下一个文件，避免多文件选择丢失。
      await new Promise((resolve) => setTimeout(resolve, 0))
    }
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    uploading.value = false
    emit('uploading', false)
    input.value = ''
  }
}
const selection = computed({
  get: () => props.modelValue,
  set: (value: string | string[]) => {
    if (props.category === 'dependency' && Array.isArray(value)) {
      const visibleIds = new Set(choices.value.map((a) => a.id))
      const retained = (Array.isArray(props.modelValue) ? props.modelValue : []).filter(
        (id) => !visibleIds.has(id),
      )
      emit('update:modelValue', [...new Set([...retained, ...value])])
    } else emit('update:modelValue', value)
  },
})
function remove(id: string) {
  emit(
    'update:modelValue',
    Array.isArray(props.modelValue) ? props.modelValue.filter((item) => item !== id) : '',
  )
}
</script>
<template>
  <div class="artifact-picker">
    <div class="field-heading">
      <strong>{{ label }}</strong
      ><label class="button upload-button"
        >{{ uploading ? '正在上传…' : category === 'main' ? '上传主 JAR' : '上传依赖文件'
        }}<input
          type="file"
          :aria-label="category === 'main' ? '上传主 JAR' : '上传依赖文件'"
          :accept="category === 'main' ? '.jar' : undefined"
          :multiple="category === 'dependency'"
          :disabled="disabled || uploading"
          @change="upload"
      /></label>
    </div>
    <input
      v-model="query"
      :aria-label="'筛选' + label"
      placeholder="按文件名筛选公共制品"
      class="artifact-search"
    /><select
      :aria-label="label"
      v-model="selection"
      :multiple="category === 'dependency'"
      :size="category === 'dependency' ? 3 : undefined"
      :disabled="disabled || uploading"
    >
      <option v-if="category === 'main'" value="">使用镜像内 JAR 路径</option>
      <option v-for="a in choices" :key="a.id" :value="a.id">
        {{ a.filename || a.name }}{{ a.category && a.category !== category ? ' · 公共制品' : '' }}
      </option>
    </select>
    <div class="file-chips">
      <span v-for="file in selected" :key="file.id" class="file-chip" :title="file.uri"
        >{{ file.filename || file.name
        }}<button
          type="button"
          :disabled="disabled || uploading"
          :aria-label="'移除 ' + file.filename"
          @click="remove(file.id)"
        >
          ×
        </button></span
      >
    </div>
    <small
      >公共制品可被所有作业引用；新上传存入
      {{ category === 'main' ? 'main/' : 'dependency/' }} 目录。</small
    >
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
  </div>
</template>
