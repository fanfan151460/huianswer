# Linux 同机试部署

2026-10-17 已在现有短链接服务器上部署前后端。页面入口为 [慧答工作空间](http://8.149.237.177/huianswer/)，接口仍使用 `/huianswer` 前缀。

## 请求与资源

```text
浏览器 / Postman
    → Nginx :80，匹配 /huianswer/
    → 页面：读取 /opt/huianswer/web/current/huianswer/
    → 接口：去掉 /huianswer 前缀，转发 HuiAnswer 127.0.0.1:8084
    → PostgreSQL + pgvector、共享 Redis、共享 RocketMQ、外部模型 API
```

原有短链接仍走 Nginx → 网关 8083，控制台仍在 8080。慧答是独立 Spring Boot 应用，直接由 Nginx 转发；8084 和 PostgreSQL 5432 仅监听回环地址。

| 资源 | 服务器位置 / 用途 |
| --- | --- |
| 当前前端版本 | `/opt/huianswer/web/current`，指向版本根目录；其 `huianswer/` 子目录存放 dist 内容 |
| 当前应用 | `/opt/huianswer/app/huianswer.jar` |
| Spring 外置配置 | `/etc/huianswer/application-server.yml` |
| 应用环境变量 | `/etc/huianswer/app.env`，root 0600；数据库密码和模型密钥在此 |
| PostgreSQL 环境变量 | `/etc/huianswer/postgres.env`，root 0600 |
| 预置账号 | `/etc/huianswer/access.json`，root 0600；管理员 `admin` 使用用户指定的口令，普通用户 `demo` 使用随机密码 |
| 原文件 | `/opt/huianswer/data/documents/{kbId}/{UUID}.扩展名` |
| 临时文件 | `/opt/huianswer/data/tmp` |
| 数据库数据 | `/opt/huianswer/data/postgres`，绑定到 PostgreSQL 容器 |
| 日志 | `/opt/huianswer/logs/application.log`，或 `journalctl -u huianswer` |
| 导入备份与部署记录 | `/opt/huianswer/backups`、`/opt/huianswer/deploy` |

本地开发保留 D 盘路径；服务器使用 `server` profile 覆盖为 Linux 路径。原文件已一同迁移，文档表中的 `file_path` 已改为服务器路径，已有片段和向量无需重新生成。以后迁移磁盘位置时，目录和数据库路径都要处理。

当前只部署一个慧答实例，生产者和消费者能读到同一份本地文件。以后跨机器部署文档消费者，需要共享文件存储或对象存储。

## 配置文件

| 仓库文件 | 服务器目标 | 说明 |
| --- | --- | --- |
| `application-server.yml` | `/etc/huianswer/application-server.yml` | Linux 路径、连接池、线程池和日志配置 |
| `huianswer.service` | `/etc/systemd/system/huianswer.service` | systemd 运行及内存上限 |
| `postgres-compose.yml` | `/opt/huianswer/deploy/postgres-compose.yml` | pgvector 容器、回环端口和数据卷 |
| `nginx-proxy.conf` | `/etc/huianswer/nginx-proxy.conf` | 关闭代理缓冲，允许 SSE 持续输出 |
| `nginx-location.conf` | `/etc/huianswer/nginx-location.conf` | 慧答路由、登录和问答入口限制、阻断公开评测接口 |
| `nginx-limits.conf` | `/etc/nginx/conf.d/huianswer-limits.conf` | 在 Nginx 的 http 层定义限流区域 |

现有 `/etc/nginx/conf.d/shortlink.conf` 中，只有监听 80 且 `server_name 8.149.237.177` 的 server 块新增：

```nginx
include /etc/huianswer/nginx-location.conf;
```

`location /huianswer/` 使用 `root /opt/huianswer/web/current` 返回页面，history 回退为 `/huianswer/index.html`。`location /huianswer/api/` 的 `proxy_pass http://127.0.0.1:8084/api/;` 将部署前缀移除并保留 `/api`；问答路径单独精确代理。静态 assets 缺失返回 404，不回退 HTML。

外置配置通过下面的参数加载，保留 JAR 内基础配置并追加服务器配置：

```text
--spring.profiles.active=server
--spring.config.additional-location=file:/etc/huianswer/
```

systemd 从 `EnvironmentFile=/etc/huianswer/app.env` 注入环境变量，Spring 再解析基础 YAML 中的占位符。密钥、账号密码、数据库备份不放进 Git。

## 资源控制

这是 4 GiB 主机上的演示配置，适合小流量验证，未做并发容量测试。

- Java：堆上限 384 MiB、元空间上限 160 MiB、直接内存上限 64 MiB；systemd 总内存限制 768 MiB。
- PostgreSQL：容器内存上限 384 MiB，CPU 上限 0.5 核，`shared_buffers=64MB`、最大连接 20。
- Hikari：最大 4 个连接、最少 1 个空闲连接。
- 问答线程池：核心 2、最大 4、队列 8；子问题池 4/8/16；摘要池 1/1/8。
- Redis：复用现有实例的 DB 1；RocketMQ 使用慧答自己的 topic 和消费组。
- 评测输出关闭；Nginx 对登录和问答入口做 IP 维度限流。未开启公开注册。

## 本次迁移和验证

构建基线是 `master` 的 `1dba46a`，加上本次线程池配置化改动。使用本机 JDK 17、Maven 构建，再通过密钥 SCP 上传，服务器未安装 Maven。

- PostgreSQL 镜像从本机 Docker 导出、上传并载入；服务器镜像源当时返回 502。
- 迁入 5 个知识库、33 份原文档、122 个 1024 维片段，未迁移本地聊天测试记录及旧 4096 维备份表。
- 验证登录、知识库列表、文档分页、会话列表，以及一条真实流式问答、来源保存和停止生成。
- 此次样例问答首个正文约 3.62 秒，只是一次功能验证，不代表平均值或 P95。
- 通过公开入口上传一份 `deployment-storage-smoke.txt`，确认本地保存 → MQ → 解析 → 向量化 → 入库成功；文档已禁用，未删除。验收后共 34 份文档、123 个片段。
- 原短链接的管理接口、Gitee 跳转和 8080 控制台均保持可用。

验收详情保存在服务器 `/opt/huianswer/deploy/smoke-private.json`、`smoke-public.json`、`smoke-upload.json`，迁入基线记录在 `database-restored.json`。

本次 JAR SHA-256：

```text
d48a555ce70642a2c09700e263e6a9f6981b2ac5d69a4fc912e250b812589e3b
```

## 前端发布与验收

2026-10-17 正式前端版本为 `20261017-112343`，目录为 `/opt/huianswer/web/releases/20261017-112343/huianswer/`。`web/current` 指向该版本的父目录 `20261017-112343`，Nginx 用请求路径拼出实际文件位置。

本机执行 `npm run build`，对 7 份静态文件生成 SHA-256 清单，再打包上传、服务端核对并切换 current 链接。Nginx 配置变更先 `nginx -t`，通过后 reload。旧配置保存在 `/opt/huianswer/backups/nginx-location-20261017-112343.conf`。

公网浏览器验收通过：静态文件全部 SHA-256 相符；登录、新会话问答、历史引用、停止生成、知识库列表、深链接刷新及手机布局正常。原短链接跳转、控制台与管理接口保持可用；公开评测入口和不存在的 assets 均返回 404。样例首个正文约 3.91 秒，仅为此次功能验收观察值。

管理员口令按用户指定值更新，BCrypt 哈希存入数据库，账号记录只存服务器私有文件，旧 admin 登录态已清除。仓库不记录口令。此次发布不需要重启慧答 Java 服务。

发布记录：`/opt/huianswer/deploy/frontend-release.json`；验收记录：`/opt/huianswer/deploy/frontend-verification.json`。后续更新仍先建立新的版本目录，放入 `huianswer/` 子目录，校验后切换 current；保留旧版本以便回退。

## 后续更新 JAR

先在仓库根目录本地构建，测试源码仍会编译，但不运行整套测试：

```powershell
$env:JAVA_HOME = 'D:\develop\java\jdk_17'
& 'D:\develop\apache-maven-3.9.13\bin\mvn.cmd' -B -pl rag -am -DskipTests package
Get-FileHash -Algorithm SHA256 .\rag\target\rag-1.0-SNAPSHOT.jar
scp -i "$env:USERPROFILE\.ssh\id_rsa" .\rag\target\rag-1.0-SNAPSHOT.jar root@8.149.237.177:/opt/huianswer/app/huianswer.jar.new
```

在服务器确认上传文件的 SHA-256 与本地一致，停止后保留旧版本，再复制新包并启动。不要直接覆盖正在运行的 JAR：

```bash
sha256sum /opt/huianswer/app/huianswer.jar.new
systemctl stop huianswer
cp -p /opt/huianswer/app/huianswer.jar "/opt/huianswer/backups/huianswer-$(date +%Y%m%d-%H%M%S).jar"
cp /opt/huianswer/app/huianswer.jar.new /opt/huianswer/app/huianswer.jar
chown huianswer:huianswer /opt/huianswer/app/huianswer.jar
systemctl start huianswer
systemctl status huianswer --no-pager
journalctl -u huianswer -n 50 --no-pager
```

只有修改 systemd 单元时才需要 `systemctl daemon-reload`；修改应用 YAML 或环境变量后重启慧答。修改 Nginx 后先 `nginx -t`，成功再 `systemctl reload nginx`。PostgreSQL 和其他项目无需随 JAR 更新一起重启。

## 接口验证入口

公网请求在原接口前加 `/huianswer`，注意问答路径原有大小写：

```text
POST http://8.149.237.177/huianswer/api/hui-answer/login
GET  http://8.149.237.177/huianswer/api/knowledge-base
POST http://8.149.237.177/huianswer/hui-ansWer/chat/stream
```

登录请求体为 `username`、`password`；从服务器的账号文件取值。登录后仍按现有约定传 `username`、`token` 请求头。服务器上用 `127.0.0.1` 检查 Nginx 时，应加 `Host: 8.149.237.177`，否则会进入默认虚拟主机。直接测试应用可用 `http://127.0.0.1:8084` 加原路径。
