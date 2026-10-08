import { commonFields, type CatalogField } from '../../../shared/types/catalog'
export const imageFields: CatalogField[] = [
  ...commonFields.filter((field) => field.key !== 'name'),
  {
    key: 'repository',
    label: '镜像仓库',
    help: '例如 registry.example.com/team/flink，不包含版本。',
  },
  { key: 'version', label: '镜像版本', help: '例如 1.20.5-scala_2.12，自动识别 Flink 版本。' },
  {
    key: 'javaVersion',
    label: '镜像内 Java 版本',
    help: '这是 Flink 运行时版本，与平台后端 JDK 17 独立。',
  },
]
