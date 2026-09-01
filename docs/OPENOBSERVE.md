# OpenObserve 配置说明

Trace Log Service 把 OpenObserve **仅作为日志存储后端**（`LogStorage` 接口的一个实现）。业务系统与前端永远不直接访问 OpenObserve。

## 版本

- 适配 OpenObserve **v0.92.x**（`openobserve/openobserve:latest`，2026-08 构建）。
- 适配要点：
  - 搜索端点为 `POST /api/{org}/_search`（流名写在 SQL 的 `FROM "stream"` 中）；
  - ingest 端点为 `POST /api/{org}/{stream}/_json`；
  - `approx_percentile_cont(col, 0.95)` 使用 0~1 小数；
  - 关键词搜索使用 `str_match()`（大小写不敏感），因为 `match_all()` 仅覆盖默认全文索引字段；
  - `histogram()` 返回 UTC 裸时间字符串，服务端按 UTC 解析；
  - 流 Schema 校验列名，适配层缓存流字段（60s TTL）并对 "unknown field" 自动刷新重试。

## 认证

后端支持两种方式（二选一，配置项 `trace-log.storage.openobserve.*`）：

| 方式 | 配置 |
| --- | --- |
| Basic（推荐本地/Compose） | `username` + `password`（OpenObserve 根用户） |
| Bearer Token | `token`（优先级高于 Basic） |

环境变量：`OPENOBSERVE_URL` / `OPENOBSERVE_ORG` / `OPENOBSERVE_STREAM` / `OPENOBSERVE_USERNAME` / `OPENOBSERVE_PASSWORD`。

## 数据模型

- 组织：默认 `default`；流：默认 `trace_logs`（可配 `OPENOBSERVE_STREAM`，首次写入自动建流）。
- 每条日志写入时附带 `_timestamp`（epoch 微秒）作为 OpenObserve 时间列，`timestamp` 字段同时保留 ISO-8601 可读文本。
- `attributes` 以 JSON **字符串**存储（避免嵌套字段被存储端打平导致 Schema 漂移），查询/导出时还原为对象。
- `duration_ms`、`status_code` 等数值列由首条日志推断类型，保持数值类型写入以支持聚合。

## 超时与重试

| 项 | 默认 |
| --- | --- |
| connect timeout | 500ms |
| 写入读超时 | 5s |
| 查询读超时 | 10s（Dashboard/Export 同样生效） |
| 健康探活超时 | 2s |
| 写入重试 | 最多 3 次，退避 100/500/2000ms，仅针对网络异常与 408/429/5xx；400/401/403/413 不重试 |

## 降级行为

- OpenObserve 停机：接收路径不受影响（日志进内存队列，写失败按 DROP 计数），`/ready` 返回 `DEGRADED`，恢复后自动续写。**不要**因 OpenObserve 故障重启本服务。
- 查询类接口（search/trace/dashboard/export）返回 `502/1401` 或 `504/1402`。

## Compose 部署

`docker-compose.yml` 中的 OpenObserve 服务：

```yaml
openobserve:
  image: openobserve/openobserve:latest
  environment:
    ZO_ROOT_USER_EMAIL: ${OO_ROOT_USER_EMAIL:-root@example.com}
    ZO_ROOT_USER_PASSWORD: ${OO_ROOT_USER_PASSWORD:-Complexpass#123}
    ZO_DATA_DIR: /data
  volumes:
    - oo-data:/data
  healthcheck:
    test: ["CMD-SHELL", "wget -qO- http://localhost:5080/healthz || exit 1"]
```

数据落在命名卷 `oo-data`。生产部署请务必：

1. 修改 `ZO_ROOT_USER_EMAIL` / `ZO_ROOT_USER_PASSWORD`（`.env` 覆盖，勿提交仓库）；
2. 为后端配置独立的 API Key 集合（`TRACE_LOG_SECURITY_API_KEYS_N_*` 环境变量）；
3. 如需暴露 OpenObserve UI，仅限内网运维访问。

## 清空日志数据

**推荐：`./scripts/clear-data.sh`**（整卷清理，约 40 秒，得到干净的空系统）。

注意：OpenObserve 的 `DELETE /api/{org}/streams/{stream}` 是**异步**操作——"being deleted"
状态可能持续数分钟，期间该流名的写入会被拒绝（服务端已做最长 30 秒的等待重试）、查询返回空结果。
因此不要用删流 API 做常规清数据，也不要在删流后立即大量写入。

## 常用运维命令

```bash
# 查看流 Schema
curl -s -u root@example.com:'Complexpass#123' \
  http://localhost:5080/api/default/streams/trace_logs/schema

# 直接查 OpenObserve（仅运维排查用）
curl -s -u root@example.com:'Complexpass#123' \
  -X POST http://localhost:5080/api/default/_search \
  -H 'Content-Type: application/json' \
  -d '{"query":{"start_time":1788242400000000,"end_time":1788246000000000,
       "sql":"SELECT count(*) AS c FROM \"trace_logs\"","from":0,"size":1}}'
```
