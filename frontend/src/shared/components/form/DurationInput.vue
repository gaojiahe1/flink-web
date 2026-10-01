<script setup lang="ts">
import { computed } from 'vue'
const props = withDefaults(defineProps<{ modelValue?: string; label: string; min?: number }>(), {
  min: 0,
})
const emit = defineEmits<{ 'update:modelValue': [string] }>()
const aliases: Record<string, string> = {
  nanosecond: 'ns',
  nanoseconds: 'ns',
  microsecond: 'us',
  microseconds: 'us',
  millisecond: 'ms',
  milliseconds: 'ms',
  second: 's',
  seconds: 's',
  minute: 'min',
  minutes: 'min',
  hour: 'h',
  hours: 'h',
  day: 'd',
  days: 'd',
}
const parsed = computed(() =>
  (props.modelValue || '').trim().match(/^([0-9]+(?:\.[0-9]+)?)\s*([a-z]+)?$/i),
)
const amount = computed(() => parsed.value?.[1] || '')
// Flink 未写单位的时长以毫秒解释；编辑旧配置时保持其语义。
const unit = computed(() => {
  const suffix = parsed.value?.[2]?.toLowerCase() || 'ms'
  return aliases[suffix] || suffix
})
function update(value: string, suffix = unit.value) {
  emit('update:modelValue', value ? value + ' ' + suffix : '')
}
</script>
<template>
  <label
    >{{ label }}
    <div class="input-unit">
      <input
        :aria-label="label"
        data-field="flinkConfiguration"
        type="number"
        :min="min"
        step="any"
        required
        :value="amount"
        @input="update(($event.target as HTMLInputElement).value)"
      />
      <select
        :aria-label="label + '单位'"
        :value="unit"
        @change="update(String(amount), ($event.target as HTMLSelectElement).value)"
      >
        <option value="ns">ns</option>
        <option value="us">μs</option>
        <option value="ms">毫秒</option>
        <option value="s">秒</option>
        <option value="min">分钟</option>
        <option value="h">小时</option>
        <option value="d">天</option>
      </select>
    </div>
  </label>
</template>
