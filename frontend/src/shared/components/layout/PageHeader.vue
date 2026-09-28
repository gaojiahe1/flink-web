<script setup lang="ts">
defineProps<{ eyebrow?: string; title?: string; description?: string }>()
</script>
<template>
  <header class="page-title">
    <div class="page-heading">
      <p v-if="eyebrow" class="eyebrow">{{ eyebrow }}</p>
      <h1>
        <slot name="title">{{ title }}</slot>
      </h1>
      <p class="muted">
        <slot name="description">{{ description }}</slot>
      </p>
    </div>
    <div v-if="$slots.actions" class="page-actions"><slot name="actions" /></div>
  </header>
</template>
