<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from '../components/Icon.vue'
import BrandMark from '../components/BrandMark.vue'
import MarkdownText from '../components/MarkdownText.vue'
import { chat, workspace, sendQuestion, loadConversation, requestStop, notify } from '../lib/workspace'
const route = useRoute(), router = useRouter()
const draft = ref(''), textarea = ref(null), scroller = ref(null), following = ref(true)
const sourceMessage = ref(null), selectedSource = ref(null)
const title = computed(() => workspace.conversations.find(c => String(c.id) === String(route.params.id))?.title || '新的对话')
const suggestions = [
  { icon: 'book', label: '查询业务规则', question: '星澜商城质量问题退货的运费由谁承担？' },
  { icon: 'layers', label: '比较不同方案', question: '星澜基础版和 Pro 版的请求超时有什么区别？' },
  { icon: 'file', label: '梳理操作步骤', question: '星澜商城申请退货需要准备什么材料？' }
]
watch(() => route.params.id, id => {
  sourceMessage.value = null; draft.value = ''
  if (chat.generating || (id && String(id) === chat.conversationId && chat.messages.length)) return
  void loadConversation(id).then(scrollBottom)
}, { immediate: true })
watch(() => chat.messages.map(m => m.content?.length || 0).join(','), async () => { if (following.value) await scrollBottom() })
async function scrollBottom() { await nextTick(); if (scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight }
function trackScroll() { const el = scroller.value; following.value = el.scrollHeight - el.scrollTop - el.clientHeight < 100 }
function resize() { if (!textarea.value) return; textarea.value.style.height = 'auto'; textarea.value.style.height = Math.min(textarea.value.scrollHeight, 170) + 'px' }
async function send(value) {
  const question = (typeof value === 'string' ? value : draft.value).trim()
  if (!question || chat.generating || chat.loading) return
  draft.value = ''; following.value = true; sourceMessage.value = null; await nextTick(); resize()
  await sendQuestion(question, id => { if (String(route.params.id) !== id) void router.replace('/chat/' + id) })
}
function keydown(e) { if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); void send() } }
function sources(message, index = null) { sourceMessage.value = message; selectedSource.value = index }
async function copy(text) { try { await navigator.clipboard.writeText(text); notify('回答已复制') } catch { notify('浏览器未允许复制，请手动选择文本', 'error') } }
</script>
<template><div class="chat-layout"><section class="chat-pane">
  <div v-if="chat.messages.length || chat.loading || route.params.id" class="conversation-heading"><span class="conversation-heading-title">{{ title }}</span><span class="quiet-label">{{ chat.generating ? '正在生成回答' : '对话记录' }}</span></div>
  <div ref="scroller" class="chat-scroll" @scroll="trackScroll">
    <div v-if="chat.loading" class="chat-loading"><span class="spinner"/>正在读取对话…</div>
    <div v-else-if="!chat.messages.length && !chat.error" class="welcome"><div class="welcome-overline"><BrandMark/><span>从一个好问题开始</span></div><h1>你好，今天想了解什么？</h1><p>把问题交给慧答，从你的资料中找到答案。</p><div class="welcome-rule"/><span class="eyebrow">试着从这里开始</span><div class="suggestions"><button v-for="item in suggestions" :key="item.label" class="suggestion" @click="send(item.question)"><Icon :name="item.icon" :size="21"/><strong>{{ item.label }}</strong><span>{{ item.question }}</span><Icon name="chevron" :size="15"/></button></div><div class="welcome-footnote"><Icon name="link" :size="15"/>回答附带资料来源，方便你继续查证</div></div>
    <div v-else class="messages">
      <article v-for="message in chat.messages" :key="message.id" :class="['message', message.role]">
        <template v-if="message.role === 'user'"><div class="user-bubble">{{ message.content }}</div></template>
        <template v-else><div class="assistant-label"><BrandMark/><strong>慧答</strong><span v-if="message.status === 'generating'" class="quiet-label">{{ message.content ? '正在回答' : chat.stopping ? '正在停止' : '正在查找相关资料' }}</span></div>
          <div v-if="!message.content && message.status === 'generating'" class="thinking" role="status" aria-label="等待回答"><i/><i/><i/></div>
          <MarkdownText v-else-if="message.content" :content="message.content" :source-count="message.sources?.length || 0" @citation="n => sources(message, n)"/>
          <p v-if="message.status === 'failed' || message.status === 'stopped'" :class="['message-status', { failed: message.status === 'failed' }]"><Icon :name="message.status === 'stopped' ? 'stop' : 'info'" :size="14"/>{{ message.errorMessage || '回答未完成' }}</p>
          <div v-if="message.status !== 'generating' && message.content" class="message-actions"><button v-if="message.sources?.length" class="source-button" @click="sources(message)"><Icon name="file" :size="15"/>查看来源<span>{{ message.sources.length }}</span><Icon name="chevron" :size="13"/></button><span v-else class="quiet-label">{{ message.status === 'success' ? '慧答回复' : '' }}</span><button class="icon-button" aria-label="复制回答" title="复制回答" @click="copy(message.content)"><Icon name="copy" :size="16"/></button><span v-if="message.finishReason === 'length'" class="quiet-label">已达到本次回答长度上限</span></div>
        </template>
      </article>
    </div>
    <div v-if="chat.error && !chat.messages.length" class="empty-state"><Icon name="info" :size="28"/><h3>暂时无法读取对话</h3><p>{{ chat.error }}</p><button class="button secondary" @click="loadConversation(route.params.id)">重新加载</button></div>
  </div>
  <div class="composer-area"><button v-if="!following && chat.messages.length" class="jump-bottom icon-button" aria-label="滚动到最新回答" @click="following = true; scrollBottom()"><Icon name="down"/></button><div class="composer"><textarea ref="textarea" v-model="draft" placeholder="有什么问题，尽管问…" aria-label="输入问题" rows="2" maxlength="20000" :disabled="chat.loading" @input="resize" @keydown="keydown"/><div class="composer-toolbar"><span class="composer-mode"><Icon name="book" :size="15"/>知识库问答<span class="mode-dot"/>自动查找相关资料</span><button v-if="chat.generating" class="stop-button" :disabled="chat.stopping" @click="requestStop"><Icon name="stop" :size="14"/>{{ chat.stopping ? '正在停止' : '停止生成' }}</button><button v-else class="send-button" aria-label="发送问题" :disabled="!draft.trim() || chat.loading" @click="send()"><Icon name="arrow" :size="21"/></button></div></div><div class="composer-hint"><span>回答由 AI 生成，请结合引用资料核实重要信息。</span><span class="keyboard-hint">Enter 发送 · Shift + Enter 换行</span></div></div>
</section>
<aside v-if="sourceMessage" class="sources-panel"><header><div><span class="eyebrow">REFERENCES</span><h2>回答的依据</h2></div><button class="icon-button" aria-label="关闭来源面板" @click="sourceMessage = null"><Icon name="close"/></button></header><p class="muted sources-intro">以下是本次回答使用的参考资料。</p><ol><li v-for="(source, index) in sourceMessage.sources" :key="index" :class="{ highlighted: selectedSource === index + 1 }"><span class="source-number">{{ index + 1 }}</span><div><strong>{{ source.docName }}</strong><span>参考片段 {{ index + 1 }}</span></div><Icon name="file" :size="17"/></li></ol><div class="sources-note"><Icon name="info" :size="16"/><p>来源列表记录本次参考资料，不代表每段内容都被答案直接引用。</p></div></aside>
</div></template>
