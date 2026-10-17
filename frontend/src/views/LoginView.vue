<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BrandMark from '../components/BrandMark.vue'
import Icon from '../components/Icon.vue'
import { login } from '../lib/api'
import { saveSession, previewSession } from '../lib/session'
import { chat } from '../lib/workspace'
const router = useRouter(), route = useRoute()
const username = ref(''), password = ref(''), visible = ref(false), busy = ref(false), error = ref('')
const canPreview = import.meta.env.DEV
async function submit() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    const result = await login({ username: username.value.trim(), password: password.value })
    if (!result?.token) throw new Error('登录响应不完整，请重试')
    saveSession(username.value.trim(), result.token); password.value = ''; chat.conversationId = null; chat.messages = []
    const redirect = String(route.query.redirect || '/chat')
    await router.replace(redirect.startsWith('/chat') || redirect === '/knowledge' ? redirect : '/chat')
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
function preview() { previewSession(); chat.conversationId = null; chat.messages = []; void router.replace('/chat') }
</script>
<template><div class="login-page"><section class="login-story"><a class="brand" href="./"><BrandMark/><span>慧答<span class="brand-latin">HuiAnswer</span></span></a><div class="login-editorial"><span class="eyebrow">A SPACE FOR YOUR KNOWLEDGE</span><h1>把散落的知识，<br>变成清晰的答案。</h1><p>连接文档与问题，让每一次探索<br class="desktop-break">都有依据，也有新的发现。</p><div class="login-paper"><div class="paper-top"><Icon name="file" :size="21"/><span>一份资料，可以解答很多问题</span></div><div class="paper-line long"/><div class="paper-line"/><div class="paper-line medium"/><div class="paper-answer"><BrandMark/><span>读懂资料，也读懂你的问题。<small>检索 · 理解 · 引用</small></span><span class="paper-citation">[1]</span></div></div></div><footer>知识不止于存储，更在于被理解。</footer></section>
  <section class="login-form-area"><div class="login-form"><span class="eyebrow">WELCOME BACK</span><h2>欢迎回来</h2><p class="muted">登录你的工作空间，继续探索。</p><form @submit.prevent="submit"><label class="field-label" for="username">账号</label><input id="username" v-model="username" autocomplete="username" placeholder="请输入账号" required :disabled="busy"/><label class="field-label" for="password">密码</label><div class="password-input"><input id="password" v-model="password" :type="visible ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入密码" required :disabled="busy"/><button type="button" class="icon-button" :aria-label="visible ? '隐藏密码' : '显示密码'" :aria-pressed="visible" @click="visible = !visible"><Icon name="eye" :size="18"/></button></div><p v-if="error" class="form-error" role="alert"><Icon name="info" :size="16"/>{{ error }}</p><button class="button primary login-submit" :disabled="busy || !username.trim() || !password"><span v-if="busy" class="spinner"/>{{ busy ? '正在登录…' : '登录工作空间' }}<Icon v-if="!busy" name="chevron" :size="17"/></button></form><p class="login-account-note">账号由管理员提供，暂不开放自主注册。</p><button v-if="canPreview" class="preview-link" @click="preview">先看看界面<Icon name="chevron" :size="15"/></button></div><small class="login-copyright">HuiAnswer · 让知识有回响</small></section></div></template>
