<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import ArtifactPicker from '../../artifact/components/ArtifactPicker.vue'
import type { Artifact } from '../../artifact/components/ArtifactPicker.vue'
import { request, jsonBody } from '../../../shared/api/http'

export type UdfFunction = {
  className: string
  kind: string
  signatures: string[]
  methodCalls: string[]
  name: string
  description: string
}
export type UdfArtifact = { reference: string; functions: UdfFunction[] }
const props = defineProps<{ modelValue: UdfArtifact[]; scopeId: string; disabled: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [UdfArtifact[]]
  insert: [string]
  uploading: [boolean]
}>()
const reference = ref(''),
  candidates = ref<UdfFunction[]>([]),
  busy = ref(false),
  error = ref(''),
  note = ref('')
let version = 0
onBeforeUnmount(() => {
  version++
})
watch(reference, () => {
  version++
  candidates.value = []
  error.value = ''
  note.value = ''
})
function uploaded(file: Artifact) {
  reference.value = file.id
}
async function inspect() {
  if (busy.value || !reference.value) return
  const current = ++version,
    selected = reference.value
  busy.value = true
  error.value = ''
  try {
    const result = await request<{ functions: UdfFunction[]; note: string }>(
      '/api/artifacts/udf-functions',
      { method: 'POST', ...jsonBody({ reference: selected }) },
    )
    if (current !== version) return
    candidates.value = result.functions.map((f) => ({
      ...f,
      name: f.className
        .split('.')
        .at(-1)!
        .replace(/\$/g, '_')
        .replace(/Function$/, '')
        .toLowerCase(),
      description: '',
    }))
    note.value = result.note
  } catch (cause) {
    if (current === version) error.value = (cause as Error).message
  } finally {
    busy.value = false
  }
}
function add(fn: UdfFunction) {
  error.value = ''
  if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(fn.name)) {
    error.value = '请填写有效的函数名称'
    return
  }
  if (
    props.modelValue
      .flatMap((a) => a.functions)
      .some((f) => f.name.toLowerCase() === fn.name.toLowerCase())
  ) {
    error.value = '此函数名称已存在，请使用其他名称'
    return
  }
  if (
    props.modelValue.length >= 4 &&
    !props.modelValue.some((a) => a.reference === reference.value)
  ) {
    error.value = '每个作业最多选择 4 个 UDF JAR'
    return
  }
  const next = structuredClone(
    props.modelValue.map((a) => ({
      reference: a.reference,
      functions: a.functions.map((f) => ({
        ...f,
        signatures: [...f.signatures],
        methodCalls: [...f.methodCalls],
      })),
    })),
  )
  let artifact = next.find((a) => a.reference === reference.value)
  if (!artifact) {
    artifact = { reference: reference.value, functions: [] }
    next.push(artifact)
  }
  if (artifact.functions.length >= 32) {
    error.value = '每个 JAR 最多选择 32 个函数'
    return
  }
  artifact.functions.push({
    ...fn,
    signatures: [...fn.signatures],
    methodCalls: [...fn.methodCalls],
  })
  emit('update:modelValue', next)
  emit('insert', `CREATE TEMPORARY SYSTEM FUNCTION \`${fn.name}\` AS '${fn.className}';\n`)
}
function remove(reference: string, name: string) {
  emit(
    'update:modelValue',
    props.modelValue
      .map((a) =>
        a.reference === reference
          ? { ...a, functions: a.functions.filter((f) => f.name !== name) }
          : a,
      )
      .filter((a) => a.functions.length),
  )
  note.value = '已移除作业依赖选择；请同时检查 SQL 中的函数注册和引用。运行中的作业不会改变。'
}
</script>
<template>
  <div class="udf-panel">
    <h3>UDF 函数</h3>
    <p class="muted">
      上传或选择 JAR → 识别全部函数 → 添加并插入注册 SQL。依赖随保存、发布和 SQL 验证生效。
    </p>
    <ArtifactPicker
      file-label="UDF JAR 文件"
      v-model="reference"
      category="main"
      :scope-id="scopeId"
      :disabled="disabled || busy"
      @uploaded="uploaded"
      @uploading="emit('uploading', $event)"
    />
    <button class="button" :disabled="disabled || busy || !reference" @click="inspect">
      {{ busy ? '正在识别…' : '识别 JAR 中的函数' }}
    </button>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="note" class="muted">{{ note }}</p>
    <p v-if="note && !candidates.length && !error" class="muted">
      未识别出可实例化的 Flink UDF。请检查继承、public 无参构造及依赖是否完整。
    </p>
    <section v-for="fn in candidates" :key="fn.className" class="udf-card">
      <strong>{{ fn.className }}</strong
      ><small>{{ fn.kind }}</small>
      <pre>{{ fn.signatures.join('\n') }}</pre>
      <label>SQL 函数名<input v-model="fn.name" :disabled="disabled" maxlength="64" /></label>
      <label
        >业务逻辑说明<textarea
          v-model="fn.description"
          :disabled="disabled"
          rows="2"
          maxlength="1000"
          placeholder="供 AI 分析参考，例如：去除手机号中的空格；不改变空值。"
        />
      </label>
      <details v-if="fn.methodCalls.length">
        <summary>静态调用线索</summary>
        <pre>{{ fn.methodCalls.join('\n') }}</pre>
      </details>
      <button class="button primary" :disabled="disabled" @click="add(fn)">
        添加函数并插入 SQL
      </button>
    </section>
    <h4>当前作业函数 · {{ modelValue.flatMap((a) => a.functions).length }}</h4>
    <section v-for="a in modelValue" :key="a.reference" class="udf-card">
      <small>{{ a.reference }}</small>
      <div v-for="fn in a.functions" :key="fn.name">
        <strong>{{ fn.name }}</strong> · {{ fn.kind }}
        <p>{{ fn.className }}</p>
        <p class="muted">{{ fn.description || '未填写业务说明' }}</p>
        <button
          class="text-button"
          :disabled="disabled"
          @click="emit('insert', `\`${fn.name}\`()`)"
        >
          插入调用</button
        ><button
          class="text-button danger"
          :disabled="disabled"
          @click="remove(a.reference, fn.name)"
        >
          移除依赖选择
        </button>
      </div>
    </section>
  </div>
</template>
<style scoped>
.udf-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
  font-size: var(--font-small);
}
.udf-card {
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow-wrap: anywhere;
}
pre {
  margin: 0;
  font-size: var(--font-caption);
  white-space: pre-wrap;
}
small {
  color: var(--color-muted);
}
</style>
