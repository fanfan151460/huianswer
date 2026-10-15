星澜商城 RAG 评测资料 v1
资料版本：1.0；准备日期：2026-10-15
这是自建合成语料，不对应真实商城，不作为真实商家承诺或法律解释。
本阶段只准备资料、上传入库和确认状态，还没有跑模型评测，没有准确率结论。

一、这一包有什么
1. corpus/：4个业务知识库、每库4篇，共16篇UTF-8正文。只有这个目录的txt需要上传。
   products：商品与使用，P01至P04。
   orders：订单与支付，O01至O04。
   shipping：配送与签收，D01至D04。
   aftersales：退换与售后，S01至S04。
2. requests/：创建4个知识库的请求JSON。只包含name、description，复制到Postman请求体，不作为文档上传。
3. cases/dev.jsonl：20道开发题，可用于观察问题、调整参数。
   cases/holdout.jsonl：20道保留题，方案确定后再跑，避免反复看题调提示词。
   cases/all.jsonl：完整40题，是前两者的合集，不是另外40题。
   cases/dev_题目与参考.txt、holdout_题目与参考.txt：方便人工阅读的题目与答案要点。
4. knowledge-bases.json：知识库逻辑名称及正文清单。
5. manifest.json：文件版本、数量和SHA-256，方便以后确认测的是否同一份资料。
题目、参考答案、请求JSON、README和manifest都不要上传到知识库，否则等于把试卷答案塞进教材。

二、先只做第一个知识库
使用你现有的Postman环境、管理员username和token。
本地默认地址为 http://localhost:8080；若已通过HUIANSWER_PORT修改端口，沿用你的实际地址。

第1步：创建知识库
POST /api/knowledge-base
Body选择raw、JSON，复制 requests/create-products.json 的内容。
name：星澜商城－商品与使用
description：使用该JSON内的完整描述，意图路由会参考它。
记录响应中返回的知识库id，下文用{kbId}代表，不能把products当成数据库id。

第2步：逐篇上传商品正文
POST /api/knowledge-base/{kbId}/documents
Body选择form-data，新增字段file，类型选File，每次选一篇：
corpus/products/P01_产品目录与选型.txt
corpus/products/P02_配件兼容与供电.txt
corpus/products/P03_操作保养与滤芯复位.txt
corpus/products/P04_错误码与故障排查.txt
请求仍带username和token，让Postman自动生成multipart的Content-Type和boundary，不要手填application/json。
按本次提供的文件名上传，后续题目可以凭文件名和章节编号定位依据。

第3步：等MQ处理完成
上传成功只表示已经接收，不等于分块向量化已完成。
GET /api/knowledge-base/{kbId}/documents?current=1&size=10
或 GET /api/knowledge-base/{kbId}/documents/{docId}
等4篇文档均为status=success，知识库和文档保持enabled=0（启用），再确认片段非空：
GET /api/knowledge-base/{kbId}/documents/{docId}/chunks
pending时先等待，不要反复上传同一篇制造重复片段。
若状态为failed，先查看错误与日志；排除问题后可用现有重试接口：
POST /api/knowledge-base/{kbId}/documents/{docId}/retry
不要为了重试创建一堆相同文件。
这4篇成功后，先反馈状态，我们再继续其余库。

三、其余三个知识库
流程相同，各用自己的创建请求，并把对应目录的4篇放进正确的库：
requests/create-orders.json -> corpus/orders/
requests/create-shipping.json -> corpus/shipping/
requests/create-aftersales.json -> corpus/aftersales/
最终应当是4个库、每库4篇成功文档，而不是16个库，也不是把16篇放进同一个库。
保留实际kbId及docId。后续运行器再记录逻辑名称与实际id的对应关系，不需要现在往业务表加评测字段。
旧会议室等资料先保留；正式全链路评测前再固定参与路由和检索的目录范围，记录是否还有其它启用文档，避免对比两次用的不是同一份语料。

四、题目怎样用
40题分布：单条规则16、口语改写6、跨库组合6、多轮指代4、澄清3、无依据3、闲聊2。
多轮题的history是固定测试输入，question是最后一次提问；不是让助手把历史再当新问题回答。
手工体验可以新建会话先说前文用户消息，再追问；实际助手回复若不同，需要保存实际历史，不能当作严格相同输入的离线分数。

JSONL关键字段：
id：稳定题号；group_id：近义题分组，同组只放在同一个集合。
question：用户原始提问；history：固定的前文；retrieval_query：仅测检索时使用的独立完整问题。
acceptable_intents：允许的意图类别，不要求不同合理路径都判错。
expected_kb_keys：预期涉及的业务库，用逻辑名称表示，不是请求必须携带的kbId。
required_evidence：规则来源的source_id、section_id和原文，独立于数据库自动生成的chunkId。
expected_answer_points、reference_answer：答案要点和参考表述，不要求逐字复述。
forbidden_claims：不能凭空承诺或混淆的内容。
retrieval_scorable：是否进入资料命中统计；澄清、无依据和闲聊不按“没有召回”计错。
有多个答案要点时逐项核对，不能只找到一个子问题的依据就算整题通过；来源多处重复支持同一事实时，判定以事实证据覆盖为准，不强制召回每一个重复段落。

五、后面怎样开始比较
先在同一语料、同一开发题、相同最终数量下比较：仅向量 -> 混合召回 -> 混合召回加重排序。
第一轮先固定最终最多6段作为共同预算，不同时乱改阈值、召回数和提示词。
仅测检索时，用retrieval_query并在后端评测入口指定预期业务范围，以免把改写或路由失败都算成检索失败。
后续再用question与history跑完整改写、拆分、路由、检索、生成链路；正常用户仍不需要传kbId。
这些是下一步的运行约定，目前未新增评测入口或脚本，也没有运行Ragas。
需要收集实际返回片段、最终回答和耗时，再接入Ragas等离线评分。
多轮题不能继承上一道题的会话状态，保留题也不能反复用于选择阈值或改写提示词。
v1样本较小且由我们自建，只适合建立基线和找明显错误，不能当成真实线上准确率证明。
