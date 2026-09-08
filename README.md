# ObsRoman · Trace Log Service

基于 **OpenObserve** 的通用链路日志服务（当前版本 **v1.3.1**，更新记录见 [docs/CHANGELOG.md](docs/CHANGELOG.md)，前端右上角「更新日志」同步展示）。业务系统与前端一律通过本服务读写日志，**禁止直接调用 OpenObserve API**。

```
业务系统 / 前端
   │  HTTP（Bearer API Key）
   ▼
Trace Log Service（Spring Boot 3 / Java 17）
   ├── 参数校验 + 统一日志模型规范化
   ├── Memory Queue（满则 DROP + 计数，绝不阻塞业务）
   ├── LogWorker 批量异步写入 / 查询聚合
   ▼
OpenObserve（仅作为 Storage 实现，SQL 全部集中在 OpenObserveLogStorage）
   ▼
Dashboard（Vue 3 + ECharts）/ 日志导出（流式 CSV / JSONL）
```

## 核心能力（P0）

| 能力 | 说明 |
| --- | --- |
| 统一日志模型 | 一级字段白名单，扩展数据进 `attributes`；trace_id 32 位 hex、service 小写中划线、level/environment 受控枚举 |
| 单条 / 批量写入 | `POST /api/v1/logs`、`POST /api/v1/logs/batch`（≤500 条、≤5MB，支持部分成功） |
| 异步写入 | 内存队列 + 后台 Worker，达到 200 条或 1 秒即批量写入；队列满 DROP 并计数 |
| trace_id 链路关联 | 兼容 W3C Trace Context（`traceparent`）；`GET /api/v1/traces/{traceId}` 返回按时间升序的完整链路 |
| 日志搜索 | 时间范围 / service / environment / level / type / trace_id / request_id / user_id / 关键词，时间范围不设上限 |
| Dashboard | Overview 单页：总量 / ERROR / 错误率 / Trace 数 / 活跃服务 / 耗时 P95·P99 + 日志趋势、等级分布、服务 Top、最近 ERROR |
| 日志导出 | 复用搜索条件的流式导出 CSV / JSONL（时间范围 ≤24 小时，数量不设上限） |
| AK/SK 鉴权 | `Authorization: Bearer <ak>:<sk>`（兼容旧单 key）；**统一管理员权限**（所有 Key 可读可写，不再细分）；系统内置默认管理员 Key（`admin:admin`，可用环境变量覆盖）；管理页 `/keys` 可创建/启停/删除 Key，日志自动打 `api_key_ak` 以便按接入应用区分 |
| 可观测自身 | `GET /health`（含版本号）、`GET /ready`（OpenObserve 故障 → `DEGRADED`，服务不崩溃） |
| 时区约定 | 全站统一北京时区（Asia/Shanghai）：前端选择器与展示、服务端容器 TZ 均为 +08:00 |
| 页内文档 | 前端「API 文档」页面直接渲染 `docs/API.md`；导出接口返回 `X-Export-Rows` 便于前端提示 |

## 快速开始（Docker Compose）

```bash
# 可选：覆盖默认开发凭据（见 .env.example）
cp .env.example .env

docker compose up -d --build
```

启动后：

| 服务 | 地址 |
| --- | --- |
| Dashboard 前端 | http://localhost:3000 |
| Trace Log Service API | http://localhost:8080 |
| OpenObserve UI | http://localhost:5080 |

默认内置管理员 Key（统一管理员权限，生产环境必须通过环境变量覆盖 Secret）：`admin` / `admin`（即 `Authorization: Bearer admin:admin`）。前端顶栏无需再填 Key，统一使用该内置默认 Key。

> 仓库内不保存任何真实密码/Token：OpenObserve 凭据与 API Key 全部经环境变量注入（见 `backend/application.yml.example` 与 `docker-compose.yml`）。

### 一分钟验证闭环

