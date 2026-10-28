<script setup lang="ts">
import { Moon, Sun, Palette } from '@lucide/vue'
import { currentTheme, themes, setTheme, type Theme } from '../../composables/useTheme'
</script>
<template>
  <label class="theme-picker" title="界面主题">
    <Moon v-if="currentTheme === 'dark'" :size="15" aria-hidden="true" />
    <Palette v-else-if="currentTheme === 'pearl'" :size="15" aria-hidden="true" />
    <Sun v-else :size="15" aria-hidden="true" />
    <select
      aria-label="界面主题"
      :value="currentTheme"
      @change="setTheme(($event.target as HTMLSelectElement).value as Theme)"
    >
      <option v-for="theme in themes" :key="theme.value" :value="theme.value">
        {{ theme.label }}
      </option>
    </select>
  </label>
</template>
<style scoped>
.theme-picker {
  display: inline-flex;
  align-items: center;
  position: relative;
  gap: 6px;
  color: var(--color-primary);
  flex: none;
  margin: 0;
}
.theme-picker > svg {
  position: absolute;
  left: 8px;
  pointer-events: none;
  z-index: 1;
}
.theme-picker select {
  width: 162px;
  padding-left: 29px;
  margin: 0;
  font-size: var(--font-caption);
  background: var(--color-surface-soft);
  color: var(--color-text);
}
@media (max-width: 760px) {
  .theme-picker select {
    width: 36px;
    padding: 0;
    color: transparent;
    appearance: none;
  }
  .theme-picker select option {
    color: var(--color-text);
  }
  .theme-picker > svg {
    left: 10px;
  }
}
</style>
