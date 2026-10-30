<script lang="ts">
import { defineComponent, h, type VNodeChild } from 'vue'
import { highlightCode } from '../../../shared/utils/codeHighlight'

// 使用文本节点渲染，不执行模型给出的HTML，也不接受脚本链接。
function inline(text: string): VNodeChild[] {
  return text.split(/(\*\*[^*]+\*\*|`[^`]+`|\[[^\]]+\]\([^\s)]+\))/g).map((part) => {
    if (part.startsWith('**') && part.endsWith('**')) return h('strong', part.slice(2, -2))
    if (part.startsWith('`') && part.endsWith('`')) return h('code', part.slice(1, -1))
    const link = part.match(/^\[([^\]]+)\]\(([^)]+)\)$/)
    if (link && /^https?:\/\//i.test(link[2]!))
      return h('a', { href: link[2], target: '_blank', rel: 'noopener noreferrer' }, link[1])
    return part
  })
}
function render(text: string): VNodeChild[] {
  const lines = text.replace(/\r/g, '').split('\n'),
    result: VNodeChild[] = []
  for (let i = 0; i < lines.length;) {
    const line = lines[i]!
    if (!line.trim()) {
      i++
      continue
    }
    if (/^\s*```/.test(line)) {
      const content: string[] = []
      const language =
        line
          .replace(/^\s*```/, '')
          .trim()
          .split(/\s+/)[0] || 'text'
      i++
      while (i < lines.length && !/^\s*```/.test(lines[i]!)) content.push(lines[i++]!)
      i++
      result.push(
        h('div', { class: 'ai-code-block' }, [
          h('div', { class: 'ai-code-language' }, language.toUpperCase()),
          h('pre', h('code', highlightCode(content.join('\n'), language))),
        ]),
      )
      continue
    }
    const heading = line.match(/^\s*(#{1,6})\s+(.+)/)
    if (heading) {
      result.push(h('h' + Math.min(heading[1]!.length + 2, 6), inline(heading[2]!)))
      i++
      continue
    }
    if (/^\s*([-*+]\s+|\d+[.)]\s+)/.test(line)) {
      const ordered = /^\s*\d/.test(line),
        items: VNodeChild[] = []
      while (
        i < lines.length &&
        (ordered ? /^\s*\d+[.)]\s+/.test(lines[i]!) : /^\s*[-*+]\s+/.test(lines[i]!))
      )
        items.push(h('li', inline(lines[i++]!.replace(/^\s*(?:[-*+]|\d+[.)])\s+/, ''))))
      result.push(h(ordered ? 'ol' : 'ul', items))
      continue
    }
    if (/^\s*>/.test(line)) {
      result.push(h('blockquote', inline(line.replace(/^\s*>\s?/, ''))))
      i++
      continue
    }
    if (line.includes('|') && /^\s*\|?\s*:?-+/.test(lines[i + 1] || '')) {
      const cells = (s: string) => s.replace(/^\s*\||\|\s*$/g, '').split('|')
      const header = cells(line)
      i += 2
      const rows: VNodeChild[] = []
      while (i < lines.length && lines[i]!.includes('|'))
        rows.push(
          h(
            'tr',
            cells(lines[i++]!).map((c) => h('td', inline(c.trim()))),
          ),
        )
      result.push(
        h(
          'div',
          { class: 'ai-rich-table' },
          h('table', [
            h(
              'thead',
              h(
                'tr',
                header.map((c) => h('th', inline(c.trim()))),
              ),
            ),
            h('tbody', rows),
          ]),
        ),
      )
      continue
    }
    const paragraph = [line]
    i++
    while (
      i < lines.length &&
      lines[i]!.trim() &&
      !/^\s*(#|```|[-*+]\s|\d+[.)]\s|>)/.test(lines[i]!)
    )
      paragraph.push(lines[i++]!)
    result.push(h('p', inline(paragraph.join('\n'))))
  }
  return result
}
export default defineComponent({
  props: { text: { type: String, default: '' } },
  setup: (props) => () => h('div', { class: 'ai-rich-text' }, render(props.text)),
})
</script>
<style>
.ai-rich-text {
  font-size: var(--font-body);
  line-height: 1.75;
  overflow-wrap: anywhere;
  min-width: 0;
}
.ai-rich-text p {
  white-space: pre-wrap;
  margin: 0.45em 0;
}
.ai-rich-text h3,
.ai-rich-text h4,
.ai-rich-text h5,
.ai-rich-text h6 {
  font-size: var(--font-section);
  font-weight: 600;
  margin: 0.8em 0 0.3em;
}
.ai-rich-text ul,
.ai-rich-text ol {
  padding-left: 1.7em;
  margin: 0.4em 0;
}
.ai-rich-text li {
  margin: 0.2em 0;
}
.ai-rich-text code {
  font-family: var(--font-family-code);
  color: var(--color-text);
  background: var(--color-primary-soft);
  border: 1px solid var(--color-border);
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 0.95em;
}
.ai-rich-text pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--color-background);
  padding: 12px;
  border-radius: 6px;
}
.ai-rich-text pre code {
  font: inherit;
  padding: 0;
  border: 0;
  background: none;
}
.ai-rich-text h5,
.ai-rich-text h6 {
  font-size: var(--font-body);
}
.ai-rich-text blockquote {
  border-left: 3px solid var(--line);
  margin: 0.6em 0;
  padding-left: 12px;
  color: var(--muted);
}
.ai-rich-table {
  max-width: 100%;
  overflow: auto;
}
.ai-rich-table table {
  border-collapse: collapse;
  font-size: inherit;
}
.ai-rich-table th,
.ai-rich-table td {
  border: 1px solid var(--line);
  padding: 6px 10px;
}
.ai-rich-text .ai-code-block {
  margin: 12px 0;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  overflow: hidden;
  background: var(--color-surface-soft);
}
.ai-rich-text .ai-code-language {
  padding: 5px 12px;
  border-bottom: 1px solid var(--color-border);
  color: var(--color-muted);
  font: 11px var(--font-family-code);
}
.ai-rich-text .ai-code-block pre,
.harness-workspace .ai-rich-text .ai-code-block pre {
  margin: 0;
  max-height: 380px;
  padding: 12px;
  color: var(--color-text);
  background: var(--color-surface-soft);
  font: 12px/1.75 var(--font-family-code);
  white-space: pre;
  overflow-wrap: normal;
  overflow: auto;
  border-radius: 0;
}
.ai-rich-text .ai-code-keyword {
  color: var(--code-keyword);
  font-weight: 600;
}
.ai-rich-text .ai-code-type {
  color: var(--code-type);
}
.ai-rich-text .ai-code-string {
  color: var(--code-string);
}
.ai-rich-text .ai-code-number {
  color: var(--code-number);
}
.ai-rich-text .ai-code-comment {
  color: var(--color-muted);
  font-style: italic;
}
</style>
