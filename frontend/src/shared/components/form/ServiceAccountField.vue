<script setup lang="ts">
import { computed, watch } from 'vue'
import { systemInfo } from '../../../modules/system/stores/system'
import HelpTip from './HelpTip.vue'
const props = withDefaults(
  defineProps<{ modelValue: string; disabled?: boolean; autoDefault?: boolean }>(),
  { autoDefault: true },
)
const emit = defineEmits<{ 'update:modelValue': [string] }>()
const suggested = computed(() =>
  systemInfo.value?.namespace ? systemInfo.value.namespace + '-flink' : '',
)
watch(
  suggested,
  (value) => {
    if (props.autoDefault !== false && !props.modelValue && value) emit('update:modelValue', value)
  },
  { immediate: true },
)
</script>
<template>
  <label
    >ServiceAccount<input
      data-field="serviceAccount"
      :value="modelValue"
      @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
      required
      pattern="[a-z0-9]([\-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([\-a-z0-9]*[a-z0-9])?)*"
      maxlength="253"
      :placeholder="suggested || '填写运行账号'"
      :disabled="disabled" /><HelpTip
      text="默认使用当前 namespace-flink，可修改为该 namespace 中已创建并授权的运行账号。"
  /></label>
</template>
