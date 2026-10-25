<script setup lang="ts">
import { ChevronLeft, ChevronRight } from '@lucide/vue'
defineProps<{ kind: 'SQL' | 'JAR'; count: number; collapsed: boolean }>()
defineEmits<{ toggle: [] }>()
</script>
<template>
  <div class="draft-list-heading">
    <strong v-if="!collapsed"
      >{{ kind }} 草稿 <slot /><span class="draft-count">{{ count }}</span></strong
    >
    <button
      class="text-button"
      :aria-label="(collapsed ? '展开 ' : '收起 ') + kind + ' 草稿栏'"
      @click="$emit('toggle')"
    >
      <ChevronRight v-if="collapsed" :size="16" /><ChevronLeft v-else :size="16" />
    </button>
  </div>
</template>
<style scoped>
.draft-list-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  min-height: 24px;
  flex-shrink: 0;
  font-size: 12px;
}
strong {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-weight: 600;
}
.draft-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  padding: 0 5px;
  border-radius: 4px;
  background: #f0f4fa;
  color: var(--muted);
  font-size: 11px;
  font-weight: 500;
}
button {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  padding: 4px;
}
</style>
