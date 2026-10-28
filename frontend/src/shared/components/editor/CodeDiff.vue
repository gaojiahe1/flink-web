<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { MergeView } from '@codemirror/merge'
import { EditorState } from '@codemirror/state'
import { EditorView, basicSetup } from 'codemirror'
import { yaml } from '@codemirror/lang-yaml'
import { flinkSqlSupport } from '../../utils/sqlEditing'
import { createEditorAppearance } from '../../utils/editorTheme'

const props = withDefaults(
  defineProps<{
    before: string
    after: string
    language?: 'sql' | 'yaml'
    stacked?: boolean
    beforeLabel?: string
    afterLabel?: string
  }>(),
  {
    language: 'sql',
    beforeLabel: '当前完整内容',
    afterLabel: '建议完整内容',
  },
)
const host = ref<HTMLElement>()
let merge: MergeView | undefined
let stopAppearances: (() => void)[] = []
function render() {
  stopAppearances.forEach((stop) => stop())
  stopAppearances = []
  merge?.destroy()
  if (!host.value) return
  const a = createEditorAppearance(),
    b = createEditorAppearance()
  const extensions = (label: string) => [
    basicSetup,
    EditorState.readOnly.of(true),
    EditorView.editable.of(false),
    props.language === 'sql' ? flinkSqlSupport() : yaml(),
    EditorView.contentAttributes.of({ role: 'textbox', 'aria-label': label, tabindex: '0' }),
    EditorView.theme({
      '&': { height: '100%', fontSize: '12px' },
      '.cm-scroller': { overflow: 'auto', fontFamily: 'Consolas, monospace', lineHeight: '1.8' },
    }),
  ]
  merge = new MergeView({
    parent: host.value,
    a: { doc: props.before, extensions: [...extensions(props.beforeLabel), a.extension] },
    b: { doc: props.after, extensions: [...extensions(props.afterLabel), b.extension] },
    highlightChanges: true,
    gutter: true,
  })
  stopAppearances = [a.attach(merge.a), b.attach(merge.b)]
}
onMounted(render)
watch(() => [props.before, props.after, props.language], render)
onBeforeUnmount(() => {
  stopAppearances.forEach((stop) => stop())
  merge?.destroy()
})
</script>
<template>
  <div class="code-diff" :class="{ stacked }">
    <div class="diff-labels">
      <span>{{ beforeLabel }} · <em>删除 / 修改</em></span
      ><span>{{ afterLabel }} · <em>新增 / 修改</em></span>
    </div>
    <div ref="host" class="diff-host" />
  </div>
</template>
<style scoped>
.code-diff {
  min-width: 0;
  height: 420px;
  display: flex;
  flex-direction: column;
}
.diff-labels {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  padding: 8px 0;
  font-size: 12px;
}
.diff-labels em {
  font-style: normal;
  color: var(--color-muted);
  font-size: 11px;
}
.diff-host {
  flex: 1;
  min-height: 0;
}
.diff-host :deep(.cm-mergeView) {
  height: 100%;
  overflow: auto;
}
.diff-host :deep(.cm-mergeViewEditors) {
  min-height: 100%;
}
.diff-host :deep(.cm-mergeViewEditor) {
  min-width: 0;
}
.diff-host :deep(.cm-changedLine) {
  background: var(--color-warning-soft);
}
.diff-host :deep(.cm-deletedText) {
  background: var(--code-diff-remove-text);
  text-decoration: line-through;
}
.diff-host :deep(.cm-insertedText) {
  background: var(--code-diff-add-text);
}
.diff-host :deep(.cm-merge-a .cm-changedLine) {
  background: var(--code-diff-remove);
}
.diff-host :deep(.cm-merge-b .cm-changedLine) {
  background: var(--code-diff-add);
}
.diff-host :deep(.cm-merge-a .cm-changedText) {
  background: var(--code-diff-remove-text);
}
.diff-host :deep(.cm-merge-b .cm-changedText) {
  background: var(--code-diff-add-text);
}
.stacked .diff-host :deep(.cm-mergeViewEditors) {
  flex-direction: column;
}
.stacked .diff-host :deep(.cm-mergeViewEditors) {
  height: 100%;
}
.stacked .diff-host :deep(.cm-mergeViewEditor) {
  flex: 1;
  min-height: 0;
}
</style>
