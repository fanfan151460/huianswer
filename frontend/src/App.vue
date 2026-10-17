<script setup>
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import Icon from './components/Icon.vue'
import BrandMark from './components/BrandMark.vue'
import Modal from './components/Modal.vue'
import { session, signedIn, isAdmin, clearSession } from './lib/session'
import { api } from './lib/api'
import { workspace, chat, notify, refreshConversations, disconnect, requestStop } from './lib/workspace'
const router = useRouter(), route = useRoute()
const mobileOpen = ref(false), search = ref(''), searchOpen = ref(false)
const renaming = ref(null), title = ref(''), saving = ref(false)
const loggedIn = computed(signedIn), admin = computed(isAdmin)
const conversations = computed(() => workspace.conversations.filter(c => (c.title || '新对话').toLowerCase().includes(search.value.toLowerCase())))
const groups = computed(() => {
  const today = new Date(); today.setHours(0, 0, 0, 0)
  const buckets = [{ name: '今天', list: [] }, { name: '最近 7 天', list: [] }, { name: '更早的对话', list: [] }]
  for (const c of conversations.value) { const ago = today.getTime() - new Date(c.updateTime || c.createTime).getTime(); buckets[ago <= 0 ? 0 : ago < 7 * 864e5 ? 1 : 2].list.push(c) }
  return buckets.filter(g => g.list.length)
})
watch(() => route.fullPath, () => { mobileOpen.value = false })
watch(loggedIn, value => { if (value) void refreshConversations() }, { immediate: true })
function expired() { disconnect(); chat.generating = false; void router.replace({ name: 'login' }); notify('登录已失效，请重新登录', 'error') }
onMounted(() => window.addEventListener('huianswer:expired', expired))
onBeforeUnmount(() => window.removeEventListener('huianswer:expired', expired))
async function logout() {
  if (chat.generating) { if (!window.confirm('回答仍在生成，停止并退出？')) return; await requestStop(); disconnect() }
  clearSession(); chat.generating = false; chat.messages = []; chat.conversationId = null; workspace.conversations = []
  void router.replace('/login')
}
async function removeConversation(c) {
  if (!window.confirm(`删除“${c.title || '新对话'}”？这会同时删除该会话的消息记录，无法恢复。`)) return
  try { await api('/api/hui-answer/conversation/' + c.id, { method: 'DELETE' }); await refreshConversations(); if (String(route.params.id) === String(c.id)) await router.push('/chat'); notify('会话已删除') }
  catch (e) { notify(e.message, 'error') }
}
async function rename() {
  if (!title.value.trim()) return
  saving.value = true
  try { await api('/api/hui-answer/conversation/' + renaming.value.id, { method: 'PUT', body: { title: title.value.trim() } }); renaming.value = null; await refreshConversations(); notify('名称已更新') }
  catch (e) { notify(e.message, 'error') } finally { saving.value = false }
}
</script>
<template>
  <RouterView v-if="route.name === 'login'" />
  <div v-else class="app-shell">
    <button v-if="mobileOpen" class="sidebar-backdrop" aria-label="关闭导航" @click="mobileOpen = false" />
    <aside :class="['sidebar', { 'is-open': mobileOpen }]">
      <RouterLink to="/chat" class="brand"><BrandMark/><span>慧答<span class="brand-latin">HuiAnswer</span></span></RouterLink>
      <button class="new-chat-button" :disabled="chat.generating" @click="router.push('/chat')"><Icon name="plus" :size="19"/>开启新对话<span class="new-chat-hint">↗</span></button>
      <nav class="workspace-nav" aria-label="工作区导航">
        <RouterLink to="/chat" :class="{ active: route.name === 'chat' }"><Icon name="chat"/>问答助手</RouterLink>
        <RouterLink v-if="admin" to="/knowledge" :class="{ active: route.name === 'knowledge' }"><Icon name="book"/>知识库管理</RouterLink>
      </nav>
      <div class="history-heading"><span>历史对话</span><button class="icon-button" aria-label="搜索历史对话" @click="searchOpen = !searchOpen"><Icon name="search" :size="16"/></button></div>
      <input v-if="searchOpen" v-model="search" class="history-search" placeholder="搜索会话名称" aria-label="搜索会话名称" />
      <div class="conversation-list">
        <div v-if="workspace.loading && !workspace.conversations.length" class="sidebar-loading"><span class="skeleton"/><span class="skeleton"/><span class="skeleton"/></div>
        <div v-else-if="workspace.error" class="sidebar-empty"><p>暂时无法加载会话</p><button class="text-button" @click="refreshConversations">重试</button></div>
        <div v-else-if="!conversations.length" class="sidebar-empty">{{ search ? '没有匹配的对话' : '新的想法，从第一次提问开始' }}</div>
        <section v-for="group in groups" :key="group.name" class="conversation-group"><h3>{{ group.name }}</h3>
          <div v-for="c in group.list" :key="c.id" :class="['conversation-row', { selected: String(route.params.id) === String(c.id) }]">
            <RouterLink :to="'/chat/' + c.id" :title="c.title || '新对话'"><Icon name="chat" :size="15"/><span>{{ c.title || '新对话' }}</span></RouterLink>
            <details v-if="!chat.generating" class="row-menu"><summary aria-label="会话操作"><Icon name="more" :size="16"/></summary><div class="row-menu-options"><button @click="renaming = c; title = c.title; $event.currentTarget.closest('details').open = false"><Icon name="edit" :size="16"/>重命名</button><button class="danger-text" @click="removeConversation(c); $event.currentTarget.closest('details').open = false"><Icon name="trash" :size="16"/>删除对话</button></div></details>
          </div>
        </section>
      </div>
      <div class="sidebar-bottom"><div class="workspace-note"><Icon name="shield" :size="17"/><span>让知识有据可循</span></div><div class="account-row"><span class="avatar">{{ session.preview ? '预' : (session.username || 'U').slice(0, 1).toUpperCase() }}</span><div><strong>{{ session.preview ? '预览访客' : session.username }}</strong><small>{{ session.preview ? '示例数据，仅在本地展示' : admin ? '管理员' : '个人空间' }}</small></div><button class="icon-button" :aria-label="session.preview ? '退出预览' : '退出登录'" :title="session.preview ? '退出预览' : '退出登录'" @click="logout"><Icon name="logout" :size="18"/></button></div></div>
    </aside>
    <main class="workspace-main"><header class="workspace-header"><div class="header-location"><button class="icon-button mobile-menu" aria-label="展开导航" @click="mobileOpen = true"><Icon name="menu"/></button><span class="header-section">工作空间</span><span class="header-slash">/</span><strong>{{ route.name === 'knowledge' ? '知识库管理' : '问答助手' }}</strong></div><div v-if="session.preview" class="preview-badge"><span/>本地预览 · 示例数据</div><span v-else class="header-caption">你的知识，随问随答</span></header><RouterView/></main>
  </div>
  <div class="toast-stack" aria-live="polite"><div v-for="item in workspace.notices" :key="item.id" :class="['toast', item.type]"><Icon :name="item.type === 'error' ? 'info' : 'check'" :size="18"/>{{ item.text }}</div></div>
  <Modal :open="Boolean(renaming)" title="重命名会话" :busy="saving" @close="renaming = null"><form @submit.prevent="rename"><label class="field-label" for="conversation-title">会话名称</label><input id="conversation-title" v-model="title" maxlength="128" required autofocus/><div class="modal-actions"><button type="button" class="button secondary" :disabled="saving" @click="renaming = null">取消</button><button class="button primary" :disabled="saving || !title.trim()">{{ saving ? '保存中…' : '保存名称' }}</button></div></form></Modal>
</template>
