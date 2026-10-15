import { parseDocument } from 'yaml'
import { readPodTemplates } from './podTemplates'

/** 在操作前检查原文；转换失败时由调用方保留编辑器内容。 */
export function parseDeploymentYaml(source: string) {
  if (!source.trim() || source.length > 200000) throw new Error('YAML 不能为空且长度不得超过 200KB')
  const document = parseDocument(source, { uniqueKeys: true })
  if (document.errors.length) {
    const error = document.errors[0]!
    const position = error.linePos?.[0]
    throw new Error(
      'YAML 语法错误' + (position ? `：第 ${position.line} 行，第 ${position.col} 列` : ''),
    )
  }
  const value = document.toJS({ maxAliasCount: 0 })
  if (
    !value ||
    value.kind !== 'FlinkDeployment' ||
    !value.spec ||
    typeof value.spec !== 'object' ||
    Array.isArray(value.spec)
  )
    throw new Error('请填写完整的 FlinkDeployment YAML')
  readPodTemplates(value.spec)
  return document
}
