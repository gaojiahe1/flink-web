import { EditorView } from '@codemirror/view'
import { Compartment } from '@codemirror/state'
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { tags } from '@lezer/highlight'
import { watch } from 'vue'
import { currentTheme } from '../composables/useTheme'

const appearance = EditorView.theme({
  '&': { color: 'var(--color-text)', backgroundColor: 'var(--color-surface)' },
  '.cm-content': { caretColor: 'var(--color-primary)' },
  '.cm-gutters': {
    color: 'var(--code-gutter)',
    backgroundColor: 'var(--color-surface-soft)',
    borderColor: 'var(--color-border)',
  },
  '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--color-primary)' },
  '.cm-activeLine, .cm-activeLineGutter': { backgroundColor: 'var(--code-active)' },
  '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': {
    backgroundColor: 'var(--code-selection)',
  },
  '.cm-tooltip': {
    color: 'var(--color-text)',
    backgroundColor: 'var(--color-surface)',
    border: '1px solid var(--color-border)',
  },
  '.cm-tooltip-autocomplete > ul > li[aria-selected]': {
    backgroundColor: 'var(--color-primary-soft)',
    color: 'var(--color-text)',
  },
  '.cm-foldPlaceholder': {
    backgroundColor: 'var(--color-surface-soft)',
    color: 'var(--color-muted)',
    borderColor: 'var(--color-border)',
  },
  '.cm-searchMatch': {
    backgroundColor: 'var(--code-search)',
    outline: '1px solid var(--code-search-border)',
  },
  '.cm-searchMatch-selected': {
    backgroundColor: 'var(--code-selection)',
    outline: '1px solid var(--color-primary)',
  },
  '.cm-matchingBracket, .cm-nonmatchingBracket': {
    backgroundColor: 'var(--color-primary-soft)',
    color: 'var(--color-text)',
  },
  '.cm-selectionMatch': { backgroundColor: 'var(--code-selection)' },
})
const highlighting = syntaxHighlighting(
  HighlightStyle.define([
    { tag: tags.keyword, color: 'var(--code-keyword)' },
    { tag: [tags.typeName, tags.propertyName], color: 'var(--code-type)' },
    { tag: tags.string, color: 'var(--code-string)' },
    { tag: [tags.number, tags.bool, tags.null], color: 'var(--code-number)' },
    { tag: tags.comment, color: 'var(--code-comment)', fontStyle: 'italic' },
  ]),
)
// Reconfigure only the mode facet: keep selection, undo history, scroll and documents intact.
export function createEditorAppearance() {
  const mode = new Compartment()
  const modeTheme = () => EditorView.theme({}, { dark: currentTheme.value === 'dark' })
  return {
    extension: [appearance, highlighting, mode.of(modeTheme())],
    attach: (view: EditorView) =>
      watch(currentTheme, () => view.dispatch({ effects: mode.reconfigure(modeTheme()) })),
  }
}
