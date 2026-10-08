export type CatalogModule = 'templates' | 'images' | 'configs' | 'users' | 'audit'
export interface CatalogField {
  key: string
  label: string
  help: string
  type?: string
  choices?: string[]
}
export const commonFields: CatalogField[] = [
  { key: 'name', label: '显示名称', help: '页面中显示的名称。' },
  { key: 'description', label: '描述', help: '说明用途或使用约束。' },
]
