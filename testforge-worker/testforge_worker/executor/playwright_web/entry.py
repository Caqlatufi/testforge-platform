from __future__ import annotations

import argparse
import importlib.util
import json
import traceback
from pathlib import Path
from time import monotonic


def _load(script: Path):
    spec = importlib.util.spec_from_file_location("testforge_playwright_case", script)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载 Playwright 脚本: {script}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    runner = getattr(module, "run", None)
    if not callable(runner):
        raise ValueError("Playwright 脚本必须导出 run(page, parameters) 函数")
    return runner


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--task", required=True)
    parser.add_argument("--script", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    payload = json.loads(Path(args.task).read_text(encoding="utf-8"))
    execution = payload.get("execution", {})
    parameters = dict(execution.get("parameters", {}))
    environment = execution.get("environment", {})
    if isinstance(environment, dict) and environment.get("endpoint"):
        parameters.setdefault("baseUrl", environment["endpoint"])
    started = monotonic()
    browser_log: list[str] = []
    result: dict[str, object]
    try:
        from playwright.sync_api import sync_playwright
        run = _load(Path(args.script))
        with sync_playwright() as playwright:
            launch_options = {"headless": bool(parameters.get("headless", True))}
            channel = str(parameters.get("browserChannel", "chrome")).strip()
            if channel:
                launch_options["channel"] = channel
            browser = playwright.chromium.launch(**launch_options)
            context = browser.new_context(viewport={"width": int(parameters.get("viewportWidth", 1440)), "height": int(parameters.get("viewportHeight", 1000))})
            context.tracing.start(screenshots=True, snapshots=True, sources=True)
            page = context.new_page()
            page.on("console", lambda message: browser_log.append(f"[{message.type}] {message.text}"))
            page.on("pageerror", lambda error: browser_log.append(f"[pageerror] {error}"))
            summary = run(page, parameters)
            page.screenshot(path=str(output / "final.png"), full_page=True)
            context.tracing.stop(path=str(output / "trace.zip"))
            browser.close()
        result = {"status": "PASSED", "summary": str(summary or "Playwright UI case passed")}
        code = 0
    except AssertionError as error:
        try:
            if "page" in locals(): page.screenshot(path=str(output / "failure.png"), full_page=True)
            if "context" in locals(): context.tracing.stop(path=str(output / "trace.zip"))
        except Exception:
            pass
        result = {"status": "ASSERTION_FAILED", "summary": str(error) or "UI assertion failed", "failure": {"type": "ASSERTION_FAILED", "message": str(error) or "UI assertion failed"}}
        browser_log.append(traceback.format_exc())
        code = 2
    except Exception as error:
        try:
            if "page" in locals(): page.screenshot(path=str(output / "failure.png"), full_page=True)
            if "context" in locals(): context.tracing.stop(path=str(output / "trace.zip"))
        except Exception:
            pass
        result = {"status": "INFRA_FAILED", "summary": str(error), "failure": {"type": "SCRIPT_ERROR", "message": str(error)}}
        browser_log.append(traceback.format_exc())
        code = 3
    result["durationMs"] = round((monotonic() - started) * 1000)
    (output / "browser.log").write_text("\n".join(browser_log), encoding="utf-8")
    (output / "playwright-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
