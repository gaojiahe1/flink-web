import { parseDocument } from 'yaml'

export const podTemplateFields = [
  { key: 'podTemplate', label: '共享 PodTemplate', path: 'spec.podTemplate' },
  { key: 'jmPodTemplate', label: 'JobManager PodTemplate', path: 'spec.jobManager.podTemplate' },
  { key: 'tmPodTemplate', label: 'TaskManager PodTemplate', path: 'spec.taskManager.podTemplate' },
] as const
export type PodTemplateKey = (typeof podTemplateFields)[number]['key']
export type PodTemplateOptions = Record<PodTemplateKey, Record<string, unknown>>
export const emptyPodTemplates = (): PodTemplateOptions => ({
  podTemplate: {},
  jmPodTemplate: {},
  tmPodTemplate: {},
})
export function readPodTemplates(spec: any): PodTemplateOptions {
  const values = [spec?.podTemplate, spec?.jobManager?.podTemplate, spec?.taskManager?.podTemplate]
  return Object.fromEntries(
    podTemplateFields.map((field, index) => {
      const value = values[index] ?? {}
      if (typeof value !== 'object' || Array.isArray(value))
        throw new Error(field.label + ' 必须为 YAML 对象')
      return [field.key, value]
    }),
  ) as PodTemplateOptions
}
export function parsePodTemplate(source: string, label: string): Record<string, unknown> {
  if (!source.trim()) return {}
  const doc = parseDocument(source, { uniqueKeys: true })
  if (doc.errors.length) throw new Error(label + ' 必须为有效 YAML 对象：' + doc.errors[0]!.message)
  const value = doc.toJS()
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error(label + ' 必须为 YAML 对象')
  return value
}
