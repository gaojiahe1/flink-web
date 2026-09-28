<script setup lang="ts">
import { useManualRefresh } from '../../composables/useManualRefresh'
const props = withDefaults(
  defineProps<{ action: () => Promise<void>; disabled?: boolean; label?: string }>(),
  { label: '刷新' },
)
const { refresh, disabled: cooling, remaining, busy } = useManualRefresh(() => props.action())
</script>
<template>
  <button type="button" class="button" :disabled="disabled || cooling" @click="refresh">
    {{ busy ? '正在查询…' : remaining ? remaining + ' 秒后刷新' : label }}
  </button>
</template>
