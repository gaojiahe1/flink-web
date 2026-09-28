export interface Group {
  id: string
  name: string
  members: string[]
  revision: number
}
export interface ImageOption {
  id: string
  name: string
  version: string
  flinkVersion: string
  javaVersion?: string
}
export interface SqlHistoryItem {
  id: string
  sqlId: string
  sql: string
  actor: string
  createdAt: string
  draftRevision: number
}

export interface JobAccess {
  revision: number
  owner: string
  project: string
  canEdit: boolean
  canOperate: boolean
  canManage: boolean
  readGroups?: string[]
  operateGroups?: string[]
  editGroups?: string[]
  externalLinks?: { name: string; url: string }[]
}
export interface Release {
  id: string
  createdAt: string
  actor: string
  draftRevision: number
  sourceYaml: string
  flinkVersion: string
  image: string
  contentSha256: string
  artifacts: { uri: string; sha256: string; filename: string }[]
  configReferences: { id: string; revision: number; kind: string }[]
}
export interface SnapshotCheck {
  exists: boolean
  checkedAt: string
  path: string
  message: string
}
export interface Snapshot {
  id: string
  type: string
  path: string
  firstObservedAt: string
  observedReleaseId: string
  lastCheck?: SnapshotCheck
}
export interface OperationObservation {
  id: string
  action: string
  state: string
  message: string
  updatedAt: string
  releaseId?: string
  observedAt?: string
  observedState?: string
  observedLifecycle?: string
}
export interface DiagnosticPod {
  name: string
  phase: string
  containers: string[]
  states: { name: string; restarts: number; reason?: string; message?: string }[]
}
export interface Diagnostic {
  queriedAt: string
  error: string
  pods: DiagnosticPod[]
  events: { time: string; type: string; reason: string; message: string }[]
}
