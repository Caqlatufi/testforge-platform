from __future__ import annotations

import json
from pathlib import Path

from .model import HttpCaseSpec


_GENERATED_TEST = '''\
import json
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


CASE = json.loads(Path(__file__).with_name("case.json").read_text(encoding="utf-8"))


def _assert_subset(expected, actual, path="response"):
    if isinstance(expected, dict):
        assert isinstance(actual, dict), f"{path}: expected object, got {type(actual).__name__}"
        for key, value in expected.items():
            assert key in actual, f"{path}: missing key {key!r}"
            _assert_subset(value, actual[key], f"{path}.{key}")
        return
    if isinstance(expected, list):
        assert isinstance(actual, list), f"{path}: expected array, got {type(actual).__name__}"
        assert len(actual) >= len(expected), f"{path}: expected at least {len(expected)} items, got {len(actual)}"
        for index, value in enumerate(expected):
            _assert_subset(value, actual[index], f"{path}[{index}]")
        return
    assert actual == expected, f"{path}: expected {expected!r}, got {actual!r}"


def test_http_case():
    base_url = CASE["endpoint"].rstrip("/") + "/"
    url = urllib.parse.urljoin(base_url, CASE["path"].lstrip("/"))
    query = urllib.parse.urlencode(CASE["query"], doseq=True)
    if query:
        url += ("&" if "?" in url else "?") + query

    headers = dict(CASE["headers"])
    body = None
    if CASE["json_body"] is not None:
        body = json.dumps(CASE["json_body"], ensure_ascii=False).encode("utf-8")
        if not any(key.lower() == "content-type" for key in headers):
            headers["Content-Type"] = "application/json"

    request = urllib.request.Request(
        url,
        data=body,
        headers=headers,
        method=CASE["method"],
    )
    try:
        response = urllib.request.urlopen(request, timeout=CASE["request_timeout_seconds"])
    except urllib.error.HTTPError as error:
        response = error

    with response:
        status = response.status
        response_headers = response.headers
        response_body = response.read().decode("utf-8", errors="replace")

    assert status == CASE["expected_status"], (
        f"HTTP status: expected {CASE['expected_status']}, got {status}; "
        f"body={response_body[:1000]!r}"
    )
    for header, expected in CASE["expected_headers"].items():
        actual = response_headers.get(header)
        assert actual == expected, f"header {header!r}: expected {expected!r}, got {actual!r}"

    expected_text = CASE["expected_body_contains"]
    if expected_text is not None:
        assert expected_text in response_body, (
            f"response body does not contain {expected_text!r}; body={response_body[:1000]!r}"
        )

    expected_json = CASE["expected_json_subset"]
    if expected_json is not None:
        try:
            actual_json = json.loads(response_body)
        except json.JSONDecodeError as error:
            raise AssertionError(f"response is not valid JSON: {error}") from error
        _assert_subset(expected_json, actual_json)
'''


def generate_http_case(case: HttpCaseSpec, directory: Path) -> Path:
    """在 ``directory`` 中生成无代码注入风险的 pytest 用例。"""

    directory.mkdir(parents=True, exist_ok=True)
    payload = {
        "name": case.name,
        "endpoint": case.endpoint,
        "path": case.path,
        "method": case.method.upper(),
        "expected_status": case.expected_status,
        "headers": dict(case.headers),
        "query": dict(case.query),
        "json_body": case.json_body,
        "expected_json_subset": case.expected_json_subset,
        "expected_body_contains": case.expected_body_contains,
        "expected_headers": dict(case.expected_headers),
        "request_timeout_seconds": case.request_timeout_seconds,
    }
    data_path = directory / "case.json"
    data_path.write_text(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    test_path = directory / "test_http_case.py"
    test_path.write_text(_GENERATED_TEST, encoding="utf-8")
    return test_path
