import { commonFields, type CatalogField } from '../../../shared/types/catalog'
export const artifactFields: CatalogField[] = [
  ...commonFields,
  {
    key: 'category',
    label: '文件目录',
    help: 'main 为主 JAR，dependency 为依赖/配置，shared 为历史公共制品；所有作业均可引用。',
    choices: ['main', 'dependency', 'shared'],
  },
  {
    key: 'uri',
    label: '下载地址',
    help: 'HTTP/HTTPS/S3 地址，不允许 URL 内携带用户名、查询参数或密钥。',
  },
  {
    key: 'filename',
    label: '容器内文件名',
    help: '例如 job.jar 或 connector.jar；Java 源码可归档，但不能直接提交执行。',
  },
  { key: 'sha256', label: 'SHA-256', help: '64 位十六进制，initContainer 下载后必须通过校验。' },
  {
    key: 'credentialRef',
    label: '下载凭证配置标识',
    help: '可选，HTTPS Bearer Token 对应的密钥配置；S3 使用工作负载凭证。',
  },
]
