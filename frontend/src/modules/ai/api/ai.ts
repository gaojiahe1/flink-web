import { jsonBody, request } from '../../../shared/api/http'

export interface AiModel {
  id: string
  name: string
  model: string
  baseUrl?: string
  revision: number
  enabled: boolean
  external: boolean
  privateNetwork?: boolean
  hasKey?: boolean
  purposes: string[]
  timeoutSeconds: number
  maxTokens: number
}
export interface AiResult {
  summary: string
  risks: string[]
  evidence: string[]
  unknowns: string[]
  steps: string[]
  sql?: string
  definitions?: Record<string, unknown>[]
  env?: { name: string; value: string }[]
  flinkConfiguration?: Record<string, string>
  validationLevel?: string
  spec?: Record<string, unknown>
  reply?: string
  baseSqlHash?: string
  conversationVersion?: number
}
export interface AiConversation {
  id: string
  sqlId: string
  revision: number
  contentVersion: number
  summaryVersion: number
  confirmedVersion: number
  summary: string
  summarySqlHash?: string
  unknowns: string[]
  lastSqlTaskId?: string
  messages: {
    id: string
    role: 'user' | 'assistant'
    content: string
    mode?: string
    taskId?: string
    createdAt: string
  }[]
}
export interface AiTask {
  id: string
  state: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELED'
  kind: string
  model: string
  message?: string
  createdAt: string
  result?: AiResult
}
export const aiApi = {
  conversation: (sqlId: string) =>
    request<AiConversation>('/api/ai/conversations/' + encodeURIComponent(sqlId)),
  message: (sqlId: string, value: unknown) =>
    request<AiConversation>('/api/ai/conversations/' + encodeURIComponent(sqlId) + '/messages', {
      method: 'POST',
      ...jsonBody(value),
    }),
  summary: (sqlId: string, value: unknown) =>
    request<AiConversation>('/api/ai/conversations/' + encodeURIComponent(sqlId) + '/summary', {
      method: 'PUT',
      ...jsonBody(value),
    }),
  confirm: (sqlId: string, value: unknown) =>
    request<AiConversation>('/api/ai/conversations/' + encodeURIComponent(sqlId) + '/confirm', {
      method: 'POST',
      ...jsonBody(value),
    }),
  models: (management = false) => request<AiModel[]>('/api/ai/models?management=' + management),
  saveModel: (id: string, value: unknown) =>
    request<AiModel>('/api/ai/models/' + encodeURIComponent(id), {
      method: 'PUT',
      ...jsonBody(value),
    }),
  deleteModel: (model: AiModel) =>
    request('/api/ai/models/' + encodeURIComponent(model.id) + '?revision=' + model.revision, {
      method: 'DELETE',
    }),
  testModel: (id: string) =>
    request<{ message: string }>('/api/ai/models/' + encodeURIComponent(id) + '/test', {
      method: 'POST',
    }),
  start: (value: unknown) =>
    request<AiTask>('/api/ai/tasks', { method: 'POST', ...jsonBody(value) }),
  task: (id: string, signal?: AbortSignal) => request<AiTask>('/api/ai/tasks/' + id, { signal }),
  cancel: (id: string) => request<AiTask>('/api/ai/tasks/' + id + '/cancel', { method: 'POST' }),
  history: (sqlId: string) => request<AiTask[]>('/api/ai/tasks?' + new URLSearchParams({ sqlId })),
  compile: (definitions: Record<string, unknown>[]) =>
    request<{
      valid: boolean
      sql: string
      env: { name: string; value: string }[]
      pending: { table: string; message: string }[]
    }>('/api/ai/definitions/compile', { method: 'POST', ...jsonBody({ definitions }) }),
}
