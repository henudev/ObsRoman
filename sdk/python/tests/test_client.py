"""Trace Log SDK Python 单元测试（仅标准库，使用 http.server 模拟服务端）。

运行：python3 -m unittest discover -s tests -v
"""
import json
import queue as queue_module
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from trace_log_sdk import (
    TraceLogClient,
    TraceLogError,
    child_trace_context,
    generate_trace_context,
    parse_traceparent,
    record,
)


class FastThreadingHTTPServer(ThreadingHTTPServer):
    """跳过 server_bind 中的 socket.getfqdn()（本机 DNS 反查可能阻塞数十秒）。"""

    def server_bind(self):
        import socket
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.socket.bind(self.server_address)
        self.server_address = self.socket.getsockname()
        host, port = self.server_address[:2]
        self.server_name = host
        self.server_port = port


class FakeServer:
    """可编程的假服务端：记录请求，按脚本返回状态码。"""

    def __init__(self):
        self.requests = []
        self.status_script = queue_module.Queue()
        self.fail_all = False
        self.lock = threading.Lock()

        class Handler(BaseHTTPRequestHandler):
            def do_POST(inner_self):
                length = int(inner_self.headers.get("Content-Length", 0))
                body = inner_self.rfile.read(length).decode("utf-8")
                with self.lock:
                    self.requests.append({
                        "path": inner_self.path,
                        "auth": inner_self.headers.get("Authorization"),
                        "body": body,
                    })
                    if self.fail_all:
                        code = 500
                    else:
                        try:
                            code = self.status_script.get_nowait()
                        except queue_module.Empty:
                            code = 200
                payload = json.dumps({
                    "code": 0 if code == 200 else 1500,
                    "message": "ok" if code == 200 else "boom",
                    "data": {"accepted": body.count('"service"'), "rejected": 0},
                }).encode("utf-8")
                inner_self.send_response(code)
                inner_self.send_header("Content-Type", "application/json")
                inner_self.send_header("Content-Length", str(len(payload)))
                inner_self.end_headers()
                inner_self.wfile.write(payload)

            def log_message(inner_self, *args):
                pass

        self.server = FastThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self):
        host, port = self.server.server_address[:2]
        return f"http://{host}:{port}"

    def stop(self):
        self.server.shutdown()
        self.server.server_close()


class TraceLogClientTest(unittest.TestCase):
    def setUp(self):
        self.server = FakeServer()

    def tearDown(self):
        self.server.stop()

    def client(self, **kwargs):
        defaults = dict(endpoint=self.server.url, api_key="test-key",
                        max_retries=2, backoff=(0.01, 0.01, 0.01))
        defaults.update(kwargs)
        return TraceLogClient(**defaults)

    def test_async_send_flushed_as_batch(self):
        with self.client() as client:
            for _ in range(5):
                self.assertTrue(client.send(record("order-service", "创建订单成功",
                                                   environment="prod", duration_ms=123)))
            client.flush(5)
            self.assertEqual(0, client.queue_size)
            self.assertEqual(5, client.sent_count)
            self.assertEqual(0, client.dropped_count)
            first = self.server.requests[0]
            self.assertEqual("/api/v1/logs/batch", first["path"])
            self.assertEqual("Bearer test-key", first["auth"])
            self.assertIn("order-service", first["body"])

    def test_send_sync_single(self):
        with self.client() as client:
            client.send_sync(record("order-service", "创建订单成功"))
            self.assertEqual("/api/v1/logs", self.server.requests[0]["path"])
            self.assertEqual(1, client.sent_count)

    def test_send_sync_fails_fast_on_400(self):
        self.server.status_script.put(400)
        with self.client() as client:
            with self.assertRaises(TraceLogError):
                client.send_sync(record("order-service", "m"))
            self.assertEqual(1, len(self.server.requests))

    def test_sync_retries_then_succeeds(self):
        self.server.status_script.put(500)
        self.server.status_script.put(503)
        with self.client() as client:
            client.send_sync(record("order-service", "m"))
            self.assertEqual(3, len(self.server.requests))
            self.assertEqual(1, client.sent_count)

    def test_async_drops_when_server_always_fails(self):
        self.server.fail_all = True
        client = self.client(max_retries=1, backoff=(0.01, 0.01, 0.01))
        client.send(record("order-service", "m"))
        client.flush(10)
        self.assertEqual(1, client.dropped_count)
        self.assertEqual(0, client.sent_count)
        client.close()

    def test_send_batch_chunks_over_limit(self):
        with self.client() as client:
            entries = [record("order-service", f"m{i}") for i in range(501)]
            result = client.send_batch(entries)
            self.assertEqual(2, len(self.server.requests))
            self.assertEqual(501, result["accepted"] + result["rejected"])

    def test_queue_full_drops_without_blocking(self):
        # 容量 1，快速压入 50 条：后台线程来不及消费，必然出现队列满丢弃
        with self.client(queue_capacity=1, flush_interval=30) as client:
            results = [client.send(record("order-service", f"m{i}")) for i in range(50)]
            accepted = sum(1 for ok in results if ok)
            self.assertGreater(client.dropped_count, 0)
            self.assertEqual(50, accepted + client.dropped_count)
            client.flush(10)

    def test_oversized_record_dropped(self):
        with self.client() as client:
            self.assertFalse(client.send(record("order-service", "x" * 70000)))
            self.assertEqual(1, client.dropped_count)

    def test_trace_helpers(self):
        context = generate_trace_context()
        self.assertRegex(context.trace_id, r"^[0-9a-f]{32}$")
        self.assertRegex(context.span_id, r"^[0-9a-f]{16}$")
        parsed = parse_traceparent(context.traceparent())
        self.assertEqual(context.trace_id, parsed.trace_id)
        child = child_trace_context(parsed)
        self.assertEqual(parsed.trace_id, child.trace_id)
        self.assertIsNone(parse_traceparent("bad-input"))


if __name__ == "__main__":
    unittest.main()
