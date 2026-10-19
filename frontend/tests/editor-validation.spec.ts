import {syntaxTree} from '@codemirror/language'
import {test,expect} from '@playwright/test'
import {EditorState} from '@codemirror/state'
import {CompletionContext} from '@codemirror/autocomplete'
import {connectorCompletion,flinkSqlSupport,formatFlinkSql,sameSqlTokens} from '../src/shared/utils/sqlEditing'
import {parseDeploymentYaml} from '../src/shared/utils/deploymentYaml'

test('格式化强制建表字段和 WITH 参数分行，保留嵌套类型和字符串中的逗号',async()=>{
  const sql="CREATE TEMPORARY TABLE sink (id BIGINT, amount DECIMAL(18, 2), payload STRING) WITH ('connector'='print','print-identifier'='a,b'); SELECT id, COALESCE(amount,0) FROM sink;"
  const formatted=await formatFlinkSql(sql)
  expect(formatted).toMatch(/\(\s*\n\s+id BIGINT,\s*\n\s+amount DECIMAL\(18, 2\),\s*\n\s+payload STRING/)
  expect(formatted).toMatch(/'connector' = 'print',\s*\n\s+'print-identifier' = 'a,b'/)
  expect(sameSqlTokens(sql,formatted)).toBe(true)
  expect(await formatFlinkSql(formatted)).toBe(formatted)
})

test('格式化保留引用标识符、函数、行注释与多表语句的全部 token',async()=>{
  const sql="CREATE TABLE `one` (`a,b` STRING, n DECIMAL(12,2)) WITH ('connector'='print'); -- note , (\nCREATE TABLE two (x STRING, y AS CONCAT(x, ',')) WITH ('connector'='datagen'); INSERT INTO `one` SELECT x, 1 FROM two;"
  const formatted=await formatFlinkSql(sql)
  expect(sameSqlTokens(sql,formatted)).toBe(true)
  expect(formatted).toContain("`a,b` STRING,")
  expect(formatted).toContain("-- note , (")
})

test('部署 spec 拒绝标量及数组，合法对象保留原文',()=>{
  for(const spec of ['true','false','1','0','text','null','[]','[one]'])
    expect(()=>parseDeploymentYaml('kind: FlinkDeployment\nspec: '+spec+'\n')).toThrow('FlinkDeployment')
  const source='kind: FlinkDeployment\n# preserve\nspec:\n  image: test/flink\n  extra: "unchanged"\n'
  expect(parseDeploymentYaml(source).toString()).toBe(source)
})
test('Connector 和已用键忽略注释，保留字符串中的注释符号',()=>{
  for(const comment of ["/* 'connector'='jdbc', 'topic'='ignore' */", "-- 'connector'='jdbc', 'topic'='ignore'\n"]){
    const doc="CREATE TABLE t (id STRING) WITH ("+comment+" 'connector'='kafka', '"
    const result=connectorCompletion(new CompletionContext(EditorState.create({doc}),doc.length,true))
    const labels=result!.options.map(option=>option.label)
    expect(labels).toContain('topic');expect(labels).toContain('properties.bootstrap.servers');expect(labels).not.toContain('url')
  }
  const doc="CREATE TABLE t (id STRING) WITH ('connector'='kafka','topic'='--real/*value*/','"
  const result=connectorCompletion(new CompletionContext(EditorState.create({doc}),doc.length,true))
  expect(result!.options.map(option=>option.label)).not.toContain('topic')
})


test('各 Connector 仅建议对应配置，选项含说明，已使用键不会重复',()=>{
  for(const [connector,key] of [['kafka','properties.bootstrap.servers'],['jdbc','url'],['filesystem','path'],['hbase-2.2','zookeeper.quorum'],['mongodb','uri'],['opensearch','hosts']]){
    const doc="CREATE TABLE t (id STRING) WITH ('connector'='"+connector+"', '"
    const result=connectorCompletion(new CompletionContext(EditorState.create({doc}),doc.length,true))!
    expect(result.options.map(o=>o.label)).toContain(key)
    expect(result.options.every(o=>!!o.info)).toBeTruthy()
    expect(result.options.map(o=>o.label)).not.toContain('connector')
    if(connector!=='kafka')expect(result.options.map(o=>o.label)).not.toContain('properties.bootstrap.servers')
  }
})


test('SQL 基础关键字及 Flink 数据类型实际解析为高亮节点',()=>{
 const doc="CREATE TABLE t(id STRING, value TIMESTAMP_LTZ(3)) WITH ('connector'='kafka'); SELECT * FROM t;"
 const state=EditorState.create({doc,extensions:flinkSqlSupport()})
 const tree=syntaxTree(state)
 expect(tree.resolveInner(doc.indexOf('CREATE')+1).name).toBe('Keyword')
 expect(tree.resolveInner(doc.indexOf('STRING')+1).name).toBe('Type')
 expect(tree.resolveInner(doc.indexOf('TIMESTAMP_LTZ')+1).name).toBe('Type')
 expect(tree.resolveInner(doc.indexOf('SELECT')+1).name).toBe('Keyword')
})
