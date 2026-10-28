<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { X } from '@lucide/vue'
defineProps<{ title: string }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
onMounted(() => dialog.value?.showModal())
</script>
<template>
  <dialog
    ref="dialog"
    class="ai-content-dialog"
    :aria-label="title"
    @cancel.prevent="emit('close')"
  >
    <header>
      <h3>{{ title }}</h3>
      <button class="icon-button" aria-label="关闭大视图" @click="emit('close')">
        <X :size="20" />
      </button>
    </header>
    <div class="ai-content-body"><slot /></div>
  </dialog>
</template>
<style scoped>
.ai-content-dialog {
  box-sizing: border-box;
  width: min(1520px, 96vw);
  height: 92dvh;
  max-width: 96vw;
  max-height: 96dvh;
  margin: auto;
  padding: 16px 20px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--color-surface);
  color: var(--text);
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
  box-shadow: 0 12px 48px var(--color-shadow-soft);
}
.ai-content-dialog::backdrop {
  background: var(--color-overlay);
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex: none;
}
h3 {
  margin: 0;
  font-size: var(--font-section);
}
.ai-content-body {
  flex: 1;
  min-height: 0;
  min-width: 0;
  overflow: auto;
}
@media (max-width: 600px) {
  .ai-content-dialog {
    padding: 14px;
    width: 100vw;
    max-width: 100vw;
    height: 100dvh;
    max-height: 100dvh;
    border-radius: 0;
  }
}
</style>
