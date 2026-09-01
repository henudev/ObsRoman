# Trace Log Service API 文档

| | |
| --- | --- |
| **接口版本** | v1.2.0 |
| **发布日期** | 2026-09-01 |
| **Base URL** | `http://localhost:8080`（Compose 前端经 nginx 同源代理 `/api`） |
| **更新日志** | 见 [CHANGELOG.md](CHANGELOG.md)，页面右上角「更新日志」同步展示 |
| **业务接入** | 推荐 SDK：Java / Python（见 [SDK.md](SDK.md)，页面「SDK 文档」同步展示） |

> 所有业务 API 均返回统一包装结构：`{"code": 0, "message": "ok", "data": {...}}`；`code=0` 成功，非 0 见全局异常码表。

## 目录

- [鉴权](#鉴权)
- [全局异常码](#全局异常码)
- [日志写入](#1-单条日志写入) · [批量写入](#2-批量写入)
- [日志搜索](#3-日志搜索) · [Trace 链路查询](#4-trace-链路查询)
- [Dashboard](#5-dashboard)（Overview / 日志趋势 / 等级分布 / 服务 Top / 最近 ERROR）
- [日志导出](#6-日志导出)
- [系统接口](#7-系统接口)（/health · /ready）

---

## 鉴权

**功能描述**：所有业务 API 需要 Bearer Token；`/health`、`/ready` 公开。

| 权限 | 允许的操作 |
| --- | --- |
| `log:write` | `POST /api/v1/logs`、`POST /api/v1/logs/batch` |
| `log:read` | `POST /api/v1/logs/search` |
| `trace:read` | `GET /api/v1/traces/{traceId}` |
| `dashboard:read` | `POST /api/v1/dashboard/**` |
| `log:export` | `POST /api/v1/logs/export` |

写入 Key 可绑定 `service` / `environment`：绑定的 Key 提交的日志必须与之匹配，否则该条被拒绝（单条 → 403；批量 → 计入 rejected）。

**请求参数**（Header）

| 名称 | 必填 | 说明 |
| --- | --- | --- |
| Authorization | 是 | `Bearer <api-key>` |

**异常码**：`1101`（401，缺失/无效 Key）、`1102`（403，权限不足或绑定不匹配）。

```bash
# curl 示例：无 Key 访问（期望 401）
curl -i -X POST http://localhost:8080/api/v1/logs \
  -H 'Content-Type: application/json' \
  -d '{"trace_id":"4bf92f3577b34da6a3ce929d0e0e4736","service":"s","level":"INFO","message":"m"}'
```

---

## 全局异常码

| code | HTTP | 含义 |
| --- | --- | --- |
| 0 | 200/202 | 成功 |
| 1001 | 400 | 请求参数错误（含时间范围起止颠倒、非法枚举等） |
| 1002 | 400 | 日志记录校验失败（缺失必填字段 / 一级字段白名单外 / 格式非法） |
| 1003 | 400 | 单条日志超过 64 KB |
| 1004 | 400 | 批量超过 500 条 |
| 1005 | 413 | 请求体超过 5 MB |
| 1006 | 202 | 队列满丢弃（内部计数 log_drop_total） |
| 1101 | 401 | 缺失或无效 API Key |
| 1102 | 403 | 权限不足 / 写入 Key 绑定不匹配 |
| 1201 | 404 | 资源不存在（如 Trace 未找到） |
| 1301 | 400 | 搜索时间范围超过 7 天 |
| 1302 | 400 | Dashboard 时间范围超过 24 小时 |
| 1303 | 400 | 导出超过 24 小时或 100,000 条 |
| 1304 | 400 | 导出格式不支持（仅 csv / jsonl） |
| 1401 | 502 | 存储错误（OpenObserve 异常） |
| 1402 | 504 | 存储查询超时 |
| 1500 | 500 | 内部错误 |

---

## 1. 单条日志写入

```
POST /api/v1/logs
```

**功能描述**：接收单条日志，完成统一模型校验与规范化后提交内存异步队列，立即返回 202。`202` 仅表示日志已进入本服务，不保证已持久化（持久化失败按 DROP 计数，不影响调用方）。

**请求参数**（Body，`application/json`）

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| trace_id | string | 是 | 32 位十六进制（大小写不敏感，服务端转小写） |
| service | string | 是 | 小写字母数字以 `-` 连接（如 `order-service`），≤128 字符 |
| level | string | 是 | TRACE / DEBUG / INFO / WARN / ERROR / FATAL |
| message | string | 是 | 非空，≤32768 字符 |
| timestamp | string\|number | 否 | ISO-8601（推荐带时区，如 `2026-09-01T14:20:30.123+08:00`）；也接受 epoch 毫秒/微秒数字；缺省用服务端接收时间 |
| environment | string | 否 | local / dev / test / staging / prod，默认 `local` |
| type | string | 否 | 默认 `application`，≤64 字符 |
| span_id / parent_span_id | string | 否 | 16 位十六进制（W3C Trace Context 兼容，P0 不做 Span 管理） |
| request_id / user_id | string | 否 | ≤128 字符 |
| service_version / host / instance | string | 否 | ≤64/255/255 字符 |
| method / path | string | 否 | ≤16 / ≤2048 字符 |
| status_code | int | 否 | [0, 999] |
| duration_ms | int | 否 | 非负整数 |
| event | string | 否 | ≤256 字符 |
| attributes | object | 否 | 业务扩展字段，≤50 项；嵌套值序列化为字符串存储；**一级字段白名单之外的数据必须放这里** |

**返回参数**（202 Accepted）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| code | int | 0 |
| message | string | `accepted` |
| data | null | — |

**异常码**：`1001`（400 Body 非法）、`1002`（400 字段校验失败，message 指明原因）、`1003`（400 超过 64KB）、`1101/1102`（鉴权）、`1006`（202 队列满丢弃）。

```bash
# curl 示例：写入单条日志
curl -s -X POST http://localhost:8080/api/v1/logs \
  -H "Authorization: Bearer dev-writer-key" \
  -H 'Content-Type: application/json' \
  -d '{
    "timestamp": "2026-09-01T14:20:30.123+08:00",
    "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
    "span_id": "00f067aa0ba902b7",
    "request_id": "req_001",
    "service": "order-service",
    "service_version": "1.0.0",
    "environment": "prod",
    "level": "INFO",
    "type": "application",
    "event": "order.create",
    "message": "创建订单成功",
    "user_id": "10001",
    "method": "POST",
    "path": "/api/orders",
    "status_code": 200,
    "duration_ms": 123,
    "host": "10.0.0.1",
    "instance": "order-service-01",
    "attributes": {"order_id": "ORD001"}
  }'
# 期望：{"code":0,"message":"accepted","data":null}
```

---

## 2. 批量写入

```
POST /api/v1/logs/batch
```

**功能描述**：批量接收日志，**支持部分成功**——单条校验失败只计入 `rejected` 并附 `errors` 明细，不影响其他条目。

**请求参数**（Body）

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| logs | array | 是 | 日志对象数组，单条结构与"单条写入"一致 |
| — | — | — | 限制：单批 ≤ 500 条（超限整批 400/1004）；请求体 ≤ 5 MB（413/1005） |

**返回参数**（202 Accepted，`data`）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| accepted | int | 通过校验并入队的条数 |
| rejected | int | 校验失败条数（>0 时附 errors） |
| dropped | int | 队列满被丢弃的条数（>0 时出现） |
| errors | array\|null | `[{index, code, message}]`，index 为 logs 数组下标 |

**异常码**：`1001`（400 缺少 logs 数组）、`1004`（400 超 500 条）、`1005`（413 超 5MB）、`1102`（写入 Key 绑定不匹配计入 rejected）。

```bash
# curl 示例：3 条日志，其中 1 条 trace_id 非法（部分成功）
curl -s -X POST http://localhost:8080/api/v1/logs/batch \
  -H "Authorization: Bearer dev-writer-key" -H 'Content-Type: application/json' \
  -d '{"logs":[
    {"trace_id":"4bf92f3577b34da6a3ce929d0e0e4736","service":"gateway","environment":"prod","level":"INFO","message":"收到请求"},
    {"trace_id":"bad-id","service":"gateway","level":"INFO","message":"非法 trace_id"},
    {"trace_id":"e05af3d1c9b34a0f8e37b1f05f2f4a10","service":"payment-service","environment":"prod","level":"INFO","message":"支付成功"}
  ]}'
# 期望：{"code":0,"message":"ok","data":{"accepted":2,"rejected":1,
#        "errors":[{"index":1,"code":1002,"message":"trace_id must be a 32-char hex string"}]}}
```

---

## 3. 日志搜索

```
POST /api/v1/logs/search
```

**功能描述**：多条件组合查询。时间范围默认最近 15 分钟（调用方不传时），最大 7 天；结果按 timestamp 倒序分页返回。查询条件由服务端构建为受控 SQL，**禁止前端提交 SQL**。

**请求参数**（Body，全部可选）

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| start_time / end_time | string | 最近 15 分钟 | ISO-8601；范围 > 7 天 → 1301 |
| service | string[] | 不限 | 服务名精确匹配 |
| environment | string[] | 不限 | 受控枚举 |
| level | string[] | 不限 | 受控枚举 |
| type | string[] | 不限 | 精确匹配 |
| trace_id / request_id / user_id | string | 不限 | 精确匹配 |
| keyword | string | 不限 | 大小写不敏感，匹配 message / event / attributes |
| page | int | 1 | ≥1 |
| size | int | 50 | 1~500 |

**返回参数**（200 OK，`data`）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| total | long | 命中总数（分页依据） |
| page / size | int | 回显分页参数 |
| logs | array | LogRecord 数组（结构与写入模型一致，attributes 还原为对象） |

**异常码**：`1001`（参数非法 / 非法枚举 / 时间格式错误）、`1301`（范围超 7 天）、`1401/1402`（存储异常/超时）。

```bash
# curl 示例：近 1 小时 prod 环境 ERROR 关键词 timeout
curl -s -X POST http://localhost:8080/api/v1/logs/search \
  -H "Authorization: Bearer dev-reader-key" -H 'Content-Type: application/json' \
  -d '{
    "start_time": "2026-09-01T15:00:00+08:00",
    "end_time":   "2026-09-01T16:00:00+08:00",
    "environment": ["prod"],
    "level": ["ERROR"],
    "keyword": "timeout",
    "page": 1,
    "size": 50
  }'
# 期望：{"code":0,"data":{"total":N,"page":1,"size":50,"logs":[...]}}
```

---

## 4. Trace 链路查询

```
GET /api/v1/traces/{traceId}
```

**功能描述**：按 trace_id 聚合完整链路：起止时间、总耗时、状态与涉及服务；日志按 timestamp **升序**。查询窗口为最近 7 天，超过 `trace-max-logs`（默认 5000）条时 `truncated=true`。状态判定：存在 ERROR/FATAL → `ERROR`；有日志无错误 → `SUCCESS`；无日志 → `404`。

**请求参数**（Path）

| 名称 | 必填 | 说明 |
| --- | --- | --- |
| traceId | 是 | 32 位十六进制（非法 → 1001） |

**返回参数**（200 OK，`data`）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| trace_id | string | 规范化 trace_id |
| start_time / end_time | string | 首末日志时间（ISO-8601） |
| duration_ms | long | 末端时间 - 起始时间 |
| status | string | SUCCESS / ERROR |
| services | string[] | 链路涉及的服务（按首次出现顺序） |
| logs | array | LogRecord 数组，timestamp 升序 |
| truncated | bool\|null | 日志被截断时为 true |

**异常码**：`1001`（traceId 非法）、`1201`（404 链路不存在）、`1401/1402`。

```bash
# curl 示例
curl -s http://localhost:8080/api/v1/traces/4bf92f3577b34da6a3ce929d0e0e4736 \
  -H "Authorization: Bearer dev-reader-key"
# 期望：{"code":0,"data":{"trace_id":"...","status":"ERROR","duration_ms":2000,
#        "services":["gateway","order-service","payment-service"],"logs":[...]}}
```

---

## 5. Dashboard

五个接口均 `POST`、权限 `dashboard:read`，共用筛选条件，**不直接查询 OpenObserve**（经本服务聚合）。

**请求参数**（Body，全部可选）

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| start_time / end_time | string | 最近 1 小时 | ISO-8601；范围 > 24 小时 → 1302 |
| environment | string | 不限 | 受控枚举 |
| service | string | 不限（null/空 = 全部） | 精确匹配 |

**异常码**（四个接口共用）：`1001`（参数非法）、`1302`（范围超 24 小时）、`1401/1402`。

### 5.1 Overview 聚合

```
POST /api/v1/dashboard/overview
```

**返回参数**（`data`）：`total_logs`、`error_logs`、`warn_logs`、`error_rate`（0~1）、`trace_count`、`active_services`、`avg_duration_ms`、`p95_duration_ms`、`p99_duration_ms`（后三者可能为 null，表示窗口内无 duration 数据）。

```bash
# curl 示例
curl -s -X POST http://localhost:8080/api/v1/dashboard/overview \
  -H "Authorization: Bearer dev-reader-key" -H 'Content-Type: application/json' \
  -d '{"environment":"prod"}'
# 期望：{"code":0,"data":{"total_logs":603,"error_logs":58,"warn_logs":70,"error_rate":0.0962,
#        "trace_count":41,"active_services":6,"avg_duration_ms":1521.2,
#        "p95_duration_ms":2879.1,"p99_duration_ms":2969.3}}
```

### 5.2 日志趋势

```
POST /api/v1/dashboard/log-trend
```

**功能描述**：按时间桶聚合日志量，桶宽按范围自适应（约 60 个桶），缺失桶补零，按时间升序。

**返回参数**（`data`）：`[{ "time": "14:00", "count": 1200, "bucket": 1788242400000 }]`（bucket 为桶起点 epoch 毫秒）。

```bash
# curl 示例
curl -s -X POST http://localhost:8080/api/v1/dashboard/log-trend \
  -H "Authorization: Bearer dev-reader-key" -H 'Content-Type: application/json' \
  -d '{"environment":"prod"}'
```

### 5.3 日志等级分布

```
POST /api/v1/dashboard/level-distribution
```

**返回参数**（`data`）：`[{ "level": "INFO", "count": 100000 }, ...]`，按 count 降序。

```bash
# curl 示例
curl -s -X POST http://localhost:8080/api/v1/dashboard/level-distribution \
  -H "Authorization: Bearer dev-reader-key" -H 'Content-Type: application/json' -d '{}'
```

### 5.4 服务日志 Top

```
POST /api/v1/dashboard/service-ranking
```

**功能描述**：按服务聚合日志量、ERROR 数量与错误率，取 Top 20。

**返回参数**（`data`）：`[{ "service": "payment-service", "total": 10000, "errors": 500, "error_rate": 0.05 }, ...]`。

```bash
# curl 示例
curl -s -X POST http://localhost:8080/api/v1/dashboard/service-ranking \
  -H "Authorization: Bearer dev-reader-key" -H 'Content-Type: application/json' -d '{}'
```

### 5.5 最近 ERROR 日志

```
POST /api/v1/dashboard/recent-errors
```

**功能描述**：Dashboard 页面"最近 ERROR 日志"区块。固定 level ∈ {ERROR, FATAL}，timestamp 倒序，复用日志搜索通道。

**请求参数**（Body，全部可选）：`start_time` / `end_time` / `environment` / `service` / `size`（默认 10）。

**返回参数**：结构同日志搜索。

```bash
# curl 示例
curl -s -X POST http://localhost:8080/api/v1/dashboard/recent-errors \
  -H "Authorization: Bearer dev-reader-key" -H 'Content-Type: application/json' \
  -d '{"size": 5}'
```

---

## 6. 日志导出

```
POST /api/v1/logs/export
```

**功能描述**：将搜索结果流式导出为文件。查询条件与日志搜索**完全一致**（复用 `LogQuery`，仅多 `format`）；服务端"查一批 → 写一批"，内存占用与结果集无关；结果按 timestamp 升序。

**请求参数**（Body）

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| （搜索条件） | — | 否 | 与 `/api/v1/logs/search` 相同（无 page/size），时间范围默认最近 15 分钟 |
| format | string | 否 | `csv`（默认）/ `jsonl` |

**返回参数**（200，HTTP 流式响应）

| Header | 说明 |
| --- | --- |
| Content-Type | `text/csv;charset=UTF-8`（CSV，带 UTF-8 BOM、RFC 4180 转义、`attributes` 列为 JSON 字符串）或 `application/x-ndjson`（JSONL，每行一个完整 JSON） |
| Content-Disposition | `attachment; filename="logs-{yyyyMMddHHmmss}.csv|jsonl"` |
| X-Export-Rows | 导出行数（流式写出前已知；0 表示当前条件无命中） |

**异常码**：`1303`（范围超 24 小时或结果超 100,000 条，提示缩小条件）、`1304`（格式不支持）、`1401/1402`。

```bash
# curl 示例 1：导出近 24h 的 ERROR 日志为 CSV
curl -s -X POST http://localhost:8080/api/v1/logs/export \
  -H "Authorization: Bearer dev-reader-key" -H 'Content-Type: application/json' \
  -d '{
    "start_time": "2026-09-01T00:00:00+08:00",
    "end_time":   "2026-09-01T23:59:59+08:00",
    "environment": ["prod"],
    "level": ["ERROR"],
    "keyword": "timeout",
    "format": "csv"
  }' -o logs.csv

# curl 示例 2：导出某条 Trace 全部日志为 JSONL，并查看导出行数
curl -s -D - -X POST http://localhost:8080/api/v1/logs/export \
  -H "Authorization: Bearer dev-reader-key" -H 'Content-Type: application/json' \
  -d '{"trace_id":"4bf92f3577b34da6a3ce929d0e0e4736","format":"jsonl"}' \
  -o logs.jsonl | grep -i x-export-rows
```

> **提示**：导出为空（X-Export-Rows: 0）说明当前条件未命中任何日志——请扩大时间范围或减少过滤条件，前端会给出明确提示。

---

## 7. 系统接口

### 7.1 健康检查

```
GET /health
```

**功能描述**：进程存活检查（公开，无需鉴权）。

**返回参数**：`{ "status": "UP", "version": "1.2.0", "release_date": "2026-09-01" }`

```bash
# curl 示例
curl -s http://localhost:8080/health
```

### 7.2 就绪状态

```
GET /ready
```

**功能描述**：依赖就绪检查（公开）。OpenObserve 故障时返回 `DEGRADED` 且服务不崩溃、不重启，写入仍受理进队列并按策略丢弃，恢复后自动回到 `UP`。

**返回参数**（`data` 无包装，直接返回对象）

| 字段 | 说明 |
| --- | --- |
| status | `UP` / `DEGRADED`（openobserve=DOWN 时） |
| openobserve | `UP` / `DOWN` |
| queue | `UP` / `DOWN`（后台写线程存活） |
| log_drop_total | 累计丢弃总数（queue_full + write_failed） |
| queue_full_dropped / write_failed_dropped | 分项丢弃计数 |
| log_enqueued_total | 累计入队条数 |
| queue_size | 当前队列积压 |

```bash
# curl 示例
curl -s http://localhost:8080/ready
# 期望：{"status":"UP","openobserve":"UP","queue":"UP","log_drop_total":0,...}
```

---

## 版本信息

- **v1.2.0**（2026-09-01）：新增 Java / Python SDK 与 SDK 文档（接口无变更）。
- **v1.1.0**（2026-09-01）：导出增加 `X-Export-Rows` 响应头；`/health` 返回版本信息；文档按接口五段式重构。
- **v1.0.0**（2026-09-01）：P0 初始发布，完整接口清单见 [CHANGELOG.md](CHANGELOG.md)。
