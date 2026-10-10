<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { EditorState } from '@codemirror/state'
import { EditorView, basicSetup } from 'codemirror'
import { Decoration, MatchDecorator, ViewPlugin, type ViewUpdate } from '@codemirror/view'
import { Copy } from '@lucide/vue'
import { copyText } from '../../utils/clipboard'

const props = defineProps<{ text: string }>()
const host = ref<HTMLElement>()
const copied = ref('')
let view: EditorView | undefined
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
        EditorView.contentAttributes.of({ 'aria-label': '容器日志', role: 'textbox' }),
        highlight,
        EditorView.theme({
          '&': { height: '100%', fontSize: '12px' },
          '.cm-scroller': {
            overflow: 'auto',
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
})
watch(
  () => props.text,
  (text) => {
    copied.value = ''
    view?.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: text } })
  },
)
onBeforeUnmount(() => view?.destroy())
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
  <section class="log-viewer">
    <div class="log-viewer-toolbar">
      <span class="muted"
        >时间戳 · <span class="log-legend-error">ERROR</span> ·
        <span class="log-legend-warn">WARN</span> · INFO</span
      >
      <span v-if="copied" role="status">{{ copied }}</span>
      <button class="button small" @click="copy"><Copy :size="14" />复制日志</button>
    </div>
    <div ref="host" class="log-viewer-content"></div>
  </section>
</template>
<style scoped>
.log-viewer {
  min-width: 0;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--color-surface);
}
.log-viewer-toolbar {
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
  height: min(52dvh, 560px);
  min-height: 220px;
}
.log-legend-error {
  color: var(--color-danger);
}
.log-legend-warn {
  color: var(--color-warning);
}
</style>
