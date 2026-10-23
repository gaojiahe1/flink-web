import { parseDocument } from 'yaml'
import { readLogConfiguration } from '../../shared/utils/logConfiguration'
import { readPodTemplates } from '../../shared/utils/podTemplates'
type Image = { id: string; repository?: string; version?: string; flinkVersion?: string }
export function hydrateJar(
  source: string,
  images: Image[],
  storage?: { bucket: string; rootPrefix: string },
) {
  const document = parseDocument(source)
  if (document.errors.length) throw new Error('YAML 语法无法解析')
  const root = document.toJS(),
    spec = root?.spec,
    job = spec?.job
  if (root?.kind !== 'FlinkDeployment' || !job || !spec?.image)
    throw new Error('缺少完整的 FlinkDeployment 作业配置')
  const image = images.find((i) => i.repository + ':' + i.version === spec.image)
  if (!image) throw new Error('当前镜像未登记，不能安全映射镜像版本')
  if ('v' + image.flinkVersion?.split('.').slice(0, 2).join('_') !== spec.flinkVersion)
    throw new Error('镜像与 Flink 版本不一致')
  if (String(job.entryClass || '').includes('SqlRunner'))
    throw new Error('SQL 作业请使用 SQL 工作台或 YAML')
  if (typeof job.jarURI !== 'string' || !job.jarURI.startsWith('local:///'))
    throw new Error('无法映射主 JAR 路径')
  if (!Number.isInteger(job.parallelism) || job.parallelism < 1) throw new Error('无法映射并行度')
  if (
    job.args &&
    (!Array.isArray(job.args) ||
      job.args.some((v: unknown) => typeof v !== 'string' || /[\r\n]/.test(v)))
  )
    throw new Error('参数包含无法用逐行表单保留的内容')
  const config = spec.flinkConfiguration || {}
  if (
    typeof config !== 'object' ||
    Array.isArray(config) ||
    Object.values(config).some((v) => typeof v !== 'string')
  )
    throw new Error('Flink 配置必须是字符串键值表')
  const slots = Number(config['taskmanager.numberOfTaskSlots'] || 1)
  if (!Number.isInteger(slots) || slots < 1) throw new Error('无法映射 Slot 数')
  for (const manager of ['jobManager', 'taskManager']) {
    const r = spec[manager]?.resource
    if (
      r &&
      ((r.memory && !/^[1-9][0-9]*(\.[0-9]+)?[mg]$/i.test(r.memory)) ||
        (r.cpu !== undefined && (typeof r.cpu !== 'number' || r.cpu <= 0)))
    )
      throw new Error('运行资源格式无法安全映射，请使用 YAML')
  }
  let manifest: any[] | undefined
  for (const pod of [
    spec.podTemplate,
    spec.jobManager?.podTemplate,
    spec.taskManager?.podTemplate,
  ]) {
    const init = pod?.spec?.initContainers?.find((c: any) => c.name === 'fw-download')
    if (!init) continue
    const raw = init.env?.find((e: any) => e.name === 'ARTIFACT_MANIFEST')?.value
    const value = JSON.parse(raw)
    if (
      !Array.isArray(value) ||
      value.some(
        (v: any) =>
          typeof v.uri !== 'string' ||
          !/^(https?|s3):\/\//.test(v.uri) ||
          !v.filename ||
          v.tokenEnv,
      )
    )
      throw new Error('历史下载配置不能映射到 HTTP/S3 文件选择')
    if (manifest && JSON.stringify(value) !== JSON.stringify(manifest))
      throw new Error('JM/TM 下载配置不一致')
    manifest = value
  }
  const references = (manifest || [])
    .filter((item) => !item.managedPlugin)
    .map((item) => {
      if (/^https?:\/\//.test(item.uri))
        return { id: item.uri, filename: item.filename, uri: item.uri }
      const match = /^s3:\/\/[^/]+\/(.+)$/.exec(item.uri)
      if (!match) throw new Error('对象地址无法解析')
      if (!storage || !item.uri.startsWith('s3://' + storage.bucket + '/'))
        return { id: item.uri, filename: item.filename, uri: item.uri }
      return { id: 's3:' + match[1], filename: item.filename, uri: item.uri }
    })
  const main = references.find(
    (item) => job.jarURI === 'local:///opt/flink/usrlib/' + item.filename,
  )
  return {
    options: {
      serviceAccount: spec.serviceAccount || '',
      logConfiguration: readLogConfiguration(spec),
      ...readPodTemplates(spec),
      templateId: '',
      imageId: image.id,
      mainArtifactId: main?.id || '',
      dependencyArtifactIds: references.filter((a) => a !== main).map((a) => a.id),
      jarURI: job.jarURI,
      entryClass: job.entryClass || '',
      parallelism: job.parallelism,
      slots,
      upgradeMode: job.upgradeMode || 'savepoint',
      jmMemory: spec.jobManager?.resource?.memory || '1024m',
      tmMemory: spec.taskManager?.resource?.memory || '2048m',
      jmCpu: spec.jobManager?.resource?.cpu ?? 1,
      tmCpu: spec.taskManager?.resource?.cpu ?? 1,
    },
    args: (job.args || []).join('\n'),
    config: { ...config },
  }
}
