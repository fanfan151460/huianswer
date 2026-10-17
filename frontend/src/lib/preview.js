// Imported only in Vite development mode. Never call a real API with preview credentials.
const now = () => new Date().toISOString()
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
let nextId = 100, stopped = false
let bases = [
  { id: 1, name: '商品与服务', description: '星澜商城商品说明、基础版与 Pro 版功能和适用范围。', createTime: now() },
  { id: 2, name: '订单与支付', description: '订单创建、支付、取消与发票规则。', createTime: now() },
  { id: 3, name: '配送与物流', description: '发货时效、配送范围和物流异常处理。', createTime: now() },
  { id: 4, name: '售后与退换', description: '退换货、退款及寄回运费相关规则。', createTime: now() }
]
const source = [{ docId: 11, chunkId: 101, docName: '星澜商城售后与退换规则.md' }, { docId: 12, chunkId: 102, docName: '寄回运费与报销说明.txt' }]
const answer = '如果已经核实为**商品质量问题**，并且确实需要寄回，商家会按规则承担普通快递的寄回费用。[1]\n\n### 寄出前，注意这三件事\n\n1. **先联系售后确认方案**，再寄回商品。\n2. 保留运单和实际费用凭证，方便申请报销。\n3. 默认报销上限为**每工单 20 元**，预计超出时应提前确认。[2]\n\n> 未经确认使用到付或高价专送，不代表商家会接受全部实际费用。\n\n以上是界面预览中的示例内容，用于展示回答排版和引用交互。'
const conversations = [
  { id: 21, title: '质量问题退货，运费由谁承担？', createTime: now(), updateTime: now() },
  { id: 22, title: '基础版与 Pro 版有什么区别', createTime: now(), updateTime: now() },
  { id: 23, title: '申请退款需要准备哪些材料', createTime: '2026-10-13T10:30:00', updateTime: '2026-10-13T10:30:00' }
]
const messages = new Map(conversations.map(c => [String(c.id), [
  { id: c.id * 10, role: 'user', content: c.title, status: 'success' },
  { id: c.id * 10 + 1, role: 'assistant', content: answer, status: 'success', sources: source, finishReason: 'stop' }
]]))
let docs = [
  { id: 11, kbId: 1, docName: '星澜商城商品与服务说明.md', status: 'success', chunkCount: 8 },
  { id: 12, kbId: 1, docName: '基础版与 Pro 版功能对照.pdf', status: 'success', chunkCount: 6 },
  { id: 13, kbId: 1, docName: '商品规格与使用建议.txt', status: 'success', chunkCount: 5 },
  { id: 14, kbId: 1, docName: '旧版商品说明.md', status: 'success', enabled: 1, chunkCount: 3 },
  { id: 15, kbId: 1, docName: '补充资料.pdf', status: 'failed', errorMessage: '未解析到可用文字，请上传文本型 PDF', chunkCount: 0 }
].map(d => ({ enabled: 0, createTime: now(), ...d }))
const clone = value => JSON.parse(JSON.stringify(value))
export async function previewApi(path, { method = 'GET', body } = {}) {
  await sleep(130)
  const url = new URL(path, 'http://preview.local'), p = url.pathname
  if (p === '/api/hui-answer/conversation') return clone(conversations)
  let match
  if ((match = p.match(/^\/api\/hui-answer\/conversation\/(\d+)\/stop$/))) { stopped = true; return null }
  if ((match = p.match(/^\/api\/hui-answer\/conversation\/(\d+)$/))) {
    const index = conversations.findIndex(c => String(c.id) === match[1])
    if (method === 'PUT') conversations[index].title = body.title
    if (method === 'DELETE') { conversations.splice(index, 1); messages.delete(match[1]) }
    return null
  }
  if ((match = p.match(/^\/api\/hui-answer\/(\d+)$/))) return clone(messages.get(match[1]) || [])
  if (p === '/api/knowledge-base') {
    if (method === 'POST') { const kb = { id: nextId++, ...body, createTime: now() }; bases.push(kb); return clone(kb) }
    return clone(bases)
  }
  if ((match = p.match(/^\/api\/knowledge-base\/(\d+)$/))) {
    const kb = bases.find(b => String(b.id) === match[1])
    if (method === 'DELETE') { bases = bases.filter(b => b !== kb); docs = docs.filter(d => String(d.kbId) !== match[1]); return null }
    if (method === 'PUT') Object.assign(kb, body)
    return clone(kb)
  }
  if ((match = p.match(/^\/api\/knowledge-base\/(\d+)\/documents(?:\/(\d+))?(?:\/(enabled|retry|chunks))?$/))) {
    const [, kb, id, action] = match, current = Number(url.searchParams.get('current') || 1), size = Number(url.searchParams.get('size') || 10)
    const doc = docs.find(d => String(d.id) === id)
    if (action === 'enabled') { doc.enabled = body.enabled; return null }
    if (action === 'retry') { doc.status = 'success'; doc.chunkCount = 1; doc.errorMessage = null; return clone(doc) }
    if (action === 'chunks') {
      const records = Array.from({ length: doc.chunkCount }, (_, i) => ({ id: i + 1, chunkIndex: i, content: `【界面预览，示例片段 ${i + 1}】\n\n` + '商品及服务条款请以当前生效版本为准。购买前请核对产品名称、版本和适用条件；如有不明确的地方，请联系工作人员确认。\n\n' + '文档上传成功后会解析为多个参考片段，问答时根据问题检索相关资料。' }))
      return { records: records.slice((current - 1) * size, current * size), total: records.length }
    }
    if (method === 'DELETE') { docs = docs.filter(d => d !== doc); return null }
    if (method === 'POST') { const file = body.get('file'); const added = { id: nextId++, kbId: Number(kb), docName: file.name, status: 'success', enabled: 0, chunkCount: 1, createTime: now() }; docs.unshift(added); return clone(added) }
    if (id) return clone(doc)
    const records = docs.filter(d => String(d.kbId) === kb)
    return { records: clone(records.slice((current - 1) * size, current * size)), total: records.length }
  }
  throw new Error('此操作暂未提供界面预览')
}
export async function previewStream(question, conversationId, handler, signal) {
  stopped = false
  const id = conversationId || String(nextId++)
  if (!messages.has(id)) { conversations.unshift({ id, title: question.slice(0, 50), createTime: now(), updateTime: now() }); messages.set(id, []) }
  const history = messages.get(id)
  history.push({ id: nextId++, role: 'user', content: question, status: 'success' })
  handler('meta', JSON.stringify({ conversationId: id, generationId: 'preview-' + Date.now() }))
  await sleep(650)
  let content = ''
  for (let i = 0; i < answer.length; i += 8) {
    if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
    if (stopped) break
    const part = answer.slice(i, i + 8); content += part; handler('delta', part); await sleep(35)
  }
  history.push({ id: nextId++, role: 'assistant', content, status: stopped ? 'failed' : 'success', errorMessage: stopped ? '已停止生成' : null, sources: source, finishReason: stopped ? null : 'stop' })
  handler(stopped ? 'stopped' : 'done', stopped ? JSON.stringify({ message: '已停止生成' }) : '[DONE]')
}
