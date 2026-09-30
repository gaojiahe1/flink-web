import { showValidationError, validationScope } from '../utils/fieldValidation'

interface Envelope<T> {
  data: T
  error?: { code: string; message: string }
}
interface Csrf {
  headerName: string
  token: string
}

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message)
  }
}

export async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const fieldScope = validationScope()
  if ((init.method || 'GET').toUpperCase() !== 'GET') {
    const { protectSensitiveRequest } = await import('./secretTransport')
    init = await protectSensitiveRequest(url, init)
  }
  const headers = new Headers(init.headers)
  const method = (init.method || 'GET').toUpperCase()
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    // Fetch an explicit session token; never retry mutation requests automatically.
    const token = await request<Csrf>('/api/auth/csrf')
    headers.set(token.headerName, token.token)
  }
  const response = await fetch(url, {
    ...init,
    headers,
    credentials: 'same-origin',
    cache: 'no-store',
  })
  const body = (await response.json().catch(() => null)) as Envelope<T> | null
  if (!response.ok) {
    const error = new ApiError(
      response.status,
      body?.error?.code || 'HTTP_ERROR',
      body?.error?.message || '请求失败，请检查后端服务',
    )
    if (response.status === 401 && !url.startsWith('/api/auth/')) {
      window.dispatchEvent(new Event('session-expired'))
    }
    if ([400, 409, 422].includes(response.status)) showValidationError(error.message, fieldScope)
    throw error
  }
  if (!body) throw new ApiError(response.status, 'INVALID_RESPONSE', '服务端响应格式不正确')
  return body.data
}

export function jsonBody(value: unknown): Pick<RequestInit, 'headers' | 'body'> {
  return { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) }
}
