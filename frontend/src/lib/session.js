import { reactive } from 'vue'
const key = 'huianswer.session.v1'
let stored = null
try { stored = JSON.parse(localStorage.getItem(key)) } catch { /* Ignore invalid saved state. */ }
export const session = reactive({ username: stored?.username || '', token: stored?.token || '',
  preview: import.meta.env.DEV && sessionStorage.getItem('huianswer.preview') === '1' })
export const signedIn = () => session.preview || Boolean(session.username && session.token)
export const isAdmin = () => session.preview || session.username === 'admin'
export function saveSession(username, token) {
  session.preview = false; sessionStorage.removeItem('huianswer.preview')
  session.username = username; session.token = token
  localStorage.setItem(key, JSON.stringify({ username, token }))
}
export function previewSession() {
  if (!import.meta.env.DEV) return
  session.preview = true; sessionStorage.setItem('huianswer.preview', '1')
}
export function clearSession() {
  session.username = ''; session.token = ''; session.preview = false
  localStorage.removeItem(key); sessionStorage.removeItem('huianswer.preview')
}
