<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ modelValue: string; label: string }>()
const emit = defineEmits<{ 'update:modelValue': [string] }>()
const unit = computed(() => (/g(b)?$/i.test(props.modelValue) ? 'g' : 'm'))
const amount = computed(() => parseFloat(props.modelValue) || '')
function update(value: string, suffix = unit.value) {
  emit('update:modelValue', value + suffix)
}
</script>
<template>
  <label
    >{{ label }}
    <div class="input-unit">
      <input
        :aria-label="label"
        type="number"
        min="1"
        step="1"
        required
        :value="amount"
        @input="update(($event.target as HTMLInputElement).value)"
      /><select
        :aria-label="label + '单位'"
        :value="unit"
        @change="update(String(amount), ($event.target as HTMLSelectElement).value)"
      >
        <option value="m">MB</option>
        <option value="g">GB</option>
      </select>
    </div></label
  >
</template>
