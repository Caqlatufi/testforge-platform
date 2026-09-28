from __future__ import annotations

import threading
from collections.abc import Callable, Mapping
from concurrent.futures import ThreadPoolExecutor

from testforge_worker.callback.client import LeaseRejected
from testforge_worker.runtime.model import TaskEnvelope
from testforge_worker.runtime.worker import DuplicateInFlight, RunnerAdapter, WorkerRuntime

from .claim import ClaimDeferred
from .stream import StreamDelivery, TaskConsumer


class ConsumerWorker:
    """把消息 ACK 与控制面生命周期绑定，回调成功前绝不 ACK。"""

    def __init__(
        self,
        *,
        consumer: TaskConsumer,
        runtime: WorkerRuntime,
        runners: Mapping[str, RunnerAdapter],
        invalid_message: Callable[[StreamDelivery, Exception], None] | None = None,
        task_resolver: Callable[[StreamDelivery], TaskEnvelope] | None = None,
        max_concurrency: int = 1,
        execution_error: Callable[[Exception], None] | None = None,
    ) -> None:
        if max_concurrency < 1:
            raise ValueError("max_concurrency 必须大于 0")
        self._consumer = consumer
        self._runtime = runtime
        self._runners = dict(runners)
        self._invalid_message = invalid_message or (lambda delivery, error: None)
        self._task_resolver = task_resolver or (
            lambda delivery: TaskEnvelope.from_payload(delivery.payload)
        )
        self._max_concurrency = max_concurrency
        self._execution_error = execution_error or (lambda error: None)

    def run_once(self) -> bool:
        delivery = self._consumer.receive()
        if delivery is None:
            return False
        try:
            task = self._task_resolver(delivery)
            runner_name = task.execution.get("runner")
            runner = self._runners.get(str(runner_name))
            if runner is None:
                raise UnsupportedRunner(f"当前 Worker 不支持 Runner: {runner_name}")
        except UnsupportedRunner:
            # 能力路由错误可能在配置修复后恢复，保留 pending 供其他 Consumer claim。
            raise
        except ClaimDeferred:
            # 配额或设备当前繁忙；不 ACK，待 XAUTOCLAIM 重新领取。
            return True
        except LeaseRejected:
            self._consumer.ack(delivery)
            return True
        except (TypeError, ValueError, KeyError) as error:
            self._invalid_message(delivery, error)
            self._consumer.ack(delivery)
            return True

        try:
            self._runtime.execute(task, runner)
        except DuplicateInFlight:
            # 同一 Consumer 的长任务可能被 XAUTOCLAIM 再次看到。原执行仍持有
            # delivery，不能提前 ACK，否则 Worker 异常退出时消息将无法恢复。
            return True
        except LeaseRejected:
            # 控制面已经判定 Attempt 过期，消息不再有价值。
            self._consumer.ack(delivery)
            return True
        self._consumer.ack(delivery)
        return True

    def serve(self, stop: threading.Event) -> None:
        self._consumer.ensure_group()
        # 每个 slot 同步地“领取 -> 执行 -> ACK”一条消息。线程数就是硬并发上限，
        # 因而不会在内存中无界预取消息，也不会创建无界线程/进程。
        with ThreadPoolExecutor(
            max_workers=self._max_concurrency,
            thread_name_prefix="task-slot",
        ) as executor:
            futures = [executor.submit(self._serve_slot, stop) for _ in range(self._max_concurrency)]
            for future in futures:
                future.result()

    def _serve_slot(self, stop: threading.Event) -> None:
        while not stop.is_set():
            try:
                self.run_once()
            except Exception as error:
                # 消息保持 pending；同组 Worker 可在 claim idle 后恢复。单条任务的
                # 短暂网络/回调故障不应永久损失本 Worker 的一个并发 slot。
                self._execution_error(error)


class UnsupportedRunner(RuntimeError):
    pass
