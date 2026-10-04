# HuiAnswer

Java RAG 学习项目。当前阶段：知识库 CRUD，以及文档上传、本地保存和文本解析。

## 当前实现

- framework：沿用已有统一返回、异常处理等基础设施。
- rag：应用启动入口、知识实体、Mapper 和数据库类型转换；目前一起启动一个进程。
- 三张表：t_knowledge_base、t_knowledge_document、t_knowledge_chunk。
- 片段原文和嵌入向量同表，向量列为 vector(4096)。
- 建表、知识库 CRUD 和文档上传/解析已实现；切分、Embedding、检索和问答接口会按课程逐步实现。

## 知识库 CRUD

默认应用地址：http://localhost:8080。IDEA 中已经运行的旧应用需要重启后才会加载本次修改。

| 方法 | 地址 | 功能 |
| --- | --- | --- |
| POST | /api/knowledge-base | 创建知识库 |
| GET | /api/knowledge-base | 查询未删除的知识库列表，按 ID 倒序 |
| GET | /api/knowledge-base/{id} | 查询未删除的单个知识库 |
| PUT | /api/knowledge-base/{id} | 修改知识库名称 |
| DELETE | /api/knowledge-base/{id} | 删除知识库及关联资料 |

创建请求的 Content-Type 为 application/json，正文：

```json
{"name":"员工制度"}
```

返回 data 中包含 id、name、createTime、updateTime。名称去除首尾空白后保存，不能为空，长度最多 128。
当前名称允许重复；ID 用于区分知识库。

沿用 framework 的 Result 契约：成功 code 为字符串 "0"；参数错误/知识库不存在 code 为 "A000001"。
业务和参数错误目前仍返回 HTTP 200，调用方需要读取 code 判断结果。
响应使用 KnowledgeBaseRespDTO，避免直接输出实体中的 deleted 字段。
当前尚未接入用户鉴权和知识库归属权限；这些接口用于本地单用户学习阶段。

阅读顺序：CreateBaseReqDTO -> KnowledgeBaseController -> IKnowledgeBaseService /
KnowledgeBaseServiceImpl -> KnowledgeBaseMapper -> t_knowledge_base。
Controller 绑定 JSON 并启动参数校验，Service 校验并编排数据库调用，Mapper 通过 MyBatis-Plus 读写表。
修改请求正文同样为 {"name":"新名称"}，返回更新后的知识库信息。
保留用户已有 DELETE /api/knowledge-base/delete/{id} 路径，它与 DELETE /{id} 执行同一方法。
删除时在一个数据库事务中：按 kbId 物理清理所有片段（包含逻辑删除片段），
标记文档删除，再逻辑删除知识库。失败会回滚；不影响其他知识库。
不存在或已删除的知识库返回 A000001；重复删除也按不存在处理。
当前不清理磁盘原文件。文档上传与知识库删除通过同一知识库行锁协调；物理文件清理留到文件生命周期课程。
## 第 03 课：文件上传与文本解析

本课先打通：上传文件 -> 保存原文件 -> Tika 提取文字 -> 登记文档记录 -> 返回预览。
解析是文件到文本的转换，尚未切分或调用 Embedding，也不会调用聊天模型。

| 方法 | 地址 | 功能 |
| --- | --- | --- |
| POST | /api/knowledge-base/{kbId}/documents | 上传并解析一份文档 |
| GET | /api/knowledge-base/{kbId}/documents | 查看该知识库的未删除文档 |

先创建知识库，使用返回的真实 id 替换地址中的 {kbId}。
在 Postman/Apifox 中选择 POST、Body -> form-data，添加字段 file，类型选 File，选择文件后发送。
不要改成 JSON；客户端会生成 multipart/form-data 的 boundary，不要手动覆盖 Content-Type。

Windows curl.exe 示例（将 1 换成自己的知识库 ID，路径换成自己的文件）：

```powershell
curl.exe -F "file=@D:/学习资料/员工制度.md" http://localhost:8080/api/knowledge-base/1/documents
```

### 存储、解析与状态

- 支持 UTF-8 TXT、Markdown，以及带文字的 PDF；单文件最多 10MB。当前没有 OCR，不承诺保留复杂表格或版面。
- 原文件默认存于 D:/develop/huianswer-data/documents/{kbId}/{UUID}.扩展名；展示名保存在 doc_name 中。同名上传不覆盖。
- 可用 HUIANSWER_STORAGE_DIR 调整原文件目录；上传临时目录默认 D:/develop/huianswer-data/tmp，可用 HUIANSWER_UPLOAD_TEMP_DIR 调整。
- Tika 解析器返回完整文本；接口最多返回 2000 字符 textPreview，textLength 是完整解析文本长度，previewTruncated 表示仅预览被截断。
- 解析最多允许 100 万字符，超过则失败，不把截断内容当成完整文档。
- 正常结果 status=pending、parseStatus=parsed：已解析，等待后续切分/向量化。只有完整导入完成才使用 success。
- 内容无法解析时保留原文件和文档记录，status=failed、parseStatus=failed，返回安全的 errorMessage。
- Result.code="0" 表示本次上传请求得到处理结果；调用方仍应检查 data.status，不能把 failed 当成导入成功。
- 文档列表不返回正文或磁盘路径。JSON 元数据只保存格式、文件大小、解析阶段和文字数量；当前不单独保存完整解析文本，后续切分从原文件重新读取。

阅读顺序：KnowledgeDocumentController -> KnowledgeDocumentServiceImpl.uploadDocument ->
LocalDocumentStorage.save/open -> TikaDocumentParser.parse -> KnowledgeDocumentMapper。
@RequestPart("file") 绑定上传的文件；kbId 来自路径，用于关联文档和约束知识库范围。

