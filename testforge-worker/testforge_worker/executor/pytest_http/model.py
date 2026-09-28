from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Mapping
from urllib.parse import urlsplit


class RunnerStatus(str, Enum):
    """与 Attempt callback v1 一致的 Runner 终态。"""

    PASSED = "PASSED"
    ASSERTION_FAILED = "ASSERTION_FAILED"
    INFRA_FAILED = "INFRA_FAILED"


class FailureType(str, Enum):
    """pytest-http 会产生的确定性失败分类。"""

    PRODUCT_DEFECT = "PRODUCT_DEFECT"
    SCRIPT_ERROR = "SCRIPT_ERROR"
    ENVIRONMENT = "ENVIRONMENT"


@dataclass(frozen=True)
class HttpCaseSpec:
    """生成一个独立 HTTP pytest 用例所需的结构化输入。

    ``json_body`` 与 ``expected_json_subset`` 会先写入隔离目录里的 JSON 数据
    文件，不会拼接到 Python 源码中。这样即使参数来自外部契约，也不会把它
    当作 Python 代码执行。
    """

    name: str
    endpoint: str
    path: str = "/"
    method: str = "GET"
    expected_status: int = 200
    headers: Mapping[str, str] = field(default_factory=dict)
    query: Mapping[str, Any] = field(default_factory=dict)
    json_body: Any | None = None
    expected_json_subset: Any | None = None
    expected_body_contains: str | None = None
    expected_headers: Mapping[str, str] = field(default_factory=dict)
    request_timeout_seconds: float = 10.0

    def __post_init__(self) -> None:
        if not self.name.strip():
            raise ValueError("HTTP 用例名称不能为空")
        if not self.endpoint.strip():
            raise ValueError("HTTP endpoint 不能为空")
        endpoint = urlsplit(self.endpoint)
        if endpoint.scheme not in {"http", "https"} or not endpoint.netloc:
            raise ValueError("HTTP endpoint 必须是包含主机的 http/https URL")
        request_path = urlsplit(self.path)
        if request_path.scheme or request_path.netloc:
            raise ValueError("HTTP path 必须是相对于 endpoint 的路径")
        if not self.method.strip():
            raise ValueError("HTTP method 不能为空")
        if not 100 <= self.expected_status <= 599:
            raise ValueError("expected_status 必须在 100 到 599 之间")
        if self.request_timeout_seconds <= 0:
            raise ValueError("request_timeout_seconds 必须大于 0")


@dataclass(frozen=True)
class JUnitCaseResult:
    classname: str
    name: str
    outcome: str
    duration_seconds: float
    message: str | None = None
    exception_type: str | None = None
    details: str | None = None


@dataclass(frozen=True)
class JUnitSummary:
    tests: int
    failures: int
    errors: int
    skipped: int
    duration_seconds: float
    cases: tuple[JUnitCaseResult, ...]


@dataclass(frozen=True)
class PytestHttpFailure:
    type: FailureType
    message: str
    stack_digest: str | None = None
    retryable: bool = False
    details: Mapping[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class PytestHttpResult:
    status: RunnerStatus
    duration_ms: int
    summary: str
    exit_code: int | None
    junit: JUnitSummary | None
    junit_xml: bytes | None
    logs: str
    logs_truncated: bool
    failure: PytestHttpFailure | None = None
