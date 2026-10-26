<script setup lang="ts">
import { nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { EditorState } from '@codemirror/state'
import { EditorView, basicSetup } from 'codemirror'
import { Decoration, MatchDecorator, ViewPlugin, type ViewUpdate } from '@codemirror/view'
import { Copy } from '@lucide/vue'
import { copyText } from '../../utils/clipboard'

const props = defineProps<{ text: string; fill?: boolean }>()
const host = ref<HTMLElement>()
const horizontal = ref<HTMLElement>(),
  horizontalOverflow = ref(0)
const copied = ref('')
let view: EditorView | undefined
let observer: ResizeObserver | undefined,
  measureFrame = 0
function measureScroll() {
  if (measureFrame) return
  measureFrame = requestAnimationFrame(() => {
    measureFrame = 0
    if (!view) return
    horizontalOverflow.value = Math.max(0, view.scrollDOM.scrollWidth - view.scrollDOM.clientWidth)
    void nextTick(() => {
      if (view && horizontal.value) horizontal.value.scrollLeft = view.scrollDOM.scrollLeft
    })
  })
}
function scrollLog() {
  if (view && horizontal.value) view.scrollDOM.scrollLeft = horizontal.value.scrollLeft
}
function syncScroll() {
  if (view && horizontal.value) horizontal.value.scrollLeft = view.scrollDOM.scrollLeft
  measureScroll()
}
const matcher = new MatchDecorator({
  regexp:
    /\b(?:ERROR|FATAL|WARN(?:ING)?|INFO|DEBUG|TRACE)\b|\d{4}-\d{2}-\d{2}[T ][\d:.]+(?:Z|[+-]\d{2}:?\d{2})?/gi,
  decoration: (match) => {
    const token = match[0].toUpperCase()
    const kind = /^(ERROR|FATAL)$/.test(token)
      ? 'error'
      : /^WARN/.test(token)
        ? 'warn'
        : token === 'INFO'
          ? 'info'
          : 'muted'
    return Decoration.mark({ class: 'log-token-' + kind })
  },
})
// 只渲染可见行，避免一万行日志生成大量 DOM；正文始终作为文本显示。
const highlight = ViewPlugin.fromClass(
  class {
    decorations
    constructor(editor: EditorView) {
      this.decorations = matcher.createDeco(editor)
    }
    update(update: ViewUpdate) {
      this.decorations = matcher.updateDeco(update, this.decorations)
    }
  },
  { decorations: (plugin) => plugin.decorations },
)
onMounted(() => {
  view = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.text,
      extensions: [
        basicSetup,
        EditorState.readOnly.of(true),
        EditorView.editable.of(false),
        EditorView.contentAttributes.of({
          'aria-label': '容器日志',
          role: 'textbox',
          tabindex: '0',
        }),
        EditorView.updateListener.of((update) => {
          if (update.docChanged || update.geometryChanged || update.viewportChanged) measureScroll()
        }),
        highlight,
        EditorView.theme({
          '&': { height: '100%', fontSize: '12px' },
          '.cm-scroller': {
            overflow: 'auto',
            overflowX: 'hidden',
            fontFamily: 'Consolas, monospace',
            lineHeight: '1.8',
          },
          '.cm-gutters': { background: '#f8fafc', color: '#94a3b8', border: 'none' },
          '&.cm-focused': { outline: 'none' },
          '.log-token-error': { color: '#c74646', fontWeight: '700' },
          '.log-token-warn': { color: '#b7781f', fontWeight: '700' },
          '.log-token-info': { color: '#2468ee', fontWeight: '600' },
          '.log-token-muted': { color: '#64748b' },
        }),
      ],
    }),
  })
  view.scrollDOM.addEventListener('scroll', syncScroll, { passive: true })
  observer = new ResizeObserver(measureScroll)
  if (host.value) observer.observe(host.value)
  measureScroll()
})
watch(
  () => props.text,
  (text) => {
    copied.value = ''
    view?.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: text } })
  },
)
onBeforeUnmount(() => {
  observer?.disconnect()
  cancelAnimationFrame(measureFrame)
  view?.scrollDOM.removeEventListener('scroll', syncScroll)
  view?.destroy()
  view = undefined
})
async function copy() {
  try {
    await copyText(props.text, host.value?.closest('dialog') ?? undefined)
    copied.value = '已复制'
  } catch (e) {
    copied.value = (e as Error).message
  }
}
</script>
<template>
  <section class="log-viewer" :class="{ 'log-viewer-fill': fill }">
    <div class="log-viewer-toolbar">
      <span class="muted"
        >时间戳 · <span class="log-legend-error">ERROR</span> ·
        <span class="log-legend-warn">WARN</span> · INFO</span
      >
      <span v-if="copied" role="status">{{ copied }}</span>
      <button class="button small" @click="copy"><Copy :size="14" />复制日志</button>
    </div>
    <div ref="host" class="log-viewer-content"></div>
    <div
      v-show="horizontalOverflow > 0"
      ref="horizontal"
      class="log-horizontal-scroll"
      role="region"
      aria-label="日志横向滚动"
      tabindex="0"
      @scroll="scrollLog"
    >
      <div :style="{ width: 'calc(100% + ' + horizontalOverflow + 'px)' }" />
    </div>
  </section>
</template>
<style scoped>
.log-viewer {
  display: flex;
  flex-direction: column;
  max-width: 100%;
  min-width: 0;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--color-surface);
}
.log-viewer-toolbar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--line);
  font-size: 12px;
}
.log-viewer-toolbar button {
  margin-left: auto;
}
.log-viewer-content {
  min-width: 0;
  width: 100%;
  height: min(52dvh, 560px);
  min-height: 220px;
}
.log-viewer-fill .log-viewer-content {
  flex: 1;
  min-height: 0;
  height: auto;
}
.log-viewer-fill .log-viewer-toolbar {
  flex-wrap: nowrap;
}
.log-viewer-fill .log-viewer-toolbar > span:first-child {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.log-viewer-toolbar button {
  flex-shrink: 0;
}
.log-horizontal-scroll {
  flex: none;
  height: 16px;
  overflow-x: scroll;
  overflow-y: hidden;
  scrollbar-width: auto;
  scrollbar-color: #8d9cb0 #edf1f6;
  border-top: 1px solid var(--line);
}
.log-horizontal-scroll > div {
  height: 1px;
}
.log-horizontal-scroll::-webkit-scrollbar {
  height: 12px;
}
.log-horizontal-scroll::-webkit-scrollbar-track {
  background: #edf1f6;
}
.log-horizontal-scroll::-webkit-scrollbar-thumb {
  border-radius: 6px;
  background: #8d9cb0;
  border: 2px solid #edf1f6;
}
.log-legend-error {
  color: var(--color-danger);
}
.log-legend-warn {
  color: var(--color-warning);
}
</style>
