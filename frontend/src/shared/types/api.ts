export interface CurrentUser {
  username: string
  roles: string[]
}
export interface SystemInfo {
  version: string
  namespace: string
  storageType: 'mysql' | 'local'
  capabilities: { name: string; implemented: boolean }[]
}
export interface JobDraft {
  jobNumber: number
  deploymentName: string
  id: string
  name: string
  description: string
  flinkVersion: string
  yaml: string
  revision: number
  createdAt: string
  updatedAt: string
}
export type DraftInput = Pick<
  JobDraft,
  'name' | 'description' | 'flinkVersion' | 'yaml' | 'revision'
> & { workspaceId?: string; allowFormRewrite?: boolean }
