<script setup lang="ts">
import DraftCreateDialog from '../../../shared/components/form/DraftCreateDialog.vue'
defineProps<{ runtime: Record<string, unknown> }>()
const emit = defineEmits<{ created: [string]; close: [] }>()
</script>
<template>
  <DraftCreateDialog :runtime="runtime" @created="emit('created', $event)" @close="emit('close')" />
</template>
