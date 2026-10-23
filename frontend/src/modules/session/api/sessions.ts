import { request, jsonBody } from '../../../shared/api/http'
import type { Diagnostic } from '../../../shared/types/workbench'
import type { LogQuery } from '../../job-management/api/workbench'
export interface SessionCluster {
  id: string
  name: string
  description: string
  validation: boolean
  revision: number
  deploymentName: string
  desiredState: string
  state?: string
  message?: string
  configuredTaskManagers?: number
  registeredTaskManagers?: number
  taskManagerMessage?: string
  queriedAt?: string
  uid?: string
  runtime: {
    logConfiguration?: Record<string, string>
    baseYaml?: string
    deploymentYaml?: string
    deploymentMode?: string
    tmReplicas?: number | null
    imageId: string
    serviceAccount?: string
    runnerArtifactId?: string
    slots: number
    parallelism: number
    jmMemory: string
    tmMemory: string
    jmCpu: number
    tmCpu: number
    dependencyArtifactIds: string[]
    flinkConfiguration: Record<string, string>
    podTemplate: Record<string, unknown>
    jmPodTemplate?: Record<string, unknown>
    tmPodTemplate?: Record<string, unknown>
  }
}
export const sessionsApi = {
  diagnostics: (id: string) =>
    request<Diagnostic>('/api/sessions/' + encodeURIComponent(id) + '/diagnostics', {
      method: 'POST',
    }),
  logs: (id: string, input: LogQuery) =>
    request<{ text: string; queriedAt?: string; limitBytes?: number; lines?: number }>(
      '/api/sessions/' + encodeURIComponent(id) + '/logs',
      { method: 'POST', ...jsonBody(input) },
    ),
  configuration: (value: unknown, signal?: AbortSignal) =>
    request<{ yaml: string; runtime: SessionCluster['runtime']; formIssue?: string }>(
      '/api/sessions/configuration',
      {
        signal,
        method: 'POST',
        ...jsonBody(value),
      },
    ),
  list: () => request<SessionCluster[]>('/api/sessions'),
  status: (id: string) =>
    request<SessionCluster>('/api/sessions/' + encodeURIComponent(id) + '/status'),
  save: (id: string, value: Omit<SessionCluster, 'id' | 'deploymentName' | 'desiredState'>) =>
    request<SessionCluster>('/api/sessions/' + encodeURIComponent(id), {
      method: 'PUT',
      ...jsonBody(value),
    }),
  start: (item: SessionCluster) =>
    request<SessionCluster>('/api/sessions/' + encodeURIComponent(item.id) + '/start', {
      method: 'POST',
      ...jsonBody({ revision: item.revision }),
    }),
  stop: (id: string) =>
    request<SessionCluster>('/api/sessions/' + encodeURIComponent(id) + '/stop', {
      method: 'POST',
    }),
  remove: (item: SessionCluster) =>
    request('/api/sessions/' + encodeURIComponent(item.id) + '?revision=' + item.revision, {
      method: 'DELETE',
    }),
}
