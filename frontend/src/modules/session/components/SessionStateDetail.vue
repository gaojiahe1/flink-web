<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId } from 'vue'
const props = defineProps<{ state: string; message?: string; detail: string }>()
const open = ref(false),
  trigger = ref<HTMLElement>(),
  position = ref({ left: '0px', top: '0px' })
const id = useId()
let timer: ReturnType<typeof setTimeout> | undefined
const content = computed(() => {
  try {
    return JSON.stringify(JSON.parse(props.message || ''), null, 2)
  } catch {
    return props.message || props.detail
  }
})
function show() {
  clearTimeout(timer)
  const rect = trigger.value?.getBoundingClientRect()
  if (!rect) return
  position.value = {
    left:
      Math.max(
        12,
        Math.min(rect.left, window.innerWidth - Math.min(640, window.innerWidth - 24) - 12),
      ) + 'px',
    top: Math.max(12, Math.min(rect.bottom + 8, window.innerHeight - 350)) + 'px',
  }
  open.value = true
}
function hide() {
  timer = setTimeout(() => (open.value = false), 160)
}
function keep() {
  clearTimeout(timer)
}
onBeforeUnmount(() => clearTimeout(timer))
</script>
<template>
  <div
    ref="trigger"
    class="session-state"
    @mouseenter="show"
    @mouseleave="hide"
    @focusin="show"
    @focusout="hide"
    @keydown.esc="open = false"
  >
    <button
      type="button"
      class="session-state-trigger"
      :aria-describedby="open ? id : undefined"
      :aria-expanded="open"
      @click="show"
    >
      <span class="badge" :class="{ running: state === '已就绪', failed: state === '部署异常' }">{{
        state
      }}</span>
      <small v-if="message" class="table-description">{{ message }}</small>
    </button>
    <Teleport to="body"
      ><section
        v-if="open"
        :id="id"
        role="tooltip"
        class="session-state-popup"
        :style="position"
        @mouseenter="keep"
        @mouseleave="hide"
      >
        <strong>{{ state }}</strong>
        <pre>{{ content }}</pre>
        <slot />
        <small>{{ detail }}</small>
      </section></Teleport
    >
  </div>
</template>
<style scoped>
.session-state-trigger {
  display: block;
  line-height: 20px;
  border: 0;
  background: transparent;
  padding: 0;
  text-align: left;
  max-width: 100%;
  cursor: help;
  color: inherit;
  font: inherit;
}
.session-state-trigger:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 4px;
}
.session-state-trigger .badge {
  display: flex;
  width: max-content;
}
.session-state-trigger small {
  max-width: min(36vw, 540px);
}
.session-state-popup {
  position: fixed;
  z-index: 2100;
  box-sizing: border-box;
  width: min(640px, calc(100vw - 24px));
  max-height: min(340px, calc(100vh - 24px));
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--color-surface);
  padding: 16px;
  box-shadow: 0 12px 36px var(--color-shadow-soft);
  color: var(--color-text);
  font-size: var(--font-small);
  line-height: 1.65;
}
.session-state-popup pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: var(--font-small);
  font-family: var(--font-family-code);
  line-height: 1.65;
  margin: 10px 0;
}
.session-state-popup small {
  white-space: pre-wrap;
  color: var(--color-muted);
}
</style>
