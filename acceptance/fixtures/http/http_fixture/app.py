import asyncio

from fastapi import FastAPI, Query
from fastapi.responses import JSONResponse

from http_fixture import __version__
from http_fixture.scenarios import ScenarioState, should_fail


def create_app(scenario_state: ScenarioState | None = None) -> FastAPI:
    """Build an application with injectable state for deterministic tests."""

    state = scenario_state or ScenarioState()
    application = FastAPI(title="TestForge Acceptance HTTP Fixture", version=__version__)
    application.state.scenario_state = state

    @application.get("/health")
    def health() -> dict[str, str]:
        return {"service": "acceptance-http-fixture", "status": "UP", "version": __version__}

    @application.get("/api/v1/scenarios/success")
    def success() -> dict[str, object]:
        request_no = state.record("success")
        return {"scenario": "success", "requestNo": request_no, "result": "ok"}

    @application.get("/api/v1/scenarios/slow")
    async def slow(
        delay_ms: int = Query(default=250, ge=0, le=5_000),
    ) -> dict[str, object]:
        request_no = state.record("slow")
        await asyncio.sleep(delay_ms / 1_000)
        return {
            "scenario": "slow",
            "requestNo": request_no,
            "delayMs": delay_ms,
            "result": "ok",
        }

    @application.get("/api/v1/scenarios/error")
    def server_error() -> JSONResponse:
        request_no = state.record("error", outcome="failure")
        return JSONResponse(
            status_code=500,
            content={
                "scenario": "error",
                "requestNo": request_no,
                "result": "error",
                "error": "injected fixture failure",
            },
        )

    @application.get("/api/v1/scenarios/flaky", response_model=None)
    def flaky(
        failure_rate: float = Query(default=0.5, ge=0.0, le=1.0),
        seed: int | None = None,
    ) -> dict[str, object] | JSONResponse:
        failed = should_fail(failure_rate, seed)
        request_no = state.record("flaky", outcome="failure" if failed else "success")
        response = {
            "scenario": "flaky",
            "requestNo": request_no,
            "result": "error" if failed else "ok",
            "seed": seed,
            "failureRate": failure_rate,
        }
        if failed:
            response["error"] = "injected flaky failure"
            return JSONResponse(status_code=500, content=response)
        return response

    @application.get("/api/v1/state")
    def service_state() -> dict[str, object]:
        return state.details()

    return application


app = create_app()
state: ScenarioState = app.state.scenario_state
