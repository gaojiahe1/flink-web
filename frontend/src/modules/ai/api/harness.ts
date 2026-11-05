import { jsonBody, request } from '../../../shared/api/http'

export interface HarnessEvent {
  sequence: number
  type: string
  title: string
  at: string
  detail: Record<string, unknown>
}
export interface HarnessPlan {
  execution?: { available: boolean; message: string; image?: string }
  runYaml?: string
  newDraft?: boolean
  sqlName?: string
  id: string
  kind?: string
  beforeSql?: string
  sql?: string
  beforeYaml?: string
  afterYaml?: string
  check: string
  changes?: { path: string; value: unknown }[]
}
export interface HarnessInteraction {
  id: string
  kind: 'clarification' | 'choice' | 'logs' | 'verification' | 'preferences'
  title: string
  questions: string[]
  options: string[]
  state: 'PENDING' | 'ANSWERED' | 'CANCELED' | 'EXPIRED'
  expiresAt: string
  scope?: string
  model?: string
  action?: string
  verificationResult?: { state: string; message?: string }
  answers?: Record<string, string>
  changes?: Record<string, string>
  previousValues?: Record<string, string>
  sourceQuote?: string
  savedPreferenceRevision?: number
}
export interface HarnessTurn {
  interaction?: HarnessInteraction
  id: string
  sessionId: string
  sequence: number
  state: string
  content: string
  reply: string
  message?: string
  model?: string
  plan: string[]
  planStatus?: 'PENDING' | 'READY'
  runtimeStatus?: HarnessObservation
  scene?: 'sql_development' | 'status' | 'diagnosis' | 'repair' | 'evaluation'
  recovery?: {
    action: 'recover' | 'stop'
    message: string
    nextSteps?: string[]
    diagnostic?: { message: string; line?: number; excerpt?: { line: number; text: string }[] }
  }
  candidate?: HarnessPlan
  planId?: string
  contextManifest: Record<string, unknown>
  events: HarnessEvent[]
  eventsTruncated?: boolean
  requirements?: { summary: string; unknowns: string[] }
  approvedRequirements?: unknown
  applicationId?: string
  application?: {
    mode?: 'save' | 'restart' | 'run'
    sqlId?: string
    jobId?: string
    state: string
    message: string
    operationId?: string
    request?: Record<string, unknown>
  }
  observation?: HarnessObservation
}
export interface HarnessObservation {
  jobId?: string
  jobName?: string
  jobState: string
  lifecycleState?: string
  jobManagerDeploymentStatus?: string
  serviceAccount?: string
  observedAt?: string
  runningRevisionConfirmed: boolean
  currentRunning?: boolean
  message: string
  businessVerified: boolean
  issues?: { source: string; message: string; historical: boolean }[]
  diagnosticsIncomplete?: boolean
}
export interface HarnessSession {
  id: string
  revision: number
  title: string
  kind: 'diagnosis' | 'sql' | 'evaluation'
  jobId: string
  sqlId: string
  sourceSessionId: string
  updatedAt: string
  turns?: HarnessTurn[]
  memory?: { summary: string; verificationStatus: string; sourceTurnId: string }
}
const base = '/api/ai/harness/sessions'
const path = (id: string) => base + '/' + encodeURIComponent(id)
const turnPath = (id: string, turn: string) => path(id) + '/turns/' + encodeURIComponent(turn)
export const harnessApi = {
  respond: (id: string, turn: string, input: unknown) =>
    request<HarnessTurn>(turnPath(id, turn) + '/respond', { method: 'POST', ...jsonBody(input) }),
  list: () => request<HarnessSession[]>(base),
  create: (input: unknown) => request<HarnessSession>(base, { method: 'POST', ...jsonBody(input) }),
  get: (id: string) => request<HarnessSession>(path(id)),
  remove: (id: string, revision: number) =>
    request<void>(path(id) + '?revision=' + revision, { method: 'DELETE' }),
  start: (id: string, input: unknown) =>
    request<HarnessTurn>(path(id) + '/turns', { method: 'POST', ...jsonBody(input) }),
  cancel: (id: string, turn: string) =>
    request<HarnessTurn>(turnPath(id, turn) + '/cancel', { method: 'POST' }),
  apply: (id: string, turn: string, input: unknown) =>
    request<HarnessTurn>(turnPath(id, turn) + '/apply', { method: 'POST', ...jsonBody(input) }),
  observe: (id: string, turn: string) =>
    request<HarnessTurn>(turnPath(id, turn) + '/observe', { method: 'POST' }),
  eventsUrl: (id: string, turn: string) => turnPath(id, turn) + '/events',
}
export const activeHarnessTurn = (turn: HarnessTurn) =>
  ['QUEUED', 'RUNNING', 'APPLYING'].includes(turn.state)
