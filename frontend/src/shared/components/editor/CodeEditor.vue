<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { EditorView, basicSetup } from 'codemirror'
import { EditorState, Compartment } from '@codemirror/state'
import { yaml } from '@codemirror/lang-yaml'
import { sql } from '@codemirror/lang-sql'
import { linter, lintGutter, type Diagnostic } from '@codemirror/lint'
import { parseDocument } from 'yaml'
const props = withDefaults(
  defineProps<{
    modelValue: string
    language?: 'yaml' | 'sql'
    readonly?: boolean
    label?: string
  }>(),
  { language: 'yaml', readonly: false, label: 'YAML 编辑器' },
)
const emit = defineEmits<{ 'update:modelValue': [string] }>()
const host = ref<HTMLElement>(),
  issues = ref<string[]>([])
const editability = new Compartment()
let view: EditorView | undefined
onMounted(() => {
  view = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.modelValue,
      extensions: [
        basicSetup,
        props.language === 'sql' ? sql() : yaml(),
        lintGutter(),
        editability.of([
          EditorState.readOnly.of(props.readonly),
          EditorView.editable.of(!props.readonly),
        ]),
        EditorView.contentAttributes.of({
          'aria-label': props.label,
          role: 'textbox',
          'aria-multiline': 'true',
        }),
        linter(
          (editor) => {
            if (props.language !== 'yaml') return []
            const text = editor.state.doc.toString()
            const errors = parseDocument(text, { uniqueKeys: true }).errors
            issues.value = errors.map((e) => e.message)
            return errors.map(
              (e) =>
                ({
                  from: Math.min(e.pos[0], text.length),
                  to: Math.min(Math.max(e.pos[1], e.pos[0] + 1), text.length),
                  severity: 'error',
                  message: e.message,
                }) as Diagnostic,
            )
          },
          { delay: 250 },
        ),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) emit('update:modelValue', update.state.doc.toString())
        }),
        EditorView.theme({
          '&': { height: '100%', fontSize: '13px' },
          '.cm-scroller': {
            overflow: 'auto',
            fontFamily: 'Consolas, monospace',
            lineHeight: '1.8',
          },
          '.cm-content': { padding: '16px 0' },
          '.cm-gutters': { backgroundColor: '#f8fafc', color: '#94a3b8', border: 'none' },
          '&.cm-focused': { outline: 'none' },
        }),
      ],
    }),
  })
})
watch(
  () => props.modelValue,
  (value) => {
    if (view && value !== view.state.doc.toString())
      view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } })
  },
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
  <div
    class="code-editor"
    :class="{ 'field-invalid': issues.length > 0 }"
    :aria-invalid="issues.length > 0"
  >
    <div ref="host" class="code-editor-host"></div>
    <div v-if="issues.length" class="editor-errors" role="alert">{{ issues[0] }}</div>
  </div>
</template>
