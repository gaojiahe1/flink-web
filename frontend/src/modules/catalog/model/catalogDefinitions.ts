import type { CatalogModule, CatalogField } from '../../../shared/types/catalog'
export type { CatalogModule } from '../../../shared/types/catalog'
import { templateFields } from '../../template/model/templateFields'
import { imageFields } from '../../image/model/imageFields'
import { configFields } from '../../configuration/model/configFields'
import { artifactFields } from '../../artifact/model/artifactFields'
import { userFields } from '../../permission/model/userFields'
export const titles: Record<CatalogModule, string> = {
  templates: '默认模板',
  images: '镜像管理',
  configs: '配置与密钥',
  artifacts: '制品与 S3',
  users: '用户管理',
  audit: '操作审计',
}
export const definitions: Partial<Record<CatalogModule, CatalogField[]>> = {
  templates: templateFields,
  images: imageFields,
  configs: configFields,
  artifacts: artifactFields,
  users: userFields,
}
