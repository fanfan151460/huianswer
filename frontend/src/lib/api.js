import { session, clearSession } from './session'
export const base = '/huianswer'
export function authHeaders() { return { username: session.username, token: session.token } }
export function expireSession() {
  clearSession(); window.dispatchEvent(new CustomEvent('huianswer:expired'))
}
export function apiError(message, status) {
  if (status === 401 || /^用户未登录$|^用户登录信息异常[!！]?$/.test(message || '')) expireSession()
  return new Error(message || (status === 429 ? '请求有些频繁，请稍后再试' : '请求失败，请稍后重试'))
}
export async function readError(response) {
  let message = ''
  try { const value = await response.json(); message = value.message || value.errorMessage || '' } catch { /* Proxy may return HTML. */ }
  return apiError(message, response.status)
}
export async function api(path, { method = 'GET', body, signal } = {}) {
  if (session.preview && import.meta.env.DEV) {
    const { previewApi } = await import('./preview')
    return previewApi(path, { method, body })
  }
  let response
  try {
    response = await fetch(base + path, { method, signal,
      headers: { ...authHeaders(), ...(body && !(body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}) },
      body: body instanceof FormData ? body : body === undefined ? undefined : JSON.stringify(body) })
  } catch (e) {
    if (e.name === 'AbortError') throw e
    throw new Error('连接失败，请检查网络后重试')
  }
  if (!response.ok) throw await readError(response)
  const value = await response.json()
  if (value.code !== '0') throw apiError(value.message, response.status)
  return value.data
}
export const login = body => api('/api/hui-answer/login', { method: 'POST', body })
export const listConversations = () => api('/api/hui-answer/conversation')
export const history = id => api('/api/hui-answer/' + encodeURIComponent(id))
export const listBases = () => api('/api/knowledge-base')
export const docPath = (kb, doc = '') => `/api/knowledge-base/${kb}/documents${doc ? '/' + doc : ''}`
export function formatDate(value) {
  if (!value) return '刚刚'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date)
}
