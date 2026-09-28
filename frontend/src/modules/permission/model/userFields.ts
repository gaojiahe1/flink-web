import type { CatalogField } from '../../../shared/types/catalog'
export const userFields: CatalogField[] = [
  {
    key: 'role',
    label: '角色',
    help: 'ADMIN 管理全部；DEVELOPER 开发部署；OPERATOR 启停和快照；VIEWER 只读。',
    choices: ['ADMIN', 'DEVELOPER', 'OPERATOR', 'VIEWER'],
  },
  {
    key: 'password',
    label: '密码',
    help: '12–72 位，修改用户时留空保留原密码。',
    type: 'secret',
  },
  {
    key: 'enabled',
    label: '启用账户',
    help: '禁用或变更密码/角色后，已有会话失效。',
    type: 'checkbox',
  },
]
