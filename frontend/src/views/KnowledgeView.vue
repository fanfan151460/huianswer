<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import { api, listBases, docPath, formatDate } from '../lib/api'
import { notify } from '../lib/workspace'
const bases = ref([]), loading = ref(true), error = ref(''), selected = ref(null), query = ref('')
const documents = ref([]), docLoading = ref(false), docError = ref(''), page = ref(1), total = ref(0)
const baseModal = ref(false), editing = ref(null), baseName = ref(''), description = ref(''), saving = ref(false)
const fileInput = ref(null), uploading = ref(false), dragOver = ref(false), actionId = ref(null)
const chunkStrategy = ref('OVERLAP')
const chunkDoc = ref(null), chunks = ref([]), chunkPage = ref(1), chunkTotal = ref(0), chunkLoading = ref(false), chunkError = ref('')
const visibleBases = computed(() => bases.value.filter(b => b.name.toLowerCase().includes(query.value.toLowerCase())))
let poll = null, docIndex = 0, chunkIndex = 0, alive = true
const statusLabel = doc => doc.status === 'success' ? '已就绪' : doc.status === 'running' ? '处理中' : doc.status === 'pending' ? '排队中' : '处理失败'
async function loadBases() {
  loading.value = true; error.value = ''
  try { bases.value = await listBases() || []; if (!selected.value) selected.value = bases.value[0] || null }
  catch (e) { error.value = e.message } finally { loading.value = false }
}
async function loadDocuments(silent = false) {
  clearTimeout(poll); const index = ++docIndex
  if (!selected.value) { documents.value = []; total.value = 0; return }
  if (!silent) docLoading.value = true
  docError.value = ''
  const kb = selected.value.id, current = page.value
  try {
    const result = await api(docPath(kb) + `?current=${current}&size=8`)
    if (!alive || index !== docIndex) return
    documents.value = result.records || []; total.value = result.total || 0
    if (documents.value.some(d => ['pending', 'running'].includes(d.status))) poll = setTimeout(() => loadDocuments(true), 3000)
  } catch (e) { if (index === docIndex) docError.value = e.message }
  finally { if (index === docIndex) docLoading.value = false }
}
watch(() => selected.value?.id, () => { page.value = 1; documents.value = []; void loadDocuments() })
watch(page, () => loadDocuments())
onMounted(loadBases)
onBeforeUnmount(() => { alive = false; ++docIndex; ++chunkIndex; clearTimeout(poll) })
function openBase(base = null) { editing.value = base; baseName.value = base?.name || ''; description.value = base?.description || ''; baseModal.value = true }
async function saveBase() {
  if (!baseName.value.trim()) return
  saving.value = true
  try {
    const result = await api('/api/knowledge-base' + (editing.value ? '/' + editing.value.id : ''), { method: editing.value ? 'PUT' : 'POST', body: { name: baseName.value.trim(), description: description.value.trim() } })
    baseModal.value = false; await loadBases(); selected.value = bases.value.find(b => String(b.id) === String(result.id)) || selected.value; notify(editing.value ? '知识库已更新' : '知识库已创建')
  } catch (e) { notify(e.message, 'error') } finally { saving.value = false }
}
async function deleteBase() {
  const base = selected.value
  if (!base || !window.confirm(`删除知识库“${base.name}”？其中的文档和片段也会被删除，此操作无法恢复。`)) return
  saving.value = true
  try { await api('/api/knowledge-base/' + base.id, { method: 'DELETE' }); selected.value = null; await loadBases(); notify('知识库已删除') }
  catch (e) { notify(e.message, 'error') } finally { saving.value = false }
}
async function uploadFiles(fileList) {
  if (!selected.value || uploading.value) return
  const files = [...(fileList || [])]; if (!files.length) return
  if (files.some(f => !/\.(txt|md|pdf|doc|docx)$/i.test(f.name) || !f.size || f.size > 10 * 1024 * 1024)) { notify('请选择非空的 TXT、MD、PDF 或 Word 文件，每份不超过 10 MB', 'error'); return }
  uploading.value = true; const kb = selected.value.id, strategy = chunkStrategy.value
  try {
    for (const file of files) { const body = new FormData(); body.append('file', file); body.append('strategy', strategy); const result = await api(docPath(kb), { method: 'POST', body }); if (result.status === 'failed') throw new Error(result.errorMessage || '文档任务提交失败，可在列表中重试') }
    if (selected.value?.id === kb) { if (page.value !== 1) page.value = 1; else await loadDocuments() }
    notify(files.length === 1 ? '文档已上传，正在处理' : `${files.length} 份文档已上传，正在处理`)
  } catch (e) { notify(e.message, 'error'); if (selected.value?.id === kb) await loadDocuments() }
  finally { uploading.value = false; if (fileInput.value) fileInput.value.value = '' }
}
function drop(e) { e.preventDefault(); dragOver.value = false; void uploadFiles(e.dataTransfer.files) }
async function documentAction(doc, action) {
  const kb = selected.value.id
  if (action === 'delete' && !window.confirm(`删除文档“${doc.docName}”？对应片段将被删除，无法恢复。`)) return
  actionId.value = doc.id
  try {
    if (action === 'toggle') await api(docPath(kb, doc.id) + '/enabled', { method: 'PUT', body: { enabled: doc.enabled === 0 ? 1 : 0 } })
    else await api(docPath(kb, doc.id) + (action === 'retry' ? '/retry' : ''), { method: action === 'retry' ? 'POST' : 'DELETE' })
    if (selected.value?.id === kb) { if (action === 'delete' && documents.value.length === 1 && page.value > 1) page.value--; else await loadDocuments(true) }
    notify(action === 'toggle' ? doc.enabled === 0 ? '文档已停用' : '文档已启用' : action === 'retry' ? '已重新提交处理' : '文档已删除')
  } catch (e) { notify(e.message, 'error') } finally { actionId.value = null }
}
async function openChunks(doc) { chunkDoc.value = doc; chunkPage.value = 1; chunks.value = []; await loadChunks() }
async function loadChunks() {
  const index = ++chunkIndex; chunkLoading.value = true; chunkError.value = ''
  try { const result = await api(docPath(selected.value.id, chunkDoc.value.id) + `/chunks?current=${chunkPage.value}&size=5`); if (index === chunkIndex) { chunks.value = result.records || []; chunkTotal.value = result.total || 0 } }
  catch (e) { if (index === chunkIndex) chunkError.value = e.message }
  finally { if (index === chunkIndex) chunkLoading.value = false }
}
function closeChunks() { ++chunkIndex; chunkDoc.value = null; chunkTotal.value = 0 }
</script>
<template><div class="knowledge-page"><div class="page-heading"><div><span class="eyebrow">YOUR KNOWLEDGE, CONNECTED</span><h1>让每份资料，都用得上。</h1><p class="muted">整理知识库与文档，为回答提供可靠的依据。</p></div><button class="button primary" @click="openBase()"><Icon name="plus" :size="18"/>新建知识库</button></div>
  <div v-if="loading && !bases.length" class="loading-state"><span class="spinner"/>正在读取知识库…</div>
  <div v-else-if="error" class="empty-state"><Icon name="info" :size="28"/><h3>知识库加载失败</h3><p>{{ error }}</p><button class="button secondary" @click="loadBases">重新加载</button></div>
  <div v-else-if="!bases.length" class="empty-state knowledge-empty"><Icon name="book" :size="36"/><h2>给知识一个安放的地方</h2><p>先创建知识库，再上传你的第一份文档。</p><button class="button primary" @click="openBase()">创建知识库</button></div>
  <div v-else class="knowledge-workspace"><aside class="base-list"><div class="base-list-title"><span>全部知识库</span><span>{{ bases.length }}</span></div><label class="search-input"><Icon name="search" :size="17"/><input v-model="query" placeholder="搜索知识库" aria-label="搜索知识库"/></label><p v-if="!visibleBases.length" class="muted no-base-match">没有匹配的知识库</p><button v-for="(base, index) in visibleBases" :key="base.id" :class="['base-item', { active: selected?.id === base.id }]" :disabled="uploading" @click="selected = base"><span :class="['base-symbol', 'tone-' + index % 4]"><Icon name="book" :size="19"/></span><span><strong>{{ base.name }}</strong><small>{{ base.description || '暂未填写说明' }}</small></span><Icon name="chevron" :size="15"/></button></aside>
    <section v-if="selected" class="documents-area"><div class="base-detail-head"><div><h2>{{ selected.name }}</h2><p>{{ selected.description || '为这个知识库补充说明，方便理解资料的适用范围。' }}</p></div><div class="base-actions"><button class="icon-button" aria-label="编辑知识库" title="编辑知识库" :disabled="saving || uploading" @click="openBase(selected)"><Icon name="edit" :size="18"/></button><button class="icon-button danger-text" aria-label="删除知识库" title="删除知识库" :disabled="saving || uploading" @click="deleteBase"><Icon name="trash" :size="18"/></button></div></div>
    <div class="upload-options"><label for="chunk-strategy">分块方式</label><select id="chunk-strategy" v-model="chunkStrategy" :disabled="uploading"><option value="OVERLAP">滑动重叠分块</option><option value="RECURSIVE">递归分块</option></select></div>
    <div :class="['upload-zone', { dragging: dragOver, uploading }]" @dragover.prevent="dragOver = true" @dragleave.prevent="dragOver = false" @drop="drop"><span class="upload-symbol"><span v-if="uploading" class="spinner"/><Icon v-else name="upload" :size="24"/></span><div><strong>{{ uploading ? '正在上传文档…' : '把资料带进来' }}</strong><p>拖拽文件到这里，或选择本地文件</p><small>TXT、Markdown、文本型 PDF、Word（DOC/DOCX）· 每份不超过 10 MB</small></div><button class="button secondary" :disabled="uploading" @click="fileInput.click()"><Icon name="plus" :size="16"/>选择文件</button><input ref="fileInput" type="file" accept=".txt,.md,.pdf,.doc,.docx" multiple hidden @change="uploadFiles($event.target.files)"/></div>
    <div class="documents-toolbar"><h3>文档<span>{{ total }}</span></h3><button class="text-button" :disabled="docLoading" @click="loadDocuments()"><Icon name="refresh" :size="15"/>刷新</button></div>
    <div v-if="docLoading" class="loading-state"><span class="spinner"/>正在读取文档…</div>
    <div v-else-if="docError" class="inline-error" role="alert">{{ docError }}<button class="text-button" @click="loadDocuments()">重试</button></div>
    <div v-else-if="!documents.length" class="document-empty"><Icon name="file" :size="30"/><h3>这里还没有文档</h3><p>上传后，慧答会自动解析并整理内容。</p></div>
    <div v-else class="document-table-wrap"><table class="document-table"><thead><tr><th>文档名称</th><th>处理状态</th><th>片段</th><th>参与问答</th><th>操作</th></tr></thead><tbody><tr v-for="doc in documents" :key="doc.id"><td><div class="document-name"><span class="file-symbol"><Icon name="file" :size="20"/></span><div><strong>{{ doc.docName }}</strong><small>{{ formatDate(doc.createTime) }}</small><small v-if="doc.errorMessage" class="danger-text">{{ doc.errorMessage }}</small></div></div></td><td><span :class="['status-pill', doc.status]"><span/>{{ statusLabel(doc) }}</span></td><td class="number-cell">{{ doc.status === 'success' ? doc.chunkCount || 0 : '待完成' }}</td><td><button :class="['switch', { on: doc.enabled === 0 }]" role="switch" :aria-checked="doc.enabled === 0" :aria-label="(doc.enabled === 0 ? '停用' : '启用') + doc.docName" :disabled="actionId !== null" @click="documentAction(doc, 'toggle')"><span/></button></td><td><div class="table-actions"><button v-if="doc.status === 'failed'" class="text-button" :disabled="actionId !== null" @click="documentAction(doc, 'retry')">重试</button><button v-else class="icon-button" :disabled="doc.status !== 'success'" aria-label="查看文档片段" title="查看片段" @click="openChunks(doc)"><Icon name="eye" :size="17"/></button><button class="icon-button danger-text" aria-label="删除文档" title="删除文档" :disabled="actionId !== null" @click="documentAction(doc, 'delete')"><Icon name="trash" :size="17"/></button></div></td></tr></tbody></table></div>
    <div v-if="total > 8" class="pagination"><span>共 {{ total }} 份文档</span><div><button class="icon-button previous" :disabled="page <= 1 || docLoading" aria-label="上一页" @click="page--"><Icon name="chevron" :size="17"/></button><span>{{ page }} / {{ Math.ceil(total / 8) }}</span><button class="icon-button" :disabled="page * 8 >= total || docLoading" aria-label="下一页" @click="page++"><Icon name="chevron" :size="17"/></button></div></div>
    <p class="documents-footnote"><Icon name="info" :size="15"/>文档处理完成并启用后，即可作为问答资料。</p></section>
  </div></div>
  <Modal :open="baseModal" :title="editing ? '编辑知识库' : '创建知识库'" :busy="saving" @close="baseModal = false"><form @submit.prevent="saveBase"><label class="field-label" for="base-name">知识库名称</label><input id="base-name" v-model="baseName" maxlength="128" placeholder="例如：星澜商城售后规则" required autofocus/><label class="field-label" for="base-description">业务说明</label><textarea id="base-description" v-model="description" rows="4" maxlength="1000" placeholder="说明资料涵盖的主题、产品和适用范围。"/><p class="field-help">清晰的说明，有助于慧答找到合适的资料。</p><div class="modal-actions"><button type="button" class="button secondary" :disabled="saving" @click="baseModal = false">取消</button><button class="button primary" :disabled="saving || !baseName.trim()">{{ saving ? '保存中…' : '保存知识库' }}</button></div></form></Modal>
  <Modal :open="Boolean(chunkDoc)" :title="chunkDoc?.docName || '文档片段'" wide @close="closeChunks"><div class="chunk-panel"><p class="muted">文档解析后的参考片段，按原文顺序展示。</p><div v-if="chunkLoading" class="loading-state"><span class="spinner"/>正在读取片段…</div><div v-else-if="chunkError" class="inline-error">{{ chunkError }}</div><div v-else-if="!chunks.length" class="empty-state">暂无片段</div><section v-for="chunk in chunkLoading ? [] : chunks" :key="chunk.id" class="chunk-item"><span>片段 {{ chunk.chunkIndex + 1 }}</span><p>{{ chunk.content }}</p></section><div v-if="chunkTotal > 5" class="pagination"><span>共 {{ chunkTotal }} 个片段</span><div><button class="icon-button previous" aria-label="上一页片段" :disabled="chunkPage <= 1 || chunkLoading" @click="chunkPage--; loadChunks()"><Icon name="chevron" :size="17"/></button><span>{{ chunkPage }} / {{ Math.ceil(chunkTotal / 5) }}</span><button class="icon-button" aria-label="下一页片段" :disabled="chunkPage * 5 >= chunkTotal || chunkLoading" @click="chunkPage++; loadChunks()"><Icon name="chevron" :size="17"/></button></div></div></div></Modal>
</template>

<style scoped>
.upload-options { display: flex; align-items: center; gap: 12px; margin-top: 18px; color: var(--muted); font-size: 13px; }
.upload-options select { min-height: 40px; max-width: 100%; padding: 8px 12px; border: 1px solid #dce2d7; border-radius: var(--r-sm); background: var(--surface); color: var(--ink); font: inherit; }
.upload-options select:focus-visible { outline: 2px solid #91ae9b; outline-offset: 1px; }
.upload-options select:disabled { opacity: .55; cursor: not-allowed; }
</style>
