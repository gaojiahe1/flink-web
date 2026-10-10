import { request, jsonBody } from '../../../shared/api/http'
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
  runtime: {
    imageId: string
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
  }
}
export const sessionsApi = {
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
