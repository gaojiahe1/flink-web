import { request, jsonBody } from '../../../shared/api/http'
export interface FlinkCatalog {
  id: string
  name: string
  type: 'memory' | 'jdbc' | 'hive' | 'paimon' | 'iceberg'
  description: string
  defaultDatabase: string
  dataSourceId: string
  warehouse: string
  hiveConfDir: string
  revision: number
  dependencyArtifactIds?: string[]
  hiveSiteArtifactId?: string
  metastoreUris?: string
  hiveVersion?: string
}
export interface ColumnDefinition {
  name: string
  type: string
  nullable?: boolean
  primaryKey?: boolean
  sourceType?: string
  partition?: boolean
  comment?: string
}
export interface MetadataResult {
  databases?: string[]
  tables?: { name: string; type: string }[]
  columns?: ColumnDefinition[]
  truncated: boolean
  checkedAt: string
}
export interface CatalogSnippet {
  sql: string
  notice: string
  env: { name: string; value: string }[]
}
export const catalogsApi = {
  metadata: (id: string, database = '', table = '', signal?: AbortSignal) =>
    request<MetadataResult>('/api/sql/catalogs/' + encodeURIComponent(id) + '/metadata', {
      method: 'POST',
      signal,
      ...jsonBody({ database, table }),
    }),
  functions: (id: string, database: string, name = '', signal?: AbortSignal) =>
    request<{
      functions?: { name: string; type: string }[]
      available: boolean
      message?: string
      name?: string
      className?: string
      checkedAt: string
      truncated: boolean
    }>('/api/sql/catalogs/' + encodeURIComponent(id) + '/functions', {
      method: 'POST',
      signal,
      ...jsonBody({ database, name }),
    }),
  usage: (id: string) =>
    request<{ items: { id: string; name: string; revision: number; jobId: string }[] }>(
      '/api/sql/catalogs/' + encodeURIComponent(id) + '/usage',
    ),
  test: (id: string) =>
    request<{ level: string; message: string }>(
      '/api/sql/catalogs/' + encodeURIComponent(id) + '/test',
      { method: 'POST' },
    ),
  list: () => request<FlinkCatalog[]>('/api/sql/catalogs'),
  save: (value: FlinkCatalog) =>
    request<FlinkCatalog>('/api/sql/catalogs/' + encodeURIComponent(value.id), {
      method: 'PUT',
      ...jsonBody(value),
    }),
  remove: (value: FlinkCatalog) =>
    request<void>(
      '/api/sql/catalogs/' + encodeURIComponent(value.id) + '?revision=' + value.revision,
      { method: 'DELETE' },
    ),
  snippet: (id: string, database = '') =>
    request<CatalogSnippet>(
      '/api/sql/catalogs/' +
        encodeURIComponent(id) +
        '/snippet?database=' +
        encodeURIComponent(database),
    ),
}
