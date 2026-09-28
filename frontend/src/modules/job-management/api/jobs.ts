import { jsonBody, request } from '../../../shared/api/http'
import type { DraftInput, JobDraft } from '../../../shared/types/api'

export const jobsApi = {
  list: () => request<JobDraft[]>('/api/jobs'),
  get: (id: string) => request<JobDraft>('/api/jobs/' + encodeURIComponent(id)),
  refresh: (signal?: AbortSignal) =>
    request<unknown>('/api/jobs/refresh', { method: 'POST', signal }),
  create: (draft: DraftInput) =>
    request<JobDraft>('/api/jobs', { method: 'POST', ...jsonBody(draft) }),
  update: (id: string, draft: DraftInput) =>
    request<JobDraft>('/api/jobs/' + encodeURIComponent(id) + '/draft', {
      method: 'PUT',
      ...jsonBody(draft),
    }),
  remove: (draft: JobDraft) =>
    request<void>(
      '/api/jobs/' + encodeURIComponent(draft.id) + '/draft?revision=' + draft.revision,
      { method: 'DELETE' },
    ),
}
