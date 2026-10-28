<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { aiApi, type AiModel } from '../api/ai'
import { currentUser } from '../../permission/stores/auth'
const props = defineProps<{ purpose: string; disabled?: boolean }>()
const selected = defineModel<string>({ default: '' })
const emit = defineEmits<{ selected: [AiModel | undefined] }>()
const models = ref<AiModel[]>([]),
  error = ref('')
const choices = computed(() =>
  models.value.filter((m) => m.enabled && m.purposes.includes(props.purpose)),
)
async function load() {
  try {
    models.value = await aiApi.models()
    if (!choices.value.some((m) => m.id === selected.value))
      selected.value = choices.value[0]?.id || ''
  } catch (cause) {
    error.value = (cause as Error).message
  }
}
onMounted(load)
watch(
  [selected, choices],
  () =>
    emit(
      'selected',
      choices.value.find((m) => m.id === selected.value),
    ),
  { immediate: true },
)
</script>
<template>
  <div class="ai-model-field">
    <label
      >任务模型<select v-model="selected" :disabled="disabled" aria-label="任务模型">
        <option value="" disabled>请选择模型</option>
        <option v-for="model in choices" :key="model.id" :value="model.id">
          {{ model.name }} · {{ model.external ? '外部' : '私有' }}
        </option>
      </select></label
    >
    <RouterLink
      v-if="currentUser?.roles.includes('ROLE_ADMIN')"
      class="text-button"
      to="/settings/models"
      >管理模型 ↗</RouterLink
    >
    <p v-if="!choices.length && !error" class="muted">
      暂无适用于当前任务的模型，请联系管理员添加。
    </p>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
  </div>
</template>
<style scoped>
.ai-model-field {
  display: flex;
  align-items: end;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
label {
  flex: 1;
  min-width: 150px;
  font-size: var(--font-small);
  margin: 0;
}
p {
  width: 100%;
  margin: 0;
  font-size: var(--font-small);
}
</style>
