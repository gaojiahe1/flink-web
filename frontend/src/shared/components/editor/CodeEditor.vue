<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { EditorView, basicSetup } from 'codemirror'
import { keymap } from '@codemirror/view'
import { isolateHistory } from '@codemirror/commands'
import { EditorState, Compartment } from '@codemirror/state'
import { yaml } from '@codemirror/lang-yaml'
import { linter, lintGutter, type Diagnostic } from '@codemirror/lint'
import { parseDocument } from 'yaml'
import { createEditorAppearance } from '../../utils/editorTheme'
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
const appearance = createEditorAppearance()
let stopAppearance: (() => void) | undefined
const languageError = ref('')
const formatError = ref('')
async function formatSql() {
  if (!view || props.readonly || props.language !== 'sql') return
  const original = view.state.doc.toString()
  formatError.value = ''
  try {
    const { formatFlinkSql } = await import('../../utils/sqlEditing')
    const formatted = await formatFlinkSql(original)
    if (!disposed && view && !props.readonly && view.state.doc.toString() === original) {
      view.dispatch({
        changes: { from: 0, to: view.state.doc.length, insert: formatted },
        annotations: isolateHistory.of('full'),
        userEvent: 'input.format',
      })
      view.focus()
    }
  } catch {
    if (!disposed)
      formatError.value = '当前 SQL 无法格式化，请检查未闭合的引号、注释或括号。原文已保留。'
  }
}
function insert(text: string) {
  if (!view || props.readonly) return
  view.dispatch(view.state.replaceSelection(text))
  view.focus()
}
defineExpose({ format: formatSql, insert })
let languageEpoch = 0,
  disposed = false
async function updateLanguage() {
  const epoch = ++languageEpoch
  languageError.value = ''
  issues.value = []
  try {
    const extension =
      props.language === 'sql' ? (await import('../../utils/sqlEditing')).flinkSqlSupport() : yaml()
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
        keymap.of([
          {
            key: 'Mod-Shift-f',
            run: () => {
              if (props.language !== 'sql' || props.readonly) return false
              void formatSql()
              return true
            },
          },
        ]),
        basicSetup,
        appearance.extension,
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
            fontFamily: 'var(--font-family-code)',
            lineHeight: '1.8',
          },
          '.cm-content': { padding: '16px 0' },
          '.cm-gutters': { border: 'none' },
          '&.cm-focused': { outline: 'none' },
        }),
      ],
    }),
  })
  stopAppearance = appearance.attach(view)
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
  stopAppearance?.()
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
    <p v-if="formatError" class="editor-errors" role="alert">{{ formatError }}</p>
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
  font-size: var(--font-small);
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
  outline: 2px solid var(--color-focus);
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
  background: var(--color-primary-soft);
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
  font-size: var(--font-title);
}
:deep(.cm-search.cm-panel [name='close']:hover) {
  color: var(--blue);
  background: var(--color-primary-soft);
}
:deep(.cm-searchMatch) {
  background: var(--code-search);
  outline: 1px solid var(--code-search-border);
}
:deep(.cm-searchMatch-selected) {
  background: var(--code-selection);
  outline: 1px solid var(--blue);
}
</style>
