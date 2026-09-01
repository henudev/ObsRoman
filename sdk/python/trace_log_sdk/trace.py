"""W3C Trace Context（traceparent: 00-{trace_id}-{span_id}-{flags}）工具。

请求入口存在 traceparent 时必须沿用原 trace_id；不存在时本地生成。
"""
from __future__ import annotations

import re
import secrets
from dataclasses import dataclass

_TRACEPARENT_RE = re.compile(r"^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")


@dataclass(frozen=True)
class TraceContext:
    trace_id: str
    span_id: str

    def traceparent(self) -> str:
        return format_traceparent(self.trace_id, self.span_id)


def parse_traceparent(traceparent: str | None) -> TraceContext | None:
    """解析 traceparent header；非法/缺失返回 None，调用方应回退到 generate_trace_context()。"""
    if not traceparent:
        return None
    match = _TRACEPARENT_RE.match(traceparent.strip().lower())
    if not match:
        return None
    _version, trace_id, span_id, _flags = match.groups()
    return TraceContext(trace_id=trace_id, span_id=span_id)


def generate_trace_context() -> TraceContext:
    """本地生成全新链路（无上游 traceparent 时使用）。"""
    return TraceContext(trace_id=secrets.token_hex(16), span_id=secrets.token_hex(8))


def child_trace_context(parent: TraceContext) -> TraceContext:
    """沿用上游 trace_id，生成新的 span_id（调用下游前用于传播）。"""
    return TraceContext(trace_id=parent.trace_id, span_id=secrets.token_hex(8))


def format_traceparent(trace_id: str, span_id: str) -> str:
    return f"00-{trace_id}-{span_id}-01"
