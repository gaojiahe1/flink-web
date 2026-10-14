<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { X, Sparkles } from '@lucide/vue'
import AiModelSelect from './AiModelSelect.vue'
import AiResultReport from './AiResultReport.vue'
import { type AiModel } from '../api/ai'
import { useAiTask } from '../composables/useAiTask'
const props = defineProps<{
  job: { id: string; name: string }
  error?: string
  canReadLogs: boolean
}>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
onMounted(() => dialog.value?.showModal())
const modelId = ref(''),
  model = ref<AiModel>(),
  allowExternal = ref(false),
  includeLogs = ref(true),
  instruction = ref('分析当前作业报错，给出原因、证据和分步解决方案。')
const { task, busy, error: failure, start, cancel } = useAiTask()
watch(
  () => props.job.id,
  () => {
    void cancel()
    task.value = null
  },
)
watch(modelId, () => {
  allowExternal.value = false
})
function analyze() {
  return start({
    kind: 'diagnosis',
    modelId: modelId.value,
    jobId: props.job.id,
    instruction: instruction.value,
    allowExternal: allowExternal.value,
    includeLogs: includeLogs.value && props.canReadLogs,
  })
}
</script>
<template>
  <dialog
    ref="dialog"
    class="ai-analysis-drawer"
    :aria-label="'AI 分析：' + job.name"
    @cancel.prevent="emit('close')"
  >
    <div class="field-heading">
      <div>
        <h3><Sparkles :size="18" />AI 作业报错分析</h3>
        <p class="muted">{{ job.name }}</p>
      </div>
      <button class="icon-button" aria-label="关闭 AI 分析" @click="emit('close')">
        <X :size="19" />
      </button>
    </div>
    <p v-if="error" class="notice error ai-prewrap">{{ error }}</p>
    <AiModelSelect
      v-model="modelId"
      purpose="diagnosis"
      :disabled="busy"
      @selected="model = $event"
    />
    <label v-if="model?.external" class="ai-check"
      ><input v-model="allowExternal" :disabled="busy" type="checkbox" />允许发送本次脱敏错误、Pod
      事件及所选日志到此外部模型</label
    >
    <label v-if="canReadLogs" class="ai-check"
      ><input
        v-model="includeLogs"
        :disabled="busy"
        type="checkbox"
      />收集当前作业所属容器的受限脱敏日志（最多两份）</label
    >
    <p v-else class="muted">当前权限仅分析可读的状态和事件，不收集运维日志。</p>
    <label
      >问题或补充说明<textarea v-model="instruction" :disabled="busy" maxlength="8000" rows="3" />
    </label>
    <div class="toolbar-actions">
      <button
        class="button primary"
        :disabled="busy || !modelId || (model?.external && !allowExternal)"
        @click="analyze"
      >
        {{ busy ? '正在收集证据并分析…' : '开始 AI 分析' }}</button
      ><button v-if="busy" class="button" @click="cancel">取消</button>
    </div>
    <p v-if="task" class="muted" role="status">{{ task.state }} · {{ task.model }}</p>
    <p v-if="failure" class="notice error" role="alert">{{ failure }}</p>
    <AiResultReport v-if="task?.state === 'SUCCEEDED' && task.result" :result="task.result" />
    <p class="ai-boundary">
      仅本次主动取证，不持续监控。建议不会修改配置、停止、重启或重新部署作业。
    </p>
  </dialog>
</template>
<style scoped>
.ai-analysis-drawer {
  position: fixed;
  inset: 0 0 0 auto;
  margin: 0 0 0 auto;
  width: min(560px, 100%);
  height: 100vh;
  max-height: 100vh;
  max-width: 100vw;
  border: 0;
  background: #fff;
  padding: 24px;
  overflow: auto;
  box-shadow: -5px 0 24px #1522381a;
}
.ai-analysis-drawer::backdrop {
  background: #15223866;
}
h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
}
.muted,
.ai-check,
.ai-boundary {
  font-size: 12px;
}
.ai-check {
  display: flex;
  gap: 8px;
  align-items: center;
  font-weight: 400;
}
.ai-check input {
  width: auto;
  flex: none;
}
textarea {
  width: 100%;
  resize: vertical;
}
.ai-boundary {
  border-top: 1px solid var(--line);
  margin-top: 20px;
  padding-top: 14px;
  color: var(--color-muted);
}
.ai-prewrap {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
