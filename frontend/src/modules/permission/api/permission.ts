import { request, jsonBody } from '../../../shared/api/http'
import type { Group } from '../../../shared/types/workbench'
export const permissionApi = {
  groups: () => request<Group[]>('/api/platform/groups'),
  jobAccess: () =>
    request<Record<string, { canEdit: boolean; canOperate: boolean }>>('/api/platform/job-access'),
  saveGroup: (id: string, input: { name: string; members: string[]; revision: number }) =>
    request<Group>('/api/platform/groups/' + encodeURIComponent(id), {
      method: 'PUT',
      ...jsonBody(input),
    }),
}
