import { request, jsonBody } from '../../../shared/api/http'
import type { SqlHistoryItem } from '../../../shared/types/workbench'
export const sqlHistoryApi = {
  list: (id: string) =>
    request<SqlHistoryItem[]>('/api/sql/jobs/' + encodeURIComponent(id) + '/versions'),
  restore: (id: string, version: string, revision: number) =>
    request(
      '/api/sql/jobs/' +
        encodeURIComponent(id) +
        '/versions/' +
        encodeURIComponent(version) +
        '/restore',
      { method: 'POST', ...jsonBody({ revision }) },
    ),
}
