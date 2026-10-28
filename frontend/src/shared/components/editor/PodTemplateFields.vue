<script setup lang="ts">
import { reactive, watch } from 'vue'
import { stringify } from 'yaml'
import CodeEditor from './CodeEditor.vue'
import {
  emptyPodTemplates,
  podTemplateFields,
  parsePodTemplate,
  type PodTemplateOptions,
  type PodTemplateKey,
} from '../../utils/podTemplates'
const props = defineProps<{ modelValue: PodTemplateOptions; readonly?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [PodTemplateOptions]; valid: [boolean] }>()
const texts = reactive({ podTemplate: '', jmPodTemplate: '', tmPodTemplate: '' })
const errors = reactive({ podTemplate: '', jmPodTemplate: '', tmPodTemplate: '' })
const opened = reactive({ podTemplate: false, jmPodTemplate: false, tmPodTemplate: false })
let lastEmitted = ''
watch(
  () => props.modelValue,
  (value) => {
    if (JSON.stringify(value) === lastEmitted && !Object.values(errors).some(Boolean)) return
    for (const field of podTemplateFields) {
      texts[field.key] = Object.keys(value[field.key] || {}).length
        ? stringify(value[field.key])
        : ''
      errors[field.key] = ''
    }
    emit('valid', true)
  },
  { deep: true, immediate: true },
)
function update(key: PodTemplateKey, source: string) {
  texts[key] = source
  const value = emptyPodTemplates()
  for (const field of podTemplateFields) {
    try {
      value[field.key] = parsePodTemplate(texts[field.key], field.label)
      errors[field.key] = ''
    } catch (e) {
      errors[field.key] = (e as Error).message
    }
  }
  const valid = !Object.values(errors).some(Boolean)
  emit('valid', valid)
  if (valid) {
    lastEmitted = JSON.stringify(value)
    emit('update:modelValue', value)
  }
}
</script>
<template>
  <div class="pod-template-fields">
    <details
      v-for="field in podTemplateFields"
      :key="field.key"
      class="pod-template-field"
      :data-field="field.key"
      @toggle="opened[field.key] = ($event.target as HTMLDetailsElement).open"
    >
      <summary>
        <span>{{ field.label }}</span
        ><span class="muted">{{
          errors[field.key] ? '格式有误' : texts[field.key].trim() ? '已配置' : '未配置'
        }}</span>
      </summary>
      <div class="pod-template-body">
        <p class="muted">{{ field.path }} · YAML 对象，留空移除此处模板。</p>
        <CodeEditor
          v-if="opened[field.key]"
          :model-value="texts[field.key]"
          @update:model-value="update(field.key, $event)"
          :readonly="readonly"
          :label="field.label + '（YAML）'"
        />
        <p v-if="errors[field.key]" class="field-error" role="alert">{{ errors[field.key] }}</p>
      </div>
    </details>
  </div>
</template>
<style scoped>
.pod-template-fields {
  display: grid;
  gap: 10px;
  margin-top: 14px;
  min-width: 0;
}
.pod-template-field {
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  min-width: 0;
}
.pod-template-field summary {
  padding: 10px 12px;
  cursor: pointer;
  font-size: var(--font-body);
  font-weight: 600;
}
.pod-template-field summary .muted {
  float: right;
  font-size: var(--font-small);
  font-weight: 400;
}
.pod-template-body {
  padding: 0 12px 12px;
}
.pod-template-body .muted {
  font-size: var(--font-small);
  line-height: 1.7;
  margin: 0 0 10px;
  overflow-wrap: anywhere;
}
.pod-template-body .code-editor {
  height: 240px;
  min-height: 240px;
}
</style>
