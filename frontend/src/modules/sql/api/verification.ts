import { request, jsonBody } from '../../../shared/api/http'
export interface SqlVerification {
  id: string
  sqlId: string
  jobId: string
  sqlRevision: number
  state: string
  message: string
  limit: number
  deadline: string
  rows: { sink: number; kind: string; value: string }[]
}
export const verificationTerminal = (state: string) =>
  ['COMPLETED', 'FINISHED', 'FAILED', 'CANCELED', 'TIMED_OUT', 'ABORTED'].includes(state)
export const verificationApi = {
  submit: (id: string, revision: number, requestId: string) =>
    request<SqlVerification>('/api/sql/jobs/' + encodeURIComponent(id) + '/verifications', {
      method: 'POST',
      ...jsonBody({ revision, requestId }),
    }),
  status: (id: string, signal?: AbortSignal) =>
    request<SqlVerification>('/api/sql/verifications/' + id, { signal }),
  cancel: (id: string) =>
    request<SqlVerification>('/api/sql/verifications/' + id + '/cancel', { method: 'POST' }),
}
