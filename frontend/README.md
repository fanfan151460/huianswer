# 慧答前端

Vue 3 + Vite + Vue Router，使用原生 fetch 读取 POST SSE。普通 CSS 变量统一管理布局和颜色，Markdown 使用 markdown-it 与 DOMPurify。

## 本地运行

Node.js 20.19+ 或 22.12+。在此目录执行：

```powershell
npm install
npm run dev
```

打开 http://127.0.0.1:5174/huianswer/ 。本地开发服务器默认代理到已部署的服务器，账号使用服务器预置账号。登录页的“先看看界面”只在开发模式显示，使用内存示例数据，不调用模型、不修改服务器数据。刷新会重置示例数据。

若联调本机 Java 后端，复制 `.env.example` 为 `.env.local`，将 `VITE_PROXY_TARGET` 改为实际地址，并设 `VITE_PROXY_STRIP_PREFIX=true`，再重启 Vite。

## 已对接功能

- 账号登录、鉴权请求头、登录过期跳转；按当前后端规则，仅 admin 显示知识库管理。
- 新会话、历史会话、重命名与删除。
- SSE 的 meta、delta、done、stopped、error；增量解码、Markdown、停止生成和断线提示。
- 回答结束后调用历史接口获取 sources，显示引用与来源列表，无需修改后端 SSE。
- 知识库创建、编辑与删除，文档上传、状态轮询、重试、启停、删除、分页与片段查看。
- 桌面与手机布局；弹窗键盘焦点、减少动画偏好、图标按钮可访问名称。

会话和文档的删除操作都要求确认。点击退出会清除浏览器保存的登录态；当前后端没有退出登录接口。

## 文件入口

`src/lib/api.js` 管普通接口，`sse.js` 管流解析，`workspace.js` 管会话和生成状态，`views` 放三类页面。`preview.js` 仅被开发模式动态导入，生产构建移除预览入口和示例模块。

```powershell
npm run build
```

输出 `dist/` 文件夹，资源和路由前缀均为 `/huianswer/`。2026-10-17 已部署到 http://8.149.237.177/huianswer/ 。Nginx 将 `/huianswer/api/` 与大小写特殊的问答路径代理到 8084，为 `/huianswer/` 提供静态文件和 history 回退；生产版本没有示例预览入口。静态文件与版本目录见 `deploy/server/README.md`。
