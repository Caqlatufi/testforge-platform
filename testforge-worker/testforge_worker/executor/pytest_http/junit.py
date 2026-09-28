from __future__ import annotations

import xml.etree.ElementTree as ElementTree

from .model import JUnitCaseResult, JUnitSummary


class JUnitParseError(ValueError):
    """JUnit 不存在、超限、损坏或结构不受支持。"""


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _as_float(value: str | None) -> float:
    if value is None:
        return 0.0
    try:
        return max(0.0, float(value))
    except ValueError:
        return 0.0


def _as_int(value: str | None, field: str) -> int:
    if value is None:
        return 0
    try:
        parsed = int(value)
    except ValueError as error:
        raise JUnitParseError(f"JUnit XML 的 {field} 不是整数: {value!r}") from error
    if parsed < 0:
        raise JUnitParseError(f"JUnit XML 的 {field} 不能为负数")
    return parsed


def parse_junit_xml(xml_bytes: bytes, *, max_bytes: int = 5 * 1024 * 1024) -> JUnitSummary:
    """解析 pytest JUnit XML，兼容 ``testsuite`` 与 ``testsuites`` 根节点。"""

    if not xml_bytes:
        raise JUnitParseError("JUnit XML 为空")
    if len(xml_bytes) > max_bytes:
        raise JUnitParseError(f"JUnit XML 超过 {max_bytes} 字节限制")
    lowered_prefix = xml_bytes[:4096].lower()
    if b"<!doctype" in lowered_prefix or b"<!entity" in lowered_prefix:
        raise JUnitParseError("JUnit XML 不允许 DOCTYPE 或 ENTITY 声明")
    try:
        root = ElementTree.fromstring(xml_bytes)
    except ElementTree.ParseError as error:
        raise JUnitParseError(f"JUnit XML 无法解析: {error}") from error
    if _local_name(root.tag) not in {"testsuite", "testsuites"}:
        raise JUnitParseError(f"JUnit XML 根节点不受支持: {_local_name(root.tag)}")

    cases: list[JUnitCaseResult] = []
    for element in root.iter():
        if _local_name(element.tag) != "testcase":
            continue
        outcome = "passed"
        message = None
        exception_type = None
        details = None
        for child in element:
            child_name = _local_name(child.tag)
            if child_name in {"failure", "error", "skipped"}:
                outcome = child_name
                message = child.attrib.get("message")
                exception_type = child.attrib.get("type")
                details = child.text.strip() if child.text and child.text.strip() else None
                break
        cases.append(
            JUnitCaseResult(
                classname=element.attrib.get("classname", ""),
                name=element.attrib.get("name", ""),
                outcome=outcome,
                duration_seconds=_as_float(element.attrib.get("time")),
                message=message,
                exception_type=exception_type,
                details=details,
            )
        )

    suites = [element for element in root.iter() if _local_name(element.tag) == "testsuite"]
    declared_tests = sum(_as_int(element.attrib.get("tests"), "tests") for element in suites)
    if not cases and declared_tests:
        raise JUnitParseError("JUnit XML 声明了测试数量但没有 testcase")

    failures = sum(case.outcome == "failure" for case in cases)
    errors = sum(case.outcome == "error" for case in cases)
    skipped = sum(case.outcome == "skipped" for case in cases)
    return JUnitSummary(
        tests=len(cases),
        failures=failures,
        errors=errors,
        skipped=skipped,
        duration_seconds=sum(case.duration_seconds for case in cases),
        cases=tuple(cases),
    )
