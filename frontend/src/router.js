import { createRouter, createWebHistory } from 'vue-router'
import { signedIn, isAdmin } from './lib/session'
import { chat, notify } from './lib/workspace'
const router = createRouter({ history: createWebHistory(import.meta.env.BASE_URL), routes: [
  { path: '/login', name: 'login', component: () => import('./views/LoginView.vue') },
  { path: '/', redirect: '/chat' },
  { path: '/chat/:id?', name: 'chat', component: () => import('./views/ChatView.vue') },
  { path: '/knowledge', name: 'knowledge', component: () => import('./views/KnowledgeView.vue'), meta: { admin: true } },
  { path: '/:pathMatch(.*)*', redirect: '/chat' }
] })
router.beforeEach((to, from) => {
  if (to.name !== 'login' && !signedIn()) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.name === 'login' && signedIn()) return '/chat'
  if (to.meta.admin && !isAdmin()) return '/chat'
  if (chat.generating && to.name !== 'login' && !(to.name === 'chat' && String(to.params.id) === chat.conversationId)) {
    notify('请等待回答结束，或先停止生成'); return false
  }
})
export default router
