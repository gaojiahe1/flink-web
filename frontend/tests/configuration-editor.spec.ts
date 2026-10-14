import { test, expect } from '@playwright/test'
import { effectScope, reactive } from 'vue'
import { useDeploymentConfigurationEditor } from '../src/shared/composables/useDeploymentConfigurationEditor'

const source = 'kind: FlinkDeployment\nmetadata:\n  annotations:\n    extra: preserve\nspec:\n  image: registry.test/flink:1.20.5\n  extra: preserve\n'
type Runtime = { baseYaml: string; deploymentYaml: string; cpu: number }
function fixture() {
  const runtime = reactive<Runtime>({ baseYaml: '', deploymentYaml: '', cpu: 1 })
  const scope = effectScope()
  let requests = 0, signal: AbortSignal | undefined
  let convert = async () => ({ yaml: source, runtime: { ...runtime }, formIssue: '' })
  const editor = scope.run(() => useDeploymentConfigurationEditor<Runtime>({
    snapshot: () => runtime,
    payload: () => ({ runtime: { ...runtime } }),
    convert: (_value, current) => { requests++; signal = current; return convert() },
    applyRuntime: value => Object.assign(runtime, value),
  }))!
  editor.reset(runtime)
  editor.markSaved()
  return { runtime, editor, scope, requests: () => requests, signal: () => signal, convert: (value: typeof convert) => { convert = value } }
}
function deferred<T>() {
  let resolve!: (result: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

test('共享配置编辑器无损往返并统一原文、有效性和脏状态', async () => {
  const f = fixture()
  try {
    expect(f.editor.dirty.value).toBe(false)
    await f.editor.switchTo('yaml')
    expect(f.editor.yaml.value).toBe(source)
    expect(f.editor.dirty.value).toBe(true)
    await f.editor.switchTo('form')
    expect(f.runtime.baseYaml).toBe(source)
    expect(f.runtime.deploymentYaml).toBe('')
    f.editor.markSaved()
    expect(f.editor.dirty.value).toBe(false)
    f.editor.configValid.value = false
    expect(() => f.editor.validate()).toThrow('Flink 配置')
    f.editor.configValid.value = true
    f.editor.podValid.value = false
    expect(() => f.editor.validate()).toThrow('PodTemplate')
  } finally { f.scope.stop() }
})

test('无效原文、回填限制及坏响应均保留当前内容和模式', async () => {
  const f = fixture()
  try {
    await f.editor.switchTo('yaml')
    f.editor.yaml.value = 'spec: [invalid'
    await expect(f.editor.switchTo('form')).rejects.toThrow('YAML')
    expect(f.requests()).toBe(1)
    expect(f.editor.yaml.value).toBe('spec: [invalid')
    f.editor.yaml.value = source
    f.convert(async () => ({ yaml: source, runtime: { ...f.runtime, cpu: 2 }, formIssue: '资源省略不能回填' }))
    await expect(f.editor.switchTo('form')).rejects.toThrow('资源省略')
    expect(f.runtime.cpu).toBe(1)
    expect(f.editor.mode.value).toBe('yaml')
    f.convert(async () => ({ yaml: 'spec: [invalid', runtime: { ...f.runtime, cpu: 2 }, formIssue: '' }))
    await expect(f.editor.switchTo('form')).rejects.toThrow('YAML')
    expect(f.runtime.cpu).toBe(1)
    expect(f.editor.yaml.value).toBe(source)
  } finally { f.scope.stop() }
})

test('转换期间编辑后还原也视为过期请求，不覆盖新编辑状态', async () => {
  const f = fixture(), response = deferred<{ yaml: string; runtime: Runtime; formIssue: string }>()
  try {
    f.convert(() => response.promise)
    const pending = f.editor.switchTo('yaml')
    f.runtime.cpu = 2
    f.runtime.cpu = 1
    response.resolve({ yaml: source, runtime: { ...f.runtime }, formIssue: '' })
    await expect(pending).rejects.toThrow('配置已修改')
    expect(f.editor.mode.value).toBe('form')
    expect(f.editor.yaml.value).toBe('')
  } finally { f.scope.stop() }
})

test('切换草稿取消旧请求，迟到响应不污染原文且不清掉新请求状态', async () => {
  const f = fixture(), old = deferred<{ yaml: string; runtime: Runtime; formIssue: string }>(), next = deferred<{ yaml: string; runtime: Runtime; formIssue: string }>()
  try {
    f.convert(() => old.promise)
    const first = f.editor.switchTo('yaml'), firstSignal = f.signal()
    f.editor.reset({ ...f.runtime })
    expect(firstSignal?.aborted).toBe(true)
    f.convert(() => next.promise)
    const second = f.editor.switchTo('yaml')
    old.resolve({ yaml: source, runtime: { ...f.runtime }, formIssue: '' })
    expect(await first).toBe(false)
    expect(f.editor.converting.value).toBe(true)
    next.resolve({ yaml: source, runtime: { ...f.runtime }, formIssue: '' })
    expect(await second).toBe(true)
    expect(f.editor.yaml.value).toBe(source)
  } finally { f.scope.stop() }
})

test('重复切换不会多发请求，组件销毁后取消并拒绝迟到结果', async () => {
  const f = fixture(), response = deferred<{ yaml: string; runtime: Runtime; formIssue: string }>()
  f.convert(() => response.promise)
  const pending = f.editor.switchTo('yaml')
  expect(await f.editor.switchTo('yaml')).toBe(false)
  expect(f.requests()).toBe(1)
  f.scope.stop()
  expect(f.signal()?.aborted).toBe(true)
  response.resolve({ yaml: source, runtime: { ...f.runtime }, formIssue: '' })
  expect(await pending).toBe(false)
  expect(f.editor.mode.value).toBe('form')
})
