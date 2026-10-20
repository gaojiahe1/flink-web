import { parseDocument } from 'yaml'
import forge from 'node-forge'

/** 仅解码平台 Runner 的显式参数协议，不执行 SQL，也不猜测旧文件协议。 */
export function decodeDeploymentSql(source: string) {
  const doc = parseDocument(source)
  if (doc.errors.length) throw new Error('部署 YAML 无法解析')
  const value = doc.toJS(),
    args = value?.spec?.job?.args
  if (value?.kind !== 'FlinkDeployment' || !Array.isArray(args) || !args.length || args.length % 2)
    throw new Error('部署未包含平台 SQL 参数，无法核对')
  const statements: string[] = []
  for (let index = 0; index < args.length; index += 2) {
    if (args[index] !== '--statement' || typeof args[index + 1] !== 'string')
      throw new Error('不是平台 --statement 参数协议，无法核对')
    try {
      const bytes = Uint8Array.from(atob(args[index + 1]), (c) => c.charCodeAt(0))
      statements.push(new TextDecoder('utf-8', { fatal: true }).decode(bytes))
    } catch {
      throw new Error('SQL 参数的 Base64 或 UTF-8 内容损坏')
    }
  }
  const annotations = value.metadata?.annotations || {}
  return {
    sql: statements.map((s) => s.trim().replace(/;+$/, '') + ';').join('\n\n'),
    fingerprint: forge.md.sha256
      .create()
      .update(JSON.stringify(statements), 'utf8')
      .digest()
      .toHex(),
    sourceHash: String(annotations['flink-web/sql-sha256'] || ''),
    revision: String(annotations['flink-web/sql-revision'] || '旧版本未记录'),
  }
}
