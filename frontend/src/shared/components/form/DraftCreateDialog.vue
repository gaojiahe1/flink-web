<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { jsonBody, request } from '../../api/http'
const props = withDefaults(
  defineProps<{ runtime?: Record<string, unknown>; kind?: 'SQL' | 'JAR' }>(),
  { kind: 'SQL', runtime: () => ({}) },
)
const emit = defineEmits<{ created: [string]; named: [string]; close: [] }>()
const dialog = ref<HTMLDialogElement>(),
  nameInput = ref<HTMLInputElement>()
const name = ref(''),
  confirmed = ref(false),
  available = ref(false),
  checking = ref(false),
  busy = ref(false),
  message = ref(''),
  error = ref('')
let serial = 0,
  disposed = false,
  timer: ReturnType<typeof setTimeout> | undefined
let controller: AbortController | undefined
const valid = () => /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/.test(name.value) && name.value.length <= 63
async function checkName() {
  if (timer) clearTimeout(timer)
  const version = ++serial,
    value = name.value
  controller?.abort()
  available.value = false
  if (!valid()) {
    message.value = value ? '名称仅支持小写字母、数字、连字符，且首尾不能为连字符' : ''
    return false
  }
  checking.value = true
  controller = new AbortController()
  try {
    const result = await request<{ available: boolean }>(
      '/api/jobs/name-availability?' + new URLSearchParams({ name: value }),
      { signal: controller.signal },
    )
    if (disposed || serial !== version || name.value !== value) return false
    available.value = result.available
    message.value = result.available ? '名称可用' : '作业名称已存在，请使用其他名称'
    return result.available
  } catch (cause) {
    if (!disposed && serial === version) message.value = '名称校验失败，请重试'
    return false
  } finally {
    if (!disposed && serial === version) checking.value = false
  }
}
watch(name, () => {
  confirmed.value = false
  available.value = false
  message.value = ''
  error.value = ''
  serial++
  controller?.abort()
  if (timer) clearTimeout(timer)
  timer = setTimeout(checkName, 250)
})
async function create() {
  if (busy.value || !confirmed.value || !valid()) return
  busy.value = true
  error.value = ''
  const value = name.value
  try {
    if (!(await checkName())) return
    if (props.kind === 'JAR') {
      emit('named', value)
      return
    }
    try {
      await request('/api/sql/jobs/' + encodeURIComponent(value), {
        method: 'PUT',
        ...jsonBody({ name: value, description: '', sql: '', revision: 0, runtime: props.runtime }),
      })
    } catch (cause) {
      // 创建响应可能丢失；只恢复同一独占工作目录的记录，不再次创建或接管同名草稿。
      const old = await request<{ runtime: { workspaceId?: string } }>(
        '/api/sql/jobs/' + encodeURIComponent(value),
      ).catch(() => undefined)
      if (!old || old.runtime.workspaceId !== props.runtime.workspaceId) throw cause
    }
    if (!disposed) emit('created', value)
  } catch (cause) {
    if (!disposed) error.value = (cause as Error).message
  } finally {
    if (!disposed) busy.value = false
  }
}
onMounted(() => {
  dialog.value?.showModal()
  nameInput.value?.focus()
})
onBeforeUnmount(() => {
  disposed = true
  serial++
  controller?.abort()
  if (timer) clearTimeout(timer)
  dialog.value?.close()
})
</script>
<template>
  <dialog
    ref="dialog"
    class="app-confirm-dialog sql-create-dialog"
    @cancel.prevent="!busy && emit('close')"
  >
    <form @submit.prevent="create">
      <h3>新建 {{ kind === 'SQL' ? 'SQL 草稿' : 'JAR 作业' }}</h3>
      <p class="muted">
        先确认唯一的作业名称，创建后不可修改。{{
          kind === 'SQL'
            ? 'SQL 内容为空，不填充默认案例。'
            : '确认后进入配置页面，保存时会再次校验名称。'
        }}
      </p>
      <label
        >作业名称<input
          ref="nameInput"
          v-model="name"
          aria-label="新草稿作业名称"
          pattern="[a-z0-9]([\-a-z0-9]*[a-z0-9])?"
          maxlength="63"
          required
          :disabled="busy"
          placeholder="例如 orders-daily-report"
          @blur="checkName"
      /></label>
      <p class="sql-name-status" :class="{ 'field-error': !!message && !available }" role="status">
        {{ checking ? '正在检查名称…' : message || '小写字母、数字、连字符，最多 63 个字符' }}
      </p>
      <label class="sql-name-confirm"
        ><input
          v-model="confirmed"
          type="checkbox"
          :disabled="busy"
        />我已确认此名称，创建后不可修改</label
      >
      <p v-if="error" class="field-error" role="alert">{{ error }}</p>
      <div class="dialog-footer">
        <button type="button" class="button" :disabled="busy" @click="emit('close')">取消</button
        ><button
          type="submit"
          class="button primary"
          :disabled="busy || checking || !confirmed || !available"
        >
          {{ busy ? '正在创建…' : '确认并创建' }}
        </button>
      </div>
    </form>
  </dialog>
</template>
<style scoped>
.sql-create-dialog {
  width: min(460px, calc(100vw - 32px));
  padding: 24px;
}
.sql-create-dialog h3 {
  margin: 0 0 12px;
}
.sql-create-dialog p {
  font-size: 12px;
  line-height: 1.65;
}
.sql-create-dialog label {
  margin-top: 18px;
}
.sql-name-confirm {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12px;
}
.sql-name-confirm input {
  width: auto;
  flex-shrink: 0;
  margin-top: 2px;
}
.sql-name-status {
  min-height: 20px;
  margin: 6px 0 0;
  color: var(--muted);
}
.dialog-footer {
  margin-top: 24px;
}
</style>
