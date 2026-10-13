# 文档管理接口

## 路径与参数

接口沿用管理员登录校验。下面的 `{kbId}`、`{docId}` 替换为实际 ID。

| 用途 | 方法 | 路径 |
| --- | --- | --- |
| 文档分页 | GET | `/api/knowledge-base/{kbId}/documents?current=1&size=10` |
| 文档详情 | GET | `/api/knowledge-base/{kbId}/documents/{docId}` |
| 修改启用状态 | PUT | `/api/knowledge-base/{kbId}/documents/{docId}/enabled` |
| 删除文档 | DELETE | `/api/knowledge-base/{kbId}/documents/{docId}` |
| 片段分页 | GET | `/api/knowledge-base/{kbId}/documents/{docId}/chunks?current=1&size=10` |

上传与失败重试接口保留：`POST /api/knowledge-base/{kbId}/documents`（multipart 的 `file` 字段），`POST /api/knowledge-base/{kbId}/documents/{docId}/retry`。

修改启用状态使用 JSON 请求体：

```json
{"enabled": 1}
```

`0` 启用，`1` 禁用，默认 `0`。`enabled` 不使用 `@TableLogic`，管理员仍能查询禁用文档及其片段。

## 分页返回

文档和片段列表使用 MP 分页。`current` 从 1 开始，默认 1；`size` 默认 10，范围 1～100。
原文档列表的 `data` 数组改为分页对象，前端从 `data.records` 读取列表：

```json
{
  "code": "0",
  "data": {
    "records": [],
    "total": 0,
    "current": 1,
    "size": 10,
    "pages": 0
  }
}
```

示例只列出主要字段。片段响应包含 ID、归属、序号、正文和审计时间，不返回向量数组及分词中间结果。

## 启停与删除

- `status` 表示解析进度，`enabled` 控制检索。只有未删除、`status=success`、`enabled=0` 的文档会被向量和关键词两路检索召回。
- 禁用保留已有片段；重新启用后无需再次调用向量模型。正在处理的禁用文档仍能完成导入，但不能参与检索。
- 删除在同一事务中逻辑删除文档并物理删除该文档的片段，保留磁盘原文件。
- 排队中的已删除任务直接结束；处理期间删除文档时，最终写库前再次检查，不会把片段写回来。此检查不会主动中断已经发出的模型请求。

## 数据库更新

已有数据库执行 `sql/09_document_enabled.sql`，为现有文档补上 `enabled=0` 并增加 0/1 约束。本次开发已在本地 `huianswer` 数据库执行。
新数据库的 `sql/01_knowledge.sql` 已同步该字段。