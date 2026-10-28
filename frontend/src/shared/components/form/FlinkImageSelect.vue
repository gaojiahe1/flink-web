<script setup lang="ts">
import { computed, ref, watchEffect } from 'vue'
import { useOperator } from '../../composables/useOperator'
const props = defineProps<{
  modelValue: string
  images: { id: string; name?: string; version?: string; flinkVersion?: string }[]
  required?: boolean
  fallback?: string
  label?: string
}>()
const selectElement = ref<HTMLSelectElement>()
const emit = defineEmits<{ 'update:modelValue': [string] }>()
const { status, hint, refresh, disabled, remaining } = useOperator()
const available = computed(() => {
  const compatibility = status.value?.compatibility
  return compatibility?.known
    ? props.images.filter((image) =>
        compatibility.supported?.includes(
          (image.flinkVersion || '')
            .replace(/^v/, '')
            .replace('_', '.')
            .split('.')
            .slice(0, 2)
            .join('.'),
        ),
      )
    : props.images
})
const incompatible = computed(
  () => !!props.modelValue && !available.value.some((image) => image.id === props.modelValue),
)
watchEffect(() =>
  selectElement.value?.setCustomValidity(
    incompatible.value ? '请选择当前 Operator 支持的镜像版本' : '',
  ),
)
const display = computed(() => {
  const image = props.images.find((i) => i.id === props.modelValue)
  return image ? 'Flink ' + image.flinkVersion : props.fallback || '请选择 Flink 版本'
})
</script>
<template>
  <span class="image-version-field"
    ><span class="flink-image-select">
      <select
        ref="selectElement"
        :value="modelValue"
        :required="required"
        :aria-label="label || '镜像版本'"
        @change="emit('update:modelValue', ($event.target as HTMLSelectElement).value)"
      >
        <option value="">{{ fallback || '请选择镜像版本' }}</option>
        <option v-if="incompatible" :value="modelValue" disabled>
          当前镜像不在受支持范围，请重新选择
        </option>
        <option v-for="image in available" :key="image.id" :value="image.id">
          Flink {{ image.flinkVersion }} · tag: {{ image.version }} · {{ image.name }}
        </option>
      </select>
      <span aria-hidden="true" class="image-selection">{{ display }}</span>
    </span>
    <small class="operator-hint" :class="{ 'field-error': incompatible }">{{
      incompatible ? '当前镜像不受已识别 Operator 支持，请更换镜像。' : hint
    }}</small>
    <button
      type="button"
      class="text-button operator-refresh"
      :disabled="disabled"
      @click="refresh"
    >
      {{ remaining ? remaining + ' 秒后刷新' : '刷新支持范围' }}
    </button>
  </span>
</template>
<style scoped>
.flink-image-select {
  position: relative;
  display: block;
  min-width: 0;
}
select {
  color: transparent;
}
select option {
  color: var(--text);
}
.image-selection {
  position: absolute;
  left: 9px;
  right: 25px;
  top: 50%;
  transform: translateY(-42%);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  pointer-events: none;
  font-size: 12px;
  font-weight: 400;
}
</style>

<style scoped>
.operator-hint {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  font-weight: 400;
  color: var(--color-muted);
  overflow-wrap: anywhere;
}
.operator-refresh {
  font-size: 11px;
  padding: 2px 0;
}
.image-version-field {
  display: block;
  min-width: 0;
}
.field-error {
  color: var(--color-danger);
}
</style>
