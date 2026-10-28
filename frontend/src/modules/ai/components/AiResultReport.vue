<script setup lang="ts">
import type { AiResult } from '../api/ai'
defineProps<{ result: AiResult }>()
const sections = [
  ['evidence', '依据与证据'],
  ['unknowns', '仍需确认'],
  ['risks', '注意事项'],
  ['steps', '建议处理步骤'],
] as const
</script>
<template>
  <div class="ai-report">
    <p class="ai-summary">{{ result.summary }}</p>
    <template v-for="section in sections" :key="section[0]">
      <section v-if="result[section[0]]?.length">
        <h4>{{ section[1] }}</h4>
        <ol v-if="section[0] === 'steps'">
          <li v-for="(text, i) in result.steps" :key="i">{{ text }}</li>
        </ol>
        <ul v-else>
          <li v-for="(text, i) in result[section[0]]" :key="i">{{ text }}</li>
        </ul>
      </section>
    </template>
  </div>
</template>
<style scoped>
.ai-report {
  font-size: var(--font-small);
  overflow-wrap: anywhere;
}
.ai-summary {
  white-space: pre-wrap;
  color: var(--color-text);
}
section {
  border-top: 1px solid var(--line);
  padding-top: 10px;
  margin-top: 12px;
}
h4 {
  font-size: var(--font-body);
  margin: 0 0 6px;
  font-weight: 600;
}
ol,
ul {
  padding-left: 18px;
  margin: 0;
}
li {
  margin-bottom: 6px;
  white-space: pre-wrap;
}
</style>
