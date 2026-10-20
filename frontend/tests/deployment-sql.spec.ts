import { test, expect } from '@playwright/test'
import { stringify } from 'yaml'
import { decodeDeploymentSql } from '../src/modules/sql/utils/deploymentSql'
test('部署 SQL 按 UTF-8 解码，参数顺序与源修订保留，损坏内容明确拒绝', () => {
  const statements = ["CREATE TABLE 输出表 (字段 STRING) WITH ('connector'='print')", "INSERT INTO 输出表 VALUES ('中文;逗号,')"]
  const yaml = stringify({ kind: 'FlinkDeployment', metadata: { annotations: { 'flink-web/sql-revision': '7', 'flink-web/sql-sha256': 'source-hash' } }, spec: { job: { args: statements.flatMap(s => ['--statement', Buffer.from(s).toString('base64')]) } } })
  const decoded = decodeDeploymentSql(yaml)
  expect(decoded.sql).toContain("VALUES ('中文;逗号,')")
  expect(decoded.revision).toBe('7'); expect(decoded.sourceHash).toBe('source-hash')
  expect(decoded.fingerprint).toHaveLength(64)
  expect(() => decodeDeploymentSql("kind: FlinkDeployment\nspec:\n  job:\n    args: [--statement, '!!']")).toThrow('Base64')
  expect(() => decodeDeploymentSql("kind: FlinkDeployment\nspec:\n  job:\n    args: [--statement, /w==]")).toThrow('UTF-8')
  expect(() => decodeDeploymentSql("kind: FlinkDeployment\nspec:\n  job:\n    args: [--sql-file, /tmp/old.sql]")).toThrow('协议')
})