```bash
KEY=admin:admin

# 1. 写入单条日志
curl -s -X POST http://localhost:8080/api/v1/logs \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{
    "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
    "service": "order-service",
    "environment": "prod",
    "level": "INFO",
    "event": "order.create",
    "message": "创建订单成功",
    "duration_ms": 123,
    "attributes": {"order_id": "ORD001"}
  }'

# 2. 批量写入（部分成功：单条失败不影响整批）
curl -s -X POST http://localhost:8080/api/v1/logs/batch \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"logs":[{"trace_id":"4bf92f3577b34da6a3ce929d0e0e4736","service":"gateway","environment":"prod","level":"INFO","message":"收到请求"},{"trace_id":"bad-id","service":"gateway","level":"INFO","message":"bad"}]}'

# 3. 关键词搜索（等 1~2 秒让异步写入落库）
sleep 3
curl -s -X POST http://localhost:8080/api/v1/logs/search \
  -H "Authorization: Bearer admin:admin" -H 'Content-Type: application/json' \
  -d '{"keyword":"创建订单","size":10}'

# 4. Trace 完整链路（timestamp 升序）
curl -s http://localhost:8080/api/v1/traces/4bf92f3577b34da6a3ce929d0e0e4736 \
  -H "Authorization: Bearer admin:admin"

# 5. Dashboard 聚合
curl -s -X POST http://localhost:8080/api/v1/dashboard/overview \
  -H "Authorization: Bearer admin:admin" -H 'Content-Type: application/json' -d '{}'

# 6. 流式导出 CSV / JSONL
curl -s -X POST http://localhost:8080/api/v1/logs/export \
  -H "Authorization: Bearer admin:admin" -H 'Content-Type: application/json' \
  -d '{"level":["ERROR"],"format":"csv"}' -o logs.csv

curl -s -X POST http://localhost:8080/api/v1/logs/export \
  -H "Authorization: Bearer admin:admin" -H 'Content-Type: application/json' \
  -d '{"level":["ERROR"],"format":"jsonl"}' -o logs.jsonl

# 7. 系统状态（OpenObserve 故障时 status=DEGRADED）
curl -s http://localhost:8080/ready
```

浏览器打开 http://localhost:3000 即可查看 Dashboard（Compose 构建时已注入默认 reader Key，也可在侧栏更换 API Key）。

## 本地开发（不使用 Docker）

```bash
# 1. 只启动 OpenObserve
docker run -d --name openobserve -p 5080:5080 \
  -e ZO_ROOT_USER_EMAIL=root@example.com \
  -e ZO_ROOT_USER_PASSWORD='Complexpass#123' \
  -e ZO_DATA_DIR=/data openobserve/openobserve:latest

# 2. 启动后端（JDK 17）
cd backend
OPENOBSERVE_URL=http://localhost:5080 \
OPENOBSERVE_USERNAME=root@example.com \
OPENOBSERVE_PASSWORD='Complexpass#123' \
TRACE_LOG_ADMIN_KEY=admin TRACE_LOG_ADMIN_SECRET=admin \
./mvnw spring-boot:run

# 3. 启动前端（Vite，代理 /api → localhost:8080）
cd frontend
npm install && npm run dev   # http://localhost:5173
```

## 部署文件说明

| 文件 | 作用 |
| --- | --- |
| `backend/Dockerfile` | 后端多阶段构建：Maven（Wrapper）编译 → `eclipse-temurin:17-jre` 运行，内置 `TZ=Asia/Shanghai` |
| `frontend/Dockerfile` | 前端两阶段构建：Node 20 `npm run build` → nginx 托管并反代 `/api`；构建时把 `docs/*.md` 打进页面 |
| `docker-compose.yml` | 一键编排 OpenObserve + backend + frontend，含健康检查与开发默认凭据（生产用 `.env` 覆盖） |
| `.dockerignore` / `backend/.dockerignore` | 控制构建上下文体积（排除 node_modules、target 等） |
| `.env.example` | 环境变量样例（OpenObserve 凭据与 API Key，生产必须覆盖） |

常用命令：

```bash
docker compose up -d --build     # 构建并启动全部服务
docker compose ps                # 查看状态（backend 应为 healthy）
docker compose logs -f backend   # 跟踪后端日志
docker compose restart backend   # 重启单个服务
docker compose down              # 停止（-v 连同 OpenObserve 数据卷一起清除）
```

