import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.VITE_PROXY_TARGET || 'http://8.149.237.177'
  const entry = () => ({ target, changeOrigin: true,
    ...(env.VITE_PROXY_STRIP_PREFIX === 'true' ? { rewrite: path => path.replace(/^\/huianswer/, '') } : {}) })
  const proxy = { '/huianswer/api/': entry(), '/huianswer/hui-ansWer/': entry() }
  return {
    base: '/huianswer/', plugins: [vue()],
    server: { host: '127.0.0.1', port: 5174, strictPort: true, proxy },
    preview: { host: '127.0.0.1', port: 4174, proxy },
    build: { sourcemap: false }
  }
})
