"""Trace Log SDK Python 使用示例。

前置条件：Trace Log Service 已启动（docker compose up -d），
且使用的 API Key 具有 log:write 权限。

运行：python3 example.py
"""
import sys

from trace_log_sdk import (
    TraceLogClient,
    child_trace_context,
    generate_trace_context,
    parse_traceparent,
    record,
)

ENDPOINT = "http://localhost:8080"
API_KEY = "dev-writer-key"


def main():
    with TraceLogClient(ENDPOINT, api_key=API_KEY) as client:
        # ---- 1. W3C Trace Context：入口有 traceparent 就沿用，没有就生成 ----
        incoming_traceparent = None  # 实际业务中来自请求 header
        context = parse_traceparent(incoming_traceparent) or generate_trace_context()
        print("traceparent:", context.traceparent())

        # ---- 2. 异步发送（推荐）：非阻塞入队，后台凑批写入 ----
        client.send(record(
            service="order-service",
            message="创建订单成功",
            level="INFO",
            environment="prod",
            event="order.create",
            trace_id=context.trace_id,
            span_id=context.span_id,
            request_id="req_sdk_001",
            user_id="10001",
            duration_ms=123,
            attributes={"order_id": "ORD001", "channel": "sdk"},
        ))

        # ---- 3. 模拟一次跨服务调用：沿用 trace_id，生成新 span_id ----
        downstream = child_trace_context(context)
        client.send(record(
            service="payment-service",
            message="支付成功",
            environment="prod",
            event="payment.success",
            trace_id=downstream.trace_id,
            span_id=downstream.span_id,
            request_id="req_sdk_001",
            duration_ms=456,
        ))

        # ---- 4. 同步发送（脚本/测试场景，失败会抛异常） ----
        client.send_sync(record(
            service="gateway",
            message="SDK 示例同步日志",
            level="INFO",
            environment="prod",
        ))

        client.flush(5)
        print(f"sent={client.sent_count} dropped={client.dropped_count}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:  # noqa: BLE001
        print(f"example failed: {error}", file=sys.stderr)
        sys.exit(1)
