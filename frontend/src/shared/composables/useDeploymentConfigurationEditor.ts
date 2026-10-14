import { computed, onScopeDispose, ref, watch } from 'vue'
import { parseDeploymentYaml } from '../utils/deploymentYaml'
import { readPodTemplates, type PodTemplateOptions } from '../utils/podTemplates'

type Mode = 'form' | 'yaml'
interface ConfigurationResult<T> {
  yaml: string
  runtime: T
  formIssue?: string
}

/** 共用原文、有效性、脏状态及转换版本保护，业务字段和保存/发布仍由各页面管理。 */
export function useDeploymentConfigurationEditor<
  T extends { baseYaml?: string; deploymentYaml?: string },
>(options: {
  snapshot: () => unknown
  payload: () => unknown
  convert: (payload: unknown, signal: AbortSignal) => Promise<ConfigurationResult<T>>
  applyRuntime: (runtime: T, pods: PodTemplateOptions) => void
}) {
  const mode = ref<Mode>('form'),
    yaml = ref(''),
    configValid = ref(true),
    podValid = ref(true)
  const converting = ref(false),
    savedSnapshot = ref('')
  let revision = 0,
    context = 0,
    disposed = false,
    controller: AbortController | undefined
  const snapshot = () =>
    JSON.stringify({
      value: options.snapshot(),
      mode: mode.value,
      yaml: mode.value === 'yaml' ? yaml.value : '',
      configValid: configValid.value,
      podValid: podValid.value,
    })
  watch(snapshot, () => revision++, { flush: 'sync' })
  const dirty = computed(() => savedSnapshot.value !== snapshot())

  function validate() {
    if (mode.value === 'yaml') parseDeploymentYaml(yaml.value)
    else {
      if (!configValid.value) throw new Error('请先修正 Flink 配置')
      if (!podValid.value) throw new Error('请先修正 PodTemplate YAML')
    }
  }
  function markSaved() {
    savedSnapshot.value = snapshot()
  }
  function reset(runtime: T) {
    context++
    controller?.abort()
    controller = undefined
    converting.value = false
    yaml.value = runtime.deploymentYaml || ''
    mode.value = yaml.value ? 'yaml' : 'form'
    configValid.value = podValid.value = true
  }
  async function switchTo(target: Mode) {
    if (disposed || converting.value || target === mode.value) return false
    validate()
    const payload = options.payload(),
      before = snapshot(),
      version = revision,
      epoch = context
    const request = new AbortController()
    controller = request
    converting.value = true
    try {
      const result = await options.convert(payload, request.signal)
      if (disposed || epoch !== context || request.signal.aborted) return false
      if (revision !== version || before !== snapshot())
        throw new Error('配置已修改，请重新切换编辑方式')
      if (target === 'form' && result.formIssue) throw new Error(result.formIssue)
      const document = parseDeploymentYaml(result.yaml)
      const pods = readPodTemplates(document.toJS().spec)
      if (target === 'yaml') yaml.value = result.yaml
      else {
        options.applyRuntime({ ...result.runtime, deploymentYaml: '', baseYaml: result.yaml }, pods)
        configValid.value = podValid.value = true
      }
      mode.value = target
      return true
    } catch (error) {
      if (disposed || epoch !== context || request.signal.aborted) return false
      throw error
    } finally {
      if (controller === request) {
        controller = undefined
        converting.value = false
      }
    }
  }
  onScopeDispose(() => {
    disposed = true
    context++
    controller?.abort()
  })
  return {
    mode,
    yaml,
    configValid,
    podValid,
    converting,
    dirty,
    savedSnapshot,
    snapshot,
    reset,
    markSaved,
    validate,
    switchTo,
  }
}
