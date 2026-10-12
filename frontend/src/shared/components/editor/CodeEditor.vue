<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { EditorView, basicSetup } from 'codemirror'
import { EditorState, Compartment } from '@codemirror/state'
import { yaml } from '@codemirror/lang-yaml'
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
const language = new Compartment()
const languageError = ref('')
let languageEpoch = 0,
  disposed = false
async function updateLanguage() {
  const epoch = ++languageEpoch
  languageError.value = ''
  issues.value = []
  try {
    const extension =
      props.language === 'sql' ? (await import('@codemirror/lang-sql')).sql() : yaml()
    if (!disposed && epoch === languageEpoch)
      view?.dispatch({ effects: language.reconfigure(extension) })
  } catch {
    if (!disposed && epoch === languageEpoch)
      languageError.value = '语法高亮加载失败，内容仍可编辑；请刷新页面重试。'
  }
}
let view: EditorView | undefined
onMounted(() => {
  view = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.modelValue,
      extensions: [
        basicSetup,
        language.of(props.language === 'yaml' ? yaml() : []),
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
  void updateLanguage()
})
watch(
  () => props.language,
  () => {
    void updateLanguage()
  },
)
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
onBeforeUnmount(() => {
  disposed = true
  view?.destroy()
})
</script>
<template>
  <div
    class="code-editor"
    :class="{ 'field-invalid': issues.length > 0 }"
    :aria-invalid="issues.length > 0"
  >
    <div ref="host" class="code-editor-host"></div>
    <p v-if="languageError" class="muted" role="status">{{ languageError }}</p>
    <div v-if="issues.length" class="editor-errors" role="alert">{{ issues[0] }}</div>
  </div>
</template>
<style scoped>
:deep(.cm-panels) {
  background: var(--color-background);
  color: var(--text);
}
:deep(.cm-panels-bottom) {
  border-top: 1px solid var(--line);
}
:deep(.cm-search.cm-panel) {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 10px 36px 10px 12px;
  font-size: 12px;
  line-height: 1.5;
  font-family: inherit;
  position: relative;
}
:deep(.cm-search.cm-panel > *) {
  margin: 0;
}
:deep(.cm-search .cm-textfield) {
  flex: 1 1 150px;
  width: 150px;
  min-width: 0;
  height: var(--control-height);
  padding: 5px 9px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--text);
  font: inherit;
}
:deep(.cm-search .cm-textfield:focus) {
  outline: 2px solid #2468ee26;
  border-color: var(--blue);
}
:deep(.cm-search .cm-button) {
  height: var(--control-height);
  padding: 4px 10px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--text);
  font: inherit;
  text-transform: none;
  cursor: pointer;
}
:deep(.cm-search .cm-button:hover) {
  border-color: var(--blue);
  color: var(--blue);
  background: #2468ee08;
}
:deep(.cm-search label) {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
  font-weight: 400;
}
:deep(.cm-search input[type='checkbox']) {
  width: 14px;
  height: 14px;
  margin: 0;
  accent-color: var(--blue);
}
:deep(.cm-search.cm-panel [name='close']) {
  position: absolute;
  top: 10px;
  right: 8px;
  width: 24px;
  height: var(--control-height);
  padding: 0;
  color: var(--muted);
  border-radius: var(--radius-sm);
  font-size: 18px;
}
:deep(.cm-search.cm-panel [name='close']:hover) {
  color: var(--blue);
  background: #2468ee0a;
}
:deep(.cm-searchMatch) {
  background: #fef3c7;
  outline: 1px solid #fbbf24;
}
:deep(.cm-searchMatch-selected) {
  background: #bfdbfe;
  outline: 1px solid var(--blue);
}
</style>
