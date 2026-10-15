import {test,expect} from '@playwright/test'
import {EditorState} from '@codemirror/state'
import {CompletionContext} from '@codemirror/autocomplete'
import {connectorCompletion} from '../src/shared/utils/sqlEditing'
import {parseDeploymentYaml} from '../src/shared/utils/deploymentYaml'

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
