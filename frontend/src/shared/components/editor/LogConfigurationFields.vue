<script setup lang="ts">
import { defaultLogConfiguration } from '../../utils/logConfiguration'
defineProps<{ modelValue?: Record<string, string>; readonly?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [Record<string, string>] }>()
</script>
<template>
  <details class="log-configuration" data-field="logConfiguration">
    <summary>
      日志配置
      <span class="muted">{{
        Object.keys(modelValue || {}).length ? '已配置' : '使用镜像 / Operator 默认'
      }}</span>
    </summary>
    <div class="log-configuration-body">
      <p class="muted">
        写入 spec.logConfiguration；保存后需显式部署或重启才能生效。默认
        INFO，同时输出控制台与滚动文件（每个 10 MB，当前文件 + 1 个归档，共 2 个）。
      </p>
      <p class="muted">
        Flink UI 的日志页读取文件；Print / System.out 输出通常在 stdout 页。如需转入日志，可在 Flink
        配置中设置 taskmanager.system-out.mode 为 LOG。
      </p>
      <div class="toolbar-actions">
        <button
          type="button"
          class="button"
          :disabled="readonly || !!modelValue?.['log4j-console.properties']"
          @click="emit('update:modelValue', { ...modelValue, ...defaultLogConfiguration() })"
        >
          填入 Log4j2 默认配置
        </button>
        <button
          type="button"
          class="button"
          :disabled="readonly || !Object.keys(modelValue || {}).length"
          @click="emit('update:modelValue', {})"
        >
          移除日志覆盖
        </button>
      </div>
      <label v-for="(value, name) in modelValue || {}" :key="name">
        {{ name }}
        <textarea
          :aria-label="'日志配置文件 ' + name"
          :value="value"
          :readonly="readonly"
          rows="16"
          spellcheck="false"
          @input="
            emit('update:modelValue', {
              ...modelValue,
              [name]: ($event.target as HTMLTextAreaElement).value,
            })
          "
        />
      </label>
      <a
        href="https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-main/zh/docs/operations/logging/"
        target="_blank"
        rel="noopener noreferrer"
        >查看 Operator 日志配置说明 ↗</a
      >
    </div>
  </details>
</template>
<style scoped>
.log-configuration {
  margin-top: 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  min-width: 0;
}
summary {
  padding: 10px 12px;
  cursor: pointer;
}
summary span {
  margin-left: 12px;
}
.log-configuration-body {
  padding: 0 12px 12px;
  display: grid;
  gap: 10px;
}
textarea {
  min-width: 0;
  max-width: 100%;
  width: 100%;
  min-height: 280px;
  resize: vertical;
  font-family: var(--font-family-code);
  white-space: pre;
  overflow: auto;
}
</style>
