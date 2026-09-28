import { commonFields, type CatalogField } from '../../../shared/types/catalog'
export const templateFields: CatalogField[] = [
  ...commonFields,
  {
    key: 'yaml',
    label: '模板 YAML',
    help: '完整 FlinkDeployment；支持 PodTemplate 标签、环境变量和 initContainers。',
    type: 'textarea',
  },
]
