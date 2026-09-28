import { commonFields, type CatalogField } from '../../../shared/types/catalog'
export const configFields: CatalogField[] = [
  ...commonFields,
  {
    key: 'kind',
    label: '配置类型',
    help: '普通值可回显；密钥加密存储；Secret 引用使用已存在的 Kubernetes Secret。',
    choices: ['plain', 'secret', 'secretRef'],
  },
  {
    key: 'value',
    label: '配置值',
    help: '密钥编辑时留空保留旧值，服务端永不返回已保存的密钥。',
    type: 'secret',
  },
  { key: 'secretName', label: 'Kubernetes Secret 名称', help: '仅 Secret 引用类型需要。' },
  { key: 'secretKey', label: 'Secret 数据键', help: '例如 password 或 access-key。' },
]
