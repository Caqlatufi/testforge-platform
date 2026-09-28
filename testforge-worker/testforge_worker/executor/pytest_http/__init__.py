"""pytest-http Runner 的稳定公开入口。

该包只负责生成、执行与解释 pytest HTTP 用例，不消费任务消息，也不向
控制面发送回调。上层 Worker runtime 可以把 :class:`PytestHttpResult` 转换成
公开的 Attempt callback 契约。
"""

from .junit import JUnitParseError, parse_junit_xml
from .model import (
    FailureType,
    HttpCaseSpec,
    JUnitCaseResult,
    JUnitSummary,
    PytestHttpFailure,
    PytestHttpResult,
    RunnerStatus,
)
from .runner import PytestHttpRunner

__all__ = [
    "FailureType",
    "HttpCaseSpec",
    "JUnitCaseResult",
    "JUnitParseError",
    "JUnitSummary",
    "PytestHttpFailure",
    "PytestHttpResult",
    "PytestHttpRunner",
    "RunnerStatus",
    "parse_junit_xml",
]
