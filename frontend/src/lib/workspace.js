import { reactive } from 'vue'
import { api, listConversations, history } from './api'
import { session, signedIn } from './session'
import { streamAnswer } from './sse'
export const workspace = reactive({ conversations: [], loading: false, error: '', notices: [] })
export function notify(text, type = 'info') {
  const item = { id: Date.now() + Math.random(), text, type }; workspace.notices.push(item)
  setTimeout(() => { const i = workspace.notices.indexOf(item); if (i >= 0) workspace.notices.splice(i, 1) }, 4500)
}
export async function refreshConversations() {
  workspace.loading = true; workspace.error = ''
  try { workspace.conversations = await listConversations() || [] }
  catch (e) { workspace.error = e.message }
  finally { workspace.loading = false }
}
export const chat = reactive({ conversationId: null, messages: [], generating: false,
  generationId: null, stopping: false, error: '', loading: false })
let controller = null, loadIndex = 0, stopQueued = false
export async function loadConversation(id) {
  const index = ++loadIndex
  if (chat.generating) return
  chat.conversationId = id ? String(id) : null; chat.messages = []; chat.error = ''
  if (!id) { chat.loading = false; return }
  chat.loading = true
  try { const messages = await history(id); if (index === loadIndex) chat.messages = messages || [] }
  catch (e) { if (index === loadIndex) chat.error = e.message }
  finally { if (index === loadIndex) chat.loading = false }
}
export async function requestStop() {
  if (!chat.generating) return
  chat.stopping = true; stopQueued = true
  if (!chat.generationId) return
  try {
    await api(`/api/hui-answer/conversation/${chat.conversationId}/stop`, { method: 'POST', body: { generationId: chat.generationId } })
  } catch (e) { chat.stopping = false; stopQueued = false; notify(e.message, 'error') }
}
export function disconnect() { controller?.abort() }
export async function sendQuestion(question, onMeta) {
  if (chat.generating || !question.trim()) return
  ++loadIndex; chat.generating = true; chat.generationId = null; chat.error = ''; stopQueued = false; chat.stopping = false
  const now = Date.now()
  chat.messages.push({ id: 'local-user-' + now, role: 'user', content: question.trim(), status: 'success' })
  chat.messages.push({ id: 'local-answer-' + now, role: 'assistant', content: '', status: 'generating', sources: [] })
  // Use the reactive object, so streamed text is observable by Vue.
  const answer = chat.messages[chat.messages.length - 1]
  controller = new AbortController()
  let terminal = false
  const handle = (event, data) => {
    if (event === 'meta') {
      const meta = JSON.parse(data); chat.conversationId = String(meta.conversationId); chat.generationId = meta.generationId
      onMeta?.(chat.conversationId); if (stopQueued) void requestStop()
    } else if (event === 'delta') answer.content += data
    else if (event === 'done') { terminal = true; answer.status = 'success' }
    else if (event === 'stopped') { terminal = true; answer.status = 'stopped'; answer.errorMessage = '已停止生成' }
    else if (event === 'error') { terminal = true; throw new Error(JSON.parse(data).message || '回答生成失败，请稍后再试') }
  }
  try {
    if (session.preview && import.meta.env.DEV) {
      const { previewStream } = await import('./preview'); await previewStream(question, chat.conversationId, handle, controller.signal)
    } else await streamAnswer({ question: question.trim(), conversationId: chat.conversationId }, controller.signal, handle)
  } catch (e) {
    answer.status = 'failed'; answer.errorMessage = e.name === 'AbortError' ? '连接已关闭' : e.message
    chat.error = answer.errorMessage
  } finally {
    const currentId = chat.conversationId
    controller = null; chat.generating = false; chat.stopping = false; chat.generationId = null
    if (!signedIn()) return
    await refreshConversations()
    if (currentId && chat.conversationId === currentId && terminal && answer.status !== 'failed') {
      try { const saved = await history(currentId); if (chat.conversationId === currentId && !chat.generating && saved?.length) chat.messages = saved }
      catch { notify('回答已结束，引用暂未加载，可稍后刷新会话') }
    }
  }
}
