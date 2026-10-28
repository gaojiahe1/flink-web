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
const scrollbarWidth = ref(0)
let merge: MergeView | undefined
let observer: ResizeObserver | undefined
let stopAppearances: (() => void)[] = []
function render() {
  observer?.disconnect()
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
      '.cm-scroller': {
        overflow: 'auto',
        fontFamily: 'var(--font-family-code)',
        lineHeight: '1.8',
      },
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
  const container = merge.dom
  const measure = () => {
    scrollbarWidth.value = container.offsetWidth - container.clientWidth
  }
  observer = new ResizeObserver(measure)
  observer.observe(container)
  measure()
}
onMounted(render)
watch(() => [props.before, props.after, props.language], render)
onBeforeUnmount(() => {
  observer?.disconnect()
  stopAppearances.forEach((stop) => stop())
  merge?.destroy()
})
</script>
<template>
  <div
    class="code-diff"
    :class="{ stacked }"
    :style="{ '--diff-scrollbar-width': scrollbarWidth + 'px' }"
  >
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
  min-height: 0;
  height: 420px;
  display: flex;
  flex-direction: column;
}
.diff-labels {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  padding: 8px var(--diff-scrollbar-width, 0px) 8px 0;
  font-size: var(--font-small);
  flex: none;
  border-bottom: 1px solid var(--line);
  border-inline: 1px solid transparent;
}
.diff-labels > span {
  min-width: 0;
  padding-inline: 10px;
}
.diff-labels > span + span {
  border-left: 1px solid var(--line);
}
.diff-labels em {
  font-style: normal;
  color: var(--color-muted);
  font-size: var(--font-caption);
}
.diff-host {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--line);
}
.diff-host :deep(.cm-mergeView) {
  height: 100%;
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-gutter: stable;
}
.diff-host :deep(.cm-mergeViewEditors) {
  min-height: 100%;
}
.diff-host :deep(.cm-mergeViewEditor) {
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.diff-host :deep(.cm-editor) {
  flex: 1;
}
.diff-host :deep(.cm-scroller) {
  flex: 1;
}
.diff-host :deep(.cm-mergeViewEditor + .cm-mergeViewEditor) {
  border-left: 1px solid var(--line);
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
