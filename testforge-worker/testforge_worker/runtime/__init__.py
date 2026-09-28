"""Attempt runtime and lease lifecycle boundary."""
from .model import (
    AttemptFailure,
    AttemptResult,
    CallbackUrls,
    ProcessOutcome,
    ProcessSpec,
    TaskEnvelope,
)
from .process import LogSink, NullLogSink, SubprocessController

__all__ = [
    "AttemptFailure",
    "AttemptResult",
    "CallbackUrls",
    "ProcessOutcome",
    "ProcessSpec",
    "TaskEnvelope",
    "LogSink",
    "NullLogSink",
    "SubprocessController",
    "DuplicateInFlight",
    "RunnerAdapter",
    "WorkerRuntime",
]


def __getattr__(name: str):
    # callback.client 依赖 runtime.model；WorkerRuntime 再依赖 callback.client。
    # 延迟导出保持易用 API，同时避免包初始化时形成循环导入。
    if name in {"DuplicateInFlight", "RunnerAdapter", "WorkerRuntime"}:
        from .worker import DuplicateInFlight, RunnerAdapter, WorkerRuntime

        return {
            "DuplicateInFlight": DuplicateInFlight,
            "RunnerAdapter": RunnerAdapter,
            "WorkerRuntime": WorkerRuntime,
        }[name]
    raise AttributeError(name)