> 说明：OpenObserve 官方镜像为 distroless（无 shell），无法做容器内健康检查；后端对其故障具备降级能力（`/ready=DEGRADED`，不崩溃），启动数秒后自动恢复。

## 业务接入（SDK）

业务系统推荐使用官方 SDK 写入日志（异步凑批、失败重试、队列满丢弃计数、W3C Trace Context 传播）：

- **Java SDK**：`sdk/java`，零第三方依赖，`mvn install` 后引用 `com.obsroman:trace-log-sdk:1.2.0`
- **Python SDK**：`sdk/python`，零第三方依赖，`pip install -e sdk/python`，示例 `python3 example.py`

完整用法（含 Spring Boot / FastAPI 集成示例）见 [docs/SDK.md](docs/SDK.md)，前端「SDK 文档」页面同步展示。

## 测试

```bash
cd backend
./mvnw test                                   # 66 个单元测试（不依赖外部服务）

# OpenObserve 集成测试（需本地 OpenObserve 运行中，默认跳过）
OO_IT_URL=http://localhost:5080 ./mvnw test -Dtest=OpenObserveLogStorageIT
```

## 项目结构

```
backend/                          # Spring Boot 3 + Java 17
  src/main/java/com/obsroman/tracelog/
    controller/                   # Log / Trace / Dashboard / Export / System（只做收发）
    service/                      # 写入 / 查询 / Trace / Dashboard / 导出 业务逻辑
    storage/                      # LogStorage 接口（LogCursor / StorageException）
      openobserve/                # OpenObserveLogStorage + SQL 构建（全部 SQL 集中于此）
    queue/                        # LogQueue（有界 + DROP 计数）/ LogWorker（凑批写入）
    security/                     # ApiKey / Registry / Bearer 鉴权过滤器
    model/                        # LogRecord / LogQuery / TraceResult / Dashboard* / 解析校验
    config/                       # TraceLogProperties / Bean 装配
    common/                       # ApiResponse / ErrorCode / 全局异常
frontend/                         # Vue 3 + ECharts + vue-router（Vite）
  src/views/DashboardView.vue     # Overview 单页（KPI + 趋势 + 分布 + Top + 最近 ERROR）
  src/views/SearchView.vue        # 多条件搜索 + 分页 + CSV/JSONL 导出
  src/views/TraceView.vue         # Trace 链路时间线
docker-compose.yml                # OpenObserve + backend + frontend(nginx)
sdk/java/                         # Java SDK（零依赖，JDK 17+，mvn install 后引用）
sdk/python/                       # Python SDK（零依赖，pip install -e sdk/python）
docs/API.md                       # API 完整文档（前端「API 文档」页同步渲染）
docs/SDK.md                       # SDK 使用文档（前端「SDK 文档」页同步渲染）
docs/CHANGELOG.md                 # 版本更新日志（前端右上角弹窗同步展示）
docs/OPENOBSERVE.md               # OpenObserve 配置说明
```

## 关键设计约束（P0）

- Controller 只收发请求；Service 负责业务；Storage 封装 OpenObserve；Queue 负责异步写入。
- `202 Accepted` 仅表示日志已进入本服务（队列），不保证已持久化；持久化失败按 DROP 计数，**日志故障不影响业务系统**。
- Search / Export 复用同一 `LogQuery`；导出为流式（查一批写一批），不整表进内存。
- 禁止前端传 SQL；查询条件由服务端构建为受控 SQL（值全部转义，关键词剔除引号）。
- Export 有查询超时与最大时间范围（24 小时）限制；Dashboard 默认最近 7 天，无最大时间范围限制。
- OpenObserve 只是 Storage 实现：更换存储只需新增 `LogStorage` 实现。

## API

完整接口文档见 [docs/API.md](docs/API.md)，OpenObserve 配置说明见 [docs/OPENOBSERVE.md](docs/OPENOBSERVE.md)。

## 后续路线（P1+，当前未实现）

数据脱敏、Span 生命周期、完整 APM、告警、Metrics 平台、多租户、复杂 RBAC、Sampling、日志归档、异步大文件导出、自定义 Dashboard 编辑器。
