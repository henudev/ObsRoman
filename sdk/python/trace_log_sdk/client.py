"""Trace Log Service Python SDK 客户端（仅标准库）。

语义与服务端一致：
- send()：非阻塞提交内存队列，立即返回；队列满或写入失败按 DROP 计数，绝不阻塞业务。
- 后台线程凑批（默认 200 条或 1 秒）批量写入；网络异常与 408/429/5xx 重试
  （默认 3 次，退避 100/500/2000ms），400/401/403/413 不重试。
- send_sync() / send_batch()：同步直发，失败抛 TraceLogError。
"""
from __future__ import annotations

import json
import queue
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from typing import Any, Dict, Iterable, List, Optional

from .trace import generate_trace_context

MAX_BATCH_RECORDS = 500
MAX_LOG_BYTES = 65536

ALLOWED_FIELDS = (
    "timestamp", "trace_id", "span_id", "parent_span_id", "request_id",
    "service", "service_version", "environment", "level", "type", "event",
    "message", "user_id", "method", "path", "status_code", "duration_ms",
    "host", "instance", "attributes",
)

NON_RETRYABLE_STATUS = (400, 401, 403, 413)


class TraceLogError(Exception):
    """同步接口在重试耗尽或服务端拒绝时抛出。"""


def record(
    service: str,
    message: str,
    level: str = "INFO",
    environment: str = "local",
    type: str = "application",
    timestamp: Optional[str] = None,
    trace_id: Optional[str] = None,
    span_id: Optional[str] = None,
    parent_span_id: Optional[str] = None,
    request_id: Optional[str] = None,
    user_id: Optional[str] = None,
    event: Optional[str] = None,
    method: Optional[str] = None,
    path: Optional[str] = None,
    status_code: Optional[int] = None,
    duration_ms: Optional[int] = None,
    host: Optional[str] = None,
    instance: Optional[str] = None,
    service_version: Optional[str] = None,
    attributes: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """构造一条符合统一日志模型的日志 dict。

    未传 trace_id 时自动本地生成（与服务端"无上游链路则本地生成"的规范一致）。
    """
    if not trace_id:
        trace_id = generate_trace_context().trace_id
    entry: Dict[str, Any] = {}
    values = {
        "timestamp": timestamp or datetime.now().astimezone().isoformat(timespec="milliseconds"),
        "trace_id": trace_id,
        "span_id": span_id,
        "parent_span_id": parent_span_id,
        "request_id": request_id,
        "service": service,
        "service_version": service_version,
        "environment": environment,
        "level": level,
        "type": type,
        "event": event,
        "message": message,
        "user_id": user_id,
        "method": method,
        "path": path,
        "status_code": status_code,
        "duration_ms": duration_ms,
        "host": host,
        "instance": instance,
        "attributes": attributes,
    }
    for key in ALLOWED_FIELDS:
        value = values.get(key)
        if value is not None:
            entry[key] = value
    return entry


class TraceLogClient:
    """Trace Log Service 客户端。

    用法（推荐 with 语法，退出时自动 flush 并关闭）::

        with TraceLogClient("http://localhost:8080", api_key="dev-writer-key") as client:
            client.send(record("order-service", "创建订单成功", trace_id=tid))
    """

    def __init__(
        self,
        endpoint: str,
        api_key: str,
        queue_capacity: int = 10_000,
        batch_size: int = 200,
        flush_interval: float = 1.0,
        connect_timeout: float = 0.5,
        timeout: float = 5.0,
        max_retries: int = 3,
        backoff: Iterable[float] = (0.1, 0.5, 2.0),
    ) -> None:
        if not api_key:
            raise ValueError("api_key is required")
        self._endpoint = endpoint.rstrip("/")
        self._api_key = api_key
        self._batch_size = min(max(1, batch_size), MAX_BATCH_RECORDS)
        self._flush_interval = flush_interval
        self._connect_timeout = connect_timeout
        self._timeout = timeout
        self._max_retries = max_retries
        self._backoff = list(backoff)

        self._queue: "queue.Queue[Dict[str, Any]]" = queue.Queue(maxsize=max(1, queue_capacity))
        # 已提交但尚未完成写入/丢弃的条数（flush 依据，无竞态）
        self._pending = 0
        self._pending_lock = threading.Lock()
        self._running = True
        self._sent_count = 0
        self._rejected_count = 0
        self._dropped_count = 0
        self._counter_lock = threading.Lock()

        self._worker = threading.Thread(target=self._run_loop, name="trace-log-sdk", daemon=True)
        self._worker.start()

    # ---------- 异步接口（推荐，业务路径使用） ----------

    def send(self, entry: Dict[str, Any]) -> bool:
        """非阻塞提交日志到内部队列，由后台线程凑批写入。

        Returns:
            False 表示日志超限或队列满被丢弃（已计数，不影响调用方）。
        """
        payload = json.dumps(entry, ensure_ascii=False).encode("utf-8")
        if len(payload) > MAX_LOG_BYTES:
            with self._counter_lock:
                self._dropped_count += 1
            return False
        try:
            self._queue.put_nowait(entry)
        except queue.Full:
            with self._counter_lock:
                self._dropped_count += 1
            return False
        with self._pending_lock:
            self._pending += 1
        return True

    # ---------- 同步接口（脚本/测试/强一致场景使用） ----------

    def send_sync(self, entry: Dict[str, Any]) -> None:
        """同步单条写入；校验失败（400）或重试耗尽抛 TraceLogError。"""
        payload = json.dumps(entry, ensure_ascii=False).encode("utf-8")
        if len(payload) > MAX_LOG_BYTES:
            raise TraceLogError(f"log record exceeds {MAX_LOG_BYTES} bytes")
        self._post_with_retry("/api/v1/logs", payload, "send log")
        with self._counter_lock:
            self._sent_count += 1

    def send_batch(self, entries: List[Dict[str, Any]]) -> Dict[str, int]:
        """同步批量写入：自动按 500 条分块；返回 {"accepted": n, "rejected": m}。"""
        accepted = 0
        rejected = 0
        for start in range(0, len(entries), MAX_BATCH_RECORDS):
            chunk = entries[start:start + MAX_BATCH_RECORDS]
            payload = json.dumps({"logs": chunk}, ensure_ascii=False).encode("utf-8")
            body = self._post_with_retry("/api/v1/logs/batch", payload, "send batch")
            data = (body or {}).get("data") or {}
            accepted += int(data.get("accepted", 0))
            rejected += int(data.get("rejected", 0))
        with self._counter_lock:
            self._sent_count += accepted + rejected
            self._rejected_count += rejected
        return {"accepted": accepted, "rejected": rejected}

    # ---------- 生命周期 ----------

    def flush(self, timeout: float = 10.0) -> None:
        """等待内部队列清空（最多 timeout 秒）。"""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            with self._pending_lock:
                if self._pending == 0:
                    return
            time.sleep(0.02)

    def close(self) -> None:
        """停止后台线程并尽力清空残留日志。"""
        self._running = False
        self._worker.join(timeout=3)
        remaining: List[Dict[str, Any]] = []
        try:
            while True:
                remaining.append(self._queue.get_nowait())
        except queue.Empty:
            pass
        if remaining:
            try:
                self._post_batch_chunk(remaining)
                with self._counter_lock:
                    self._sent_count += len(remaining)
            except Exception:
                with self._counter_lock:
                    self._dropped_count += len(remaining)
            finally:
                with self._pending_lock:
                    self._pending -= len(remaining)

    def __enter__(self) -> "TraceLogClient":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

    # ---------- 计数器 ----------

    @property
    def sent_count(self) -> int:
        with self._counter_lock:
            return self._sent_count

    @property
    def rejected_count(self) -> int:
        with self._counter_lock:
            return self._rejected_count

    @property
    def dropped_count(self) -> int:
        with self._counter_lock:
            return self._dropped_count

    @property
    def queue_size(self) -> int:
        return self._queue.qsize()

    # ---------- 内部实现 ----------

    def _run_loop(self) -> None:
        while self._running or self._queue.qsize() > 0:
            try:
                first = self._queue.get(timeout=self._flush_interval)
            except queue.Empty:
                continue
            batch = [first]
            while len(batch) < self._batch_size:
                try:
                    batch.append(self._queue.get_nowait())
                except queue.Empty:
                    break
            try:
                self._post_batch_chunk(batch)
                with self._counter_lock:
                    self._sent_count += len(batch)
            except Exception:
                with self._counter_lock:
                    self._dropped_count += len(batch)
            finally:
                with self._pending_lock:
                    self._pending -= len(batch)

    def _post_batch_chunk(self, entries: List[Dict[str, Any]]) -> None:
        for start in range(0, len(entries), MAX_BATCH_RECORDS):
            chunk = entries[start:start + MAX_BATCH_RECORDS]
            payload = json.dumps({"logs": chunk}, ensure_ascii=False).encode("utf-8")
            self._post_with_retry("/api/v1/logs/batch", payload, "send batch")

    def _post_with_retry(self, path: str, payload: bytes, action: str) -> Optional[Dict[str, Any]]:
        last_error: Optional[Exception] = None
        for attempt in range(self._max_retries + 1):
            if attempt > 0:
                time.sleep(self._backoff[min(attempt - 1, len(self._backoff) - 1)])
            try:
                return self._post(path, payload)
            except TraceLogError as error:
                raise error  # 不可重试（400/401/403/413）
            except Exception as error:  # 网络异常 / 408/429/5xx
                last_error = error
        raise TraceLogError(f"{action} failed after {self._max_retries} retries: {last_error}")

    def _post(self, path: str, payload: bytes) -> Optional[Dict[str, Any]]:
        request = urllib.request.Request(
            self._endpoint + path,
            data=payload,
            method="POST",
            headers={
                "Authorization": f"Bearer {self._api_key}",
                "Content-Type": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=self._timeout) as response:
                body = response.read().decode("utf-8")
                return json.loads(body) if body else None
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")[:160]
            if error.code in NON_RETRYABLE_STATUS:
                raise TraceLogError(f"HTTP {error.code}: {detail}") from error
            raise OSError(f"HTTP {error.code}: {detail}") from error
