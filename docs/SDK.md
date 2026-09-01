# Trace Log SDK 使用文档

| | |
| --- | --- |
| **SDK 版本** | v1.2.0（与 [CHANGELOG.md](CHANGELOG.md) 同步） |
| **适用服务端** | Trace Log Service ≥ v1.0.0 |
| **Java 要求** | JDK 17+，**零第三方依赖**（仅 JDK 标准库） |
| **Python 要求** | Python 3.9+，**零第三方依赖**（仅标准库） |

SDK 封装了 Trace Log Service 的写入协议（`POST /api/v1/logs`、`POST /api/v1/logs/batch`），提供：**非阻塞异步发送、自动凑批、失败重试、队列满丢弃计数、W3C Trace Context 传播、trace_id 本地生成**。

> 查询 / Dashboard / 导出属于管理面操作，请直接调用 REST API（见 [API.md](API.md)）；SDK 只负责业务写入路径。

## 目录

- [核心语义](#核心语义)
- [Java SDK](#java-sdk)
- [Python SDK](#python-sdk)
- [W3C Trace Context 传播](#w3c-trace-context-传播)
- [统一日志模型速查](#统一日志模型速查)
- [最佳实践与 FAQ](#最佳实践与-faq)

---

## 核心语义

| 语义 | 说明 |
| --- | --- |
| 异步非阻塞 | `send()` 只做入队，立即返回，**绝不阻塞业务线程** |
| 自动凑批 | 后台线程按"凑满 200 条或 1 秒"批量写入（均可配置） |
| 重试 | 仅网络异常与 `408/429/5xx` 重试，默认 3 次、退避 100/500/2000ms；`400/401/403/413` 不重试 |
| 丢弃策略 | 队列满 / 单条超 64 KB / 重试耗尽 → 静默丢弃并计数（`dropped_count`），**日志故障不影响业务** |
| trace_id | 未传时 SDK 本地自动生成（32 位 hex）；有上游 `traceparent` 时必须沿用原 trace_id |
| 202 语义 | 服务端返回 202 仅表示"已进入服务"，不保证已持久化 |
| 同步接口 | `send_sync / send_batch`（Java 为 `sendSync / sendBatchSync`）供脚本与测试使用，失败抛异常 |

服务端限制：单条 ≤ 64 KB；单批 ≤ 500 条、≤ 5 MB（SDK 自动分块）；查询类限制见 API.md。

---

## Java SDK

### 获取

```bash
# 从源码安装到本地 Maven 仓库（零依赖，构建即用）
cd sdk/java
./../../.m2/wrapper/... # 或任意 Maven 3.8+
mvn install
```

项目坐标（安装后直接引用）：

```xml
<dependency>
    <groupId>com.obsroman</groupId>
    <artifactId>trace-log-sdk</artifactId>
    <version>1.2.0</version>
</dependency>
```

### 快速开始

```java
import com.obsroman.tracelog.sdk.LogRecord;
import com.obsroman.tracelog.sdk.TraceLogClient;
import com.obsroman.tracelog.sdk.TraceContext;

public class Demo {
    public static void main(String[] args) {
        try (TraceLogClient client = TraceLogClient.builder()
                .endpoint("http://localhost:8080")
                .apiKey("dev-writer-key")       // 需要 log:write 权限
                .build()) {

            // 1) 无链路上下文的普通日志：SDK 自动生成 trace_id
            client.send(LogRecord.builder()
                    .service("order-service")
                    .environment("prod")
                    .level("INFO")
                    .event("order.create")
                    .message("创建订单成功")
                    .requestId("req_001")
                    .userId("10001")
                    .durationMs(123)
                    .attribute("order_id", "ORD001")
                    .build());

            // 2) 已有链路上下文（如来自 HTTP 请求的 traceparent）
            TraceContext ctx = TraceContext.generate();
            client.send(LogRecord.builder()
                    .traceContext(ctx)              // 填充 trace_id + span_id
                    .service("order-service")
                    .environment("prod")
                    .level("ERROR")
                    .event("order.timeout")
                    .message("下游调用超时")
                    .statusCode(504)
                    .build());
        }   // close() 自动 flush 并尽力清空残留
    }
}
```

### 配置项

| Builder 方法 | 默认值 | 说明 |
| --- | --- | --- |
| `endpoint(String)` | 必填 | 服务端地址，如 `http://localhost:8080` |
| `apiKey(String)` | 必填 | 具备 `log:write` 权限的 API Key |
| `queueCapacity(int)` | 10000 | 内部队列容量，满后丢弃计数 |
| `batchSize(int)` | 200 | 凑批条数（上限 500） |
| `flushIntervalMs(long)` | 1000 | 凑批等待毫秒 |
| `connectTimeoutMs(long)` | 500 | 连接超时 |
| `writeTimeoutMs(long)` | 5000 | 请求超时 |
| `maxRetries(int)` | 3 | 网络类失败重试次数 |
| `backoffMs(long...)` | 100,500,2000 | 重试退避序列 |

### API 一览

| 方法 | 说明 |
| --- | --- |
| `boolean send(LogRecord)` | 异步入队；`false` = 丢弃（队列满或超 64 KB），已计数 |
| `void sendSync(LogRecord)` | 同步单条；失败抛 `TraceLogException` |
| `BatchResult sendBatchSync(List<LogRecord>)` | 同步批量（自动按 500 分块），返回 accepted/rejected |
| `void flush(long timeoutMillis)` | 等待队列清空 |
| `void close()` | flush + 停止后台线程（实现 `AutoCloseable`，推荐 try-with-resources） |
| `getSentCount() / getRejectedCount() / getDroppedCount() / getQueueSize()` | 运行计数（建议定期上报监控） |

`LogRecord.Builder` 字段与服务端模型一致：`timestamp/timestampNow、traceId、spanId、parentSpanId、requestId、service、serviceVersion、environment、level、type、event、message、userId、method、path、statusCode、durationMs、host、instance、attributes/attribute、traceContext`。

### Spring Boot 集成示例

```java
@Component
public class TraceLogConfig {
    @Bean(destroyMethod = "close")
    public TraceLogClient traceLogClient(
            @Value("${trace-log.endpoint}") String endpoint,
            @Value("${trace-log.api-key}") String apiKey) {
        return TraceLogClient.builder().endpoint(endpoint).apiKey(apiKey).build();
    }
}

/** 解析入口请求的 traceparent，贯穿整个请求周期 */
@Component
public class TraceContextFilter extends OncePerRequestFilter {
    private final TraceLogClient client;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        TraceContext ctx = TraceContext.parse(request.getHeader("traceparent"));
        if (ctx == null) {
            ctx = TraceContext.generate();
        }
        try (var scope = new TraceContextScope(ctx)) {   // 见下方 ThreadLocal 封装
            chain.doFilter(request, response);
        }
    }
}
```

业务代码里从 ThreadLocal 取当前上下文记日志（完整可运行封装建议放在公共包内）：

```java
public final class TraceContextScope implements AutoCloseable {
    private static final ThreadLocal<TraceContext> CURRENT = new ThreadLocal<>();
    private final TraceContext previous;

    public TraceContextScope(TraceContext context) {
        this.previous = CURRENT.get();
        CURRENT.set(context);
    }

    public static TraceContext current() {
        TraceContext ctx = CURRENT.get();
        return ctx != null ? ctx : TraceContext.generate();
    }

    public static void log(TraceLogClient client, String service, String level, String message) {
        TraceContext ctx = current();
        client.send(LogRecord.builder()
                .traceContext(ctx).service(service).level(level).message(message)
                .environment("prod").build());
    }

    @Override
    public void close() {
        CURRENT.set(previous);
    }
}
```

调用下游 HTTP 服务时继续传播（**禁止每个服务重新生成 trace_id**）：

```java
HttpRequest downstream = HttpRequest.newBuilder(URI.create(url))
        .header("traceparent", TraceContext.child(TraceContextScope.current()).traceparent())
        .build();
```

---

## Python SDK

### 获取

```bash
# 方式一：源码目录直接使用（推荐先跑 example）
cd sdk/python
python3 example.py

# 方式二：安装到当前环境（无任何第三方依赖）
pip install -e sdk/python
```

### 快速开始

```python
from trace_log_sdk import TraceLogClient, record, parse_traceparent, generate_trace_context

with TraceLogClient("http://localhost:8080", api_key="dev-writer-key") as client:
    # 1) 无链路上下文：SDK 自动生成 trace_id 与 timestamp
    client.send(record(
        service="order-service",
        message="创建订单成功",
        level="INFO",
        environment="prod",
        event="order.create",
        request_id="req_001",
        user_id="10001",
        duration_ms=123,
        attributes={"order_id": "ORD001"},
    ))

    # 2) 已有链路上下文（如来自请求 header 的 traceparent）
    ctx = parse_traceparent(header_value) or generate_trace_context()
    client.send(record(
        service="order-service",
        message="下游调用超时",
        level="ERROR",
        environment="prod",
        trace_id=ctx.trace_id,
        span_id=ctx.span_id,
        status_code=504,
    ))

    # 3) 同步发送（脚本 / 测试）
    client.send_sync(record("gateway", "启动完成"))
# with 退出时自动 flush 并关闭
```

### 配置项

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `endpoint` | 必填 | 服务端地址 |
| `api_key` | 必填 | 具备 `log:write` 权限的 API Key |
| `queue_capacity` | 10000 | 内部队列容量 |
| `batch_size` | 200 | 凑批条数（上限 500） |
| `flush_interval` | 1.0 | 凑批等待秒 |
| `connect_timeout` | 0.5 | 连接超时秒 |
| `timeout` | 5.0 | 请求超时秒 |
| `max_retries` | 3 | 网络类失败重试次数 |
| `backoff` | (0.1, 0.5, 2.0) | 重试退避序列（秒） |

### API 一览

| 方法 | 说明 |
| --- | --- |
| `send(entry: dict) -> bool` | 异步入队；`False` = 丢弃并计数 |
| `send_sync(entry: dict)` | 同步单条；失败抛 `TraceLogError` |
| `send_batch(entries: list) -> dict` | 同步批量（自动按 500 分块），返回 `{"accepted", "rejected"}` |
| `flush(timeout=10.0)` | 等待队列清空 |
| `close()` / `with` 语法 | flush + 停止后台线程 |
| `sent_count / rejected_count / dropped_count / queue_size` | 运行计数（线程安全） |
| `record(...)` | 构造符合统一模型的日志 dict；未传 `trace_id`/`timestamp` 时自动补齐 |

### FastAPI / Flask 中间件示例

```python
import time
from fastapi import FastAPI, Request
from trace_log_sdk import TraceLogClient, record, parse_traceparent, generate_trace_context, child_trace_context

app = FastAPI()
client = TraceLogClient("http://localhost:8080", api_key="dev-writer-key")

@app.middleware("http")
async def trace_middleware(request: Request, call_next):
    # 入口：沿用上游 trace_id，或本地生成
    ctx = parse_traceparent(request.headers.get("traceparent")) or generate_trace_context()
    start = time.monotonic()
    response = await call_next(request)
    duration_ms = int((time.monotonic() - start) * 1000)

    client.send(record(
        service="gateway",
        message=f"{request.method} {request.url.path}",
        level="INFO" if response.status_code < 500 else "ERROR",
        environment="prod",
        type="http",
        trace_id=ctx.trace_id,
        span_id=ctx.span_id,
        method=request.method,
        path=request.url.path,
        status_code=response.status_code,
        duration_ms=duration_ms,
        attributes={"ua": request.headers.get("user-agent", "")},
    ))
    # 响应头回传，供下游/前端关联
    response.headers["traceparent"] = child_trace_context(ctx).traceparent()
    return response
```

调用下游服务时传播：

```python
import httpx
resp = httpx.post(url, headers={"traceparent": child_trace_context(ctx).traceparent()})
```

---

## W3C Trace Context 传播

规范：请求 Header `traceparent: 00-{trace_id}-{span_id}-{flags}`，例：
`00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01`

处理规则（两个 SDK 行为一致）：

1. 入口存在 `traceparent` → **沿用原 trace_id**，本次处理生成新的 span_id；
2. 不存在 → 本地生成（SDK 的 `record()/LogRecord.builder` 也会兜底自动生成）；
3. 调用下游 → `child_context`（Java `TraceContext.child` / Python `child_trace_context`）生成新 span 并放 header 继续传播；
4. **禁止每个服务重新生成 trace_id**，否则链路断裂。

---

## 统一日志模型速查

```json
{
  "timestamp": "2026-09-01T14:20:30.123+08:00",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "span_id": "00f067aa0ba902b7",
  "service": "order-service",
  "environment": "prod",
  "level": "INFO",
  "type": "application",
  "event": "order.create",
  "message": "创建订单成功",
  "request_id": "req_001",
  "user_id": "10001",
  "duration_ms": 123,
  "attributes": {"order_id": "ORD001"}
}
```

必填：`service`（小写中划线）、`level`（TRACE/DEBUG/INFO/WARN/ERROR/FATAL）、`message`（非空）；`trace_id` 缺省由 SDK 生成；其余可选。**业务扩展字段一律放 `attributes`，一级字段白名单之外会被服务端拒绝（code=1002）**。

---

## 最佳实践与 FAQ

**Q：业务接口里应该用 `send` 还是 `send_sync`？**
永远用 `send`（异步）。`send_sync` 只用于脚本、离线任务、单元测试等允许阻塞的场景。

**Q：dropped_count 增加了怎么办？**
说明队列满 / 服务端不可用持续超过了重试窗口。先看服务端 `GET /ready`（openobserve 是否 DOWN、log_drop_total 是否增长），再检查网络与限流。SDK 丢弃是为了保护业务，属于预期降级行为。

**Q：如何保证一条请求的日志能串起来？**
入口解析 `traceparent`（没有就生成）→ 本次请求内所有日志带同一 trace_id → 调用下游时传 `child_context` 的 traceparent。之后在搜索页输入 trace_id 或调 `GET /api/v1/traces/{traceId}` 即可看到完整链路。

**Q：level 怎么选？**
INFO 常规、WARN 可自愈异常（超时重试、慢查询）、ERROR 需要关注的失败、FATAL 进程级故障。Dashboard 的错误率按 ERROR+FATAL 计算。

**Q：attributes 里放什么？**
业务主键（order_id、user_id 等）与排查所需的小体积上下文。大对象、敏感信息（脱敏属于后续版本）不要放。

**Q：多进程 / 多线程下注意什么？**
两个 SDK 的内部队列与计数器均线程安全；每个进程建议持有一个全局 client 实例（复用后台线程与连接），避免频繁创建销毁。
