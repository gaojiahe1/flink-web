<script setup lang="ts">
import HelpTip from '../form/HelpTip.vue'
import { clearValidationErrors } from '../../utils/fieldValidation'
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { EditorView, basicSetup } from 'codemirror'
import { EditorState, Compartment } from '@codemirror/state'
import { autocompletion, type CompletionContext } from '@codemirror/autocomplete'
import { linter, lintGutter } from '@codemirror/lint'
import { flinkConfigHints, formatFlinkConfig, parseFlinkConfig } from '../../utils/flinkConfig'
const props = withDefaults(
  defineProps<{ modelValue: Record<string, string>; readonly?: boolean }>(),
  { readonly: false },
)
const emit = defineEmits<{ 'update:modelValue': [Record<string, string>]; valid: [boolean] }>()
const host = ref<HTMLElement>(),
  error = ref(''),
  editability = new Compartment()
let view: EditorView | undefined,
  lastEmitted = '',
  syncing = false
function complete(context: CompletionContext) {
  const line = context.state.doc.lineAt(context.pos),
    before = line.text.slice(0, context.pos - line.from)
  if (before.includes(':')) return null
  const word = context.matchBefore(/[\w.-]*/)
  if (!word || (!word.text && !context.explicit)) return null
  return {
    from: word.from,
    options: flinkConfigHints.map(([key, info, example]) => ({
      label: key,
      type: 'property',
      detail: info,
      info: '示例：' + example,
      apply: key + ': ',
    })),
  }
}
onMounted(() => {
  view = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: formatFlinkConfig(props.modelValue),
      extensions: [
        basicSetup,
        lintGutter(),
        autocompletion({ override: [complete] }),
        editability.of([
          EditorState.readOnly.of(props.readonly),
          EditorView.editable.of(!props.readonly),
        ]),
        EditorView.contentAttributes.of({
          'aria-label': 'Flink 配置文本域',
          role: 'textbox',
          'aria-multiline': 'true',
        }),
        linter(
          (editor) =>
            parseFlinkConfig(editor.state.doc.toString()).issues.map((issue) => {
              const line = editor.state.doc.line(issue.line)
              return {
                from: line.from,
                to: line.to,
                severity: 'error' as const,
                message: issue.message,
              }
            }),
          { delay: 150 },
        ),
        EditorView.updateListener.of((update) => {
          if (!update.docChanged || syncing) return
          const result = parseFlinkConfig(update.state.doc.toString())
          error.value = result.issues.map((e) => '第 ' + e.line + ' 行：' + e.message).join('；')
          emit('valid', !error.value)
          if (!error.value) {
            lastEmitted = JSON.stringify(result.values)
            emit('update:modelValue', result.values)
          }
        }),
        EditorView.theme({
          '&': { fontSize: '12px' },
          '.cm-scroller': {
            fontFamily: 'Consolas, monospace',
            minHeight: '160px',
            maxHeight: '300px',
            overflow: 'auto',
          },
          '.cm-content': { padding: '10px 0' },
          '.cm-gutters': { background: '#f8fafc' },
          '&.cm-focused': { outline: 'none' },
        }),
      ],
    }),
  })
  emit('valid', true)
})
watch(
  () => props.modelValue,
  (value) => {
    if (host.value?.parentElement) clearValidationErrors(host.value.parentElement)
    if (!view || JSON.stringify(value) === lastEmitted) return
    const text = formatFlinkConfig(value)
    if (text !== view.state.doc.toString()) {
      syncing = true
      try {
        view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: text } })
        error.value = ''
        emit('valid', true)
      } finally {
        syncing = false
      }
    }
  },
  { deep: true },
)
watch(
  () => props.readonly,
  (value) =>
    view?.dispatch({
      effects: editability.reconfigure([
        EditorState.readOnly.of(value),
        EditorView.editable.of(!value),
      ]),
    }),
)
onBeforeUnmount(() => view?.destroy())
</script>
<template>
  <div class="flink-config-editor" :class="{ 'field-invalid': !!error }" :aria-invalid="!!error">
    <div ref="host" class="config-editor-host"></div>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
    <HelpTip
      text="每行 key:value；输入配置键自动提示，Ctrl+Space 展开。支持 # 注释和含冒号的值；配置需与运行版本匹配。"
    /><a
      class="text-button"
      href="https://nightlies.apache.org/flink/flink-docs-release-2.3/zh/docs/deployment/config/"
      target="_blank"
      rel="noopener"
      >Flink 2.3 配置参考 ↗</a
    >
  </div>
</template>
