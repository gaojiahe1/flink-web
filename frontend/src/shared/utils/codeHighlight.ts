import { h, type VNodeChild } from 'vue'
import { highlightTree, tagHighlighter, tags } from '@lezer/highlight'
import { yamlLanguage } from '@codemirror/lang-yaml'
import { flinkDialect } from './sqlEditing'

const colors = tagHighlighter([
  { tag: tags.keyword, class: 'ai-code-keyword' },
  { tag: [tags.typeName, tags.propertyName], class: 'ai-code-type' },
  { tag: tags.string, class: 'ai-code-string' },
  { tag: [tags.number, tags.bool, tags.null], class: 'ai-code-number' },
  { tag: tags.comment, class: 'ai-code-comment' },
])

// 只生成文本节点和带固定类名的 span；模型内容始终不会成为 HTML。
export function highlightCode(text: string, language: string): VNodeChild[] {
  const dialect = language.toLowerCase()
  const parser = /^(sql|flinksql|flink)$/.test(dialect)
    ? flinkDialect.language.parser
    : /^(yaml|yml|json)$/.test(dialect)
      ? yamlLanguage.parser
      : undefined
  if (!parser || text.length > 64000) return [text]
  const output: VNodeChild[] = []
  let offset = 0
  highlightTree(parser.parse(text), colors, (from, to, classes) => {
    if (from > offset) output.push(text.slice(offset, from))
    output.push(h('span', { class: classes }, text.slice(from, to)))
    offset = to
  })
  if (offset < text.length) output.push(text.slice(offset))
  return output
}
