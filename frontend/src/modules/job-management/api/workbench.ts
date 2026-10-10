import { request, jsonBody } from '../../../shared/api/http'
import type { JobDraft } from '../../../shared/types/api'
import type {
  Release,
  Snapshot,
  SnapshotCheck,
  JobAccess,
  OperationObservation,
  Diagnostic,
} from '../../../shared/types/workbench'
const jobPath = (id: string) => '/api/jobs/' + encodeURIComponent(id)
export interface LogQuery {
  pod: string
  container: string
  lines: number
  previous: boolean
}
export const workbenchApi = {
  releases: (id: string) => request<Release[]>(jobPath(id) + '/releases'),
  snapshots: (id: string) => request<Snapshot[]>(jobPath(id) + '/snapshots'),
  access: (id: string) => request<JobAccess>(jobPath(id) + '/access'),
  operations: (id: string) => request<OperationObservation[]>(jobPath(id) + '/operations'),
  diagnostics: (id: string) =>
    request<Diagnostic>(jobPath(id) + '/diagnostics', { method: 'POST' }),
  logs: (id: string, input: LogQuery) =>
    request<{ text: string; queriedAt?: string; limitBytes?: number; lines?: number }>(
      jobPath(id) + '/logs',
      { method: 'POST', ...jsonBody(input) },
    ),
  restore: (id: string, release: string, revision: number) =>
    request<JobDraft>(jobPath(id) + '/releases/' + encodeURIComponent(release) + '/restore', {
      method: 'POST',
      ...jsonBody({ revision }),
    }),
  checkSnapshot: (id: string, snapshot: string) =>
    request<SnapshotCheck>(jobPath(id) + '/snapshots/' + encodeURIComponent(snapshot) + '/check', {
      method: 'POST',
    }),
  saveAccess: (id: string, input: JobAccess) =>
    request<JobAccess>(jobPath(id) + '/access', { method: 'PUT', ...jsonBody(input) }),
}
