from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Mapping, Protocol

from redis.exceptions import ResponseError


@dataclass(frozen=True)
class StreamDelivery:
    stream_id: str
    payload: Mapping[str, Any]
    claimed: bool = False


class TaskConsumer(Protocol):
    def ensure_group(self) -> None: ...

    def receive(self) -> StreamDelivery | None: ...

    def ack(self, delivery: StreamDelivery) -> None: ...


class RedisStreamConsumer:
    """Redis Stream Consumer Group 适配器，优先接管超时 pending 消息。"""

    def __init__(
        self,
        redis_client: Any,
        *,
        stream: str,
        group: str,
        consumer: str,
        claim_idle_ms: int = 30_000,
        block_ms: int = 1_000,
        payload_field: str = "payload",
    ) -> None:
        if claim_idle_ms < 1 or block_ms < 0:
            raise ValueError("Stream 超时参数非法")
        self._redis = redis_client
        self._stream = stream
        self._group = group
        self._consumer = consumer
        self._claim_idle_ms = claim_idle_ms
        self._block_ms = block_ms
        self._payload_field = payload_field
        self._claim_cursor = "0-0"

    def ensure_group(self) -> None:
        try:
            self._redis.xgroup_create(
                name=self._stream,
                groupname=self._group,
                id="0-0",
                mkstream=True,
            )
        except ResponseError as error:
            if "BUSYGROUP" not in str(error):
                raise

    def receive(self) -> StreamDelivery | None:
        claimed = self._claim_one()
        if claimed is not None:
            return claimed
        rows = self._redis.xreadgroup(
            groupname=self._group,
            consumername=self._consumer,
            streams={self._stream: ">"},
            count=1,
            block=self._block_ms,
        )
        if not rows:
            return None
        _, messages = rows[0]
        if not messages:
            return None
        stream_id, fields = messages[0]
        return self._delivery(stream_id, fields, claimed=False)

    def ack(self, delivery: StreamDelivery) -> None:
        self._redis.xack(self._stream, self._group, delivery.stream_id)

    def _claim_one(self) -> StreamDelivery | None:
        response = self._redis.xautoclaim(
            name=self._stream,
            groupname=self._group,
            consumername=self._consumer,
            min_idle_time=self._claim_idle_ms,
            start_id=self._claim_cursor,
            count=1,
        )
        if not response:
            return None
        self._claim_cursor = _decode(response[0])
        messages = response[1]
        if not messages:
            if self._claim_cursor == "0-0":
                self._claim_cursor = "0-0"
            return None
        stream_id, fields = messages[0]
        return self._delivery(stream_id, fields, claimed=True)

    def _delivery(
        self, stream_id: Any, fields: Mapping[Any, Any], *, claimed: bool
    ) -> StreamDelivery:
        normalized = {_decode(key): _decode(value) for key, value in fields.items()}
        raw = normalized.get(self._payload_field)
        if raw is None:
            raise ValueError(f"Stream 消息缺少 {self._payload_field} 字段")
        payload = json.loads(raw)
        if not isinstance(payload, Mapping):
            raise ValueError("Stream payload 必须是 JSON 对象")
        # The outbox keeps routing/index fields beside the JSON payload so Redis
        # consumers can inspect them without deserializing the body.  A worker
        # claim needs those same identifiers; merge only missing values to keep
        # an explicitly versioned payload authoritative.
        merged = dict(payload)
        for key in (
            "messageId",
            "taskId",
            "runId",
            "runner",
            "platform",
            "deliveryAttempt",
            "traceparent",
        ):
            if key not in merged and key in normalized:
                merged[key] = normalized[key]
        return StreamDelivery(_decode(stream_id), merged, claimed=claimed)


def _decode(value: Any) -> str:
    if isinstance(value, bytes):
        return value.decode("utf-8")
    return str(value)
