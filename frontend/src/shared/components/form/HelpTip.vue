<script setup lang="ts">
import { ref, useId } from 'vue'
defineProps<{ text: string }>()
const descriptionId = useId()
const open = ref(false),
  left = ref(0),
  top = ref(0)
function show(event: Event) {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  left.value = Math.max(12, Math.min(rect.left, window.innerWidth - 322))
  top.value = Math.min(rect.bottom + 8, window.innerHeight - 160)
  open.value = true
}
</script>
<template>
  <span class="help-tip">
    <button
      type="button"
      aria-label="参数说明"
      :aria-describedby="descriptionId"
      @mouseenter="show"
      @mouseleave="open = false"
      @focus="show"
      @blur="open = false"
      @keydown.esc="open = false"
    >
      ?
    </button>
    <span :id="descriptionId" hidden>{{ text }}</span>
    <Teleport to="body"
      ><span
        v-if="open"
        role="tooltip"
        class="help-popover"
        :style="{ left: left + 'px', top: top + 'px' }"
        >{{ text }}</span
      ></Teleport
    >
  </span>
</template>