### 当前取舍

首版同步解析。上传与删除都在事务中对同一知识库行 SELECT FOR UPDATE，防止删除漏掉并发新增文档。
锁覆盖本次文件存储和解析，同库导入串行，适合本地小文件学习；以后异步导入时需要缩短事务并协调任务状态。
数据库事务不会回滚磁盘写入：数据库登记失败或存储中途失败可能留下文件，当前保留，不自动清理。
删除知识库会清理数据库关联资料，暂不删除原文件，也未实现单文档删除、解析重试和用户权限。

本课测试：真实 PostgreSQL + Spring MVC + Tika 验证归属、中文预览、列表隔离、失败记录及上传参数；
实际生成的 PDF 验证文本提取、空白 PDF 拒绝与字符上限。另用独立临时应用完成真实 HTTP multipart/磁盘存储验收。
集成测试使用内存文件存储及数据库回滚；HTTP 验收只清理本次数据库记录，原文件保留在 D:/develop/huianswer-data/verification。

Tika 参考：https://tika.apache.org/3.2.3/gettingstarted.html 、https://tika.apache.org/3.2.3/examples.html

## 本地数据库

现有 Docker 容器：postgres，镜像 pgvector/pgvector:pg16，宿主机端口 5432。
项目数据库：huianswer。建表脚本：sql/01_knowledge.sql。

```powershell
.\tools\init-db.ps1
```

脚本创建独立 huianswer 数据库，并在该库启用 vector 扩展、创建三张表；不修改 frag 和 ragent。
重复执行可用于初始化空库；CREATE TABLE IF NOT EXISTS 不会升级已有表，后续字段变更要编写迁移 SQL。

## 密钥和数据库密码

真实值保存在 Windows 当前用户环境变量，不在代码、YAML 或 .env.example 中：

- HUIANSWER_DB_PASSWORD：PostgreSQL 密码。
- HUIANSWER_AI_API_KEY：硅基流动 API key。

application.yml 通过 ${变量名} 读取。可选的连接地址、用户名、模型等变量名见 .env.example。
.env.example 只是变量名示例，Spring Boot 不会自动把它当配置加载。
用户环境变量不是加密保险库，本机同一用户的程序可以读取；不要打印完整环境变量或分享带值的截图。

已打开的 IDEA 或终端不会自动继承刚设置的用户变量。重启 IDEA/终端，或者使用启动脚本主动读取：

```powershell
.\tools\start-local.ps1
```

脚本默认 JDK 17 位于 D:\develop\java\jdk_17，先构建，再启动 rag 的可执行 jar。
当前启动不需要调用模型；接入模型的课程中再验证 API key 与接口。

## 4096 维的取舍

- pgvector 的 vector 类型可存储 4096 维，当前表固定为 vector(4096)。
- 存储上限和近似索引上限不同：普通 vector 的 HNSW/IVFFlat 索引最多 2000 维。
- halfvec 近似索引最多 4000 维，也不能直接索引当前 4096 维。
- 当前使用知识库范围过滤后，按 embedding <=> 问题向量进行精确余弦距离排序。
- 未创建向量近似索引；以后按实际数据量讨论降维或量化方案。
- 改嵌入模型或输出维度时，要同时考虑表结构与已有文档的重新向量化，不能只改配置。

官方说明：https://github.com/pgvector/pgvector

## 建议阅读顺序

1. sql/01_knowledge.sql：字段、归属外键、状态和普通索引。
2. knowledge/dao/entity：与你刚设计的三种数据对象对应。
3. knowledge/dao/mapper：BaseMapper 的泛型是实体；deletePhysicallyByDocId 明确物理清理。
4. knowledge/dao/handler：把 Java float[] / JSON 文本转换成 PostgreSQL 的 vector / jsonb。
5. config/DatabaseMetaObjectHandler：填充创建时间、更新时间和删除标识。

实体类位于 rag/src/main/java/com/lhsg/HuiAnswer/rag 下。

文档采用逻辑删除。清理文档时，业务服务应在同一事务中标记文档删除并调用
KnowledgeChunkMapper.deletePhysicallyByDocId；后续课程会实现该服务及文件清理。
自定义检索 SQL 仍需显式排除已删除知识库/文档/片段，并限定成功处理的文档。

## 验证

真实 PostgreSQL 集成测试：KnowledgeDatabaseIntegrationTest、KnowledgeBaseApiIntegrationTest、KnowledgeBaseMutationIntegrationTest、KnowledgeDocumentApiIntegrationTest；文本解析用例为 TikaDocumentParserTest。
验证向量与 JSON 映射、知识库范围、按文档物理清理、维度约束和归属约束；以及知识库 CRUD、参数校验、逻辑删除过滤和响应 DTO。关联删除覆盖误删隔离、失败回滚及审计时间。
普通用例使用测试事务回滚；专用故障用例关闭外层测试事务，验证 Service 自身回滚，然后按本用例 ID 清理专用记录。测试不保留业务记录、不调用模型或删除磁盘文件。

```powershell
$env:JAVA_HOME = 'D:\develop\java\jdk_17'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:HUIANSWER_DB_PASSWORD = [Environment]::GetEnvironmentVariable('HUIANSWER_DB_PASSWORD', 'User')
mvn -B -ntp "-Dmaven.repo.local=D:/develop/repo" -pl rag -am verify
```

未提供 HUIANSWER_DB_PASSWORD 时，这组真实数据库测试会跳过。执行前需要 Docker 中数据库和表已初始化。
