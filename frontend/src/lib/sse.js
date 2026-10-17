import { authHeaders, base, readError } from './api'
// A fetch chunk is not an SSE event. Buffer through the blank-line delimiter.
export async function streamAnswer(body, signal, onEvent) {
  const response = await fetch(base + '/hui-ansWer/chat/stream', {
    method: 'POST', signal, headers: { ...authHeaders(), 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(body)
  })
  if (!response.ok || !response.headers.get('content-type')?.includes('text/event-stream')) throw await readError(response)
  if (!response.body) throw new Error('浏览器无法读取流式回答')
  const reader = response.body.getReader(), decoder = new TextDecoder()
  let buffer = '', terminal = false
  const dispatch = frame => {
    let event = 'message'; const data = []
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
    }
    if (!data.length) return
    if (['done', 'stopped', 'error'].includes(event)) terminal = true
    onEvent(event, data.join('\n'))
  }
  try {
    while (true) {
      const { done, value } = await reader.read()
      buffer += decoder.decode(value, { stream: !done })
      let match
      while ((match = /\r?\n\r?\n/.exec(buffer))) {
        dispatch(buffer.slice(0, match.index)); buffer = buffer.slice(match.index + match[0].length)
      }
      if (done || terminal) break
    }
    if (!terminal) throw new Error('连接中断，已保留收到的回答，请稍后刷新会话')
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock() }
}
