import { commonFields, type CatalogField } from '../../../shared/types/catalog'
export const imageFields: CatalogField[] = [
  ...commonFields,
  {
    key: 'repository',
    label: '镜像仓库',
    help: '例如 registry.example.com/team/flink，不包含版本。',
  },
  { key: 'version', label: '镜像版本', help: '创建作业时选择此版本。' },
  { key: 'flinkVersion', label: 'Flink 版本', help: '例如 1.20.3 或 2.3.0。' },
  {
    key: 'javaVersion',
    label: '镜像内 Java 版本',
    help: '这是 Flink 运行时版本，与平台后端 JDK 17 独立。',
  },
  {
    key: 'pullSecret',
    label: 'imagePullSecret',
    help: '可选，运维已在当前 namespace 创建的镜像拉取 Secret。',
  },
]
