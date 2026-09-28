import { ref } from 'vue'
import { request } from '../../../shared/api/http'
import type { CurrentUser } from '../../../shared/types/api'

export const currentUser = ref<CurrentUser | null>(null)
let checked = false

export async function ensureAuth() {
  if (checked) return Boolean(currentUser.value)
  try {
    currentUser.value = await request<CurrentUser>('/api/auth/me')
  } catch {
    currentUser.value = null
  }
  checked = true
  return Boolean(currentUser.value)
}

export async function login(username: string, password: string) {
  await request('/api/auth/login', {
    method: 'POST',
    body: new URLSearchParams({ username, password }),
  })
  currentUser.value = await request<CurrentUser>('/api/auth/me')
  checked = true
}

export async function logout() {
  await request('/api/auth/logout', { method: 'POST' })
  currentUser.value = null
  checked = true
}

export function clearSession() {
  currentUser.value = null
  checked = true
}
