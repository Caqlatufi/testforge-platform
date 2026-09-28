import unittest

from fastapi.testclient import TestClient

from http_fixture.app import create_app
from http_fixture.scenarios import ScenarioState


class MockGameApiTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client = TestClient(create_app(ScenarioState()))

    def test_health_and_success_scenario(self) -> None:
        self.assertEqual("UP", self.client.get("/health").json()["status"])

        response = self.client.get("/api/v1/scenarios/success")

        self.assertEqual(200, response.status_code)
        self.assertEqual("ok", response.json()["result"])

    def test_slow_scenario_accepts_zero_delay(self) -> None:
        response = self.client.get("/api/v1/scenarios/slow", params={"delay_ms": 0})

        self.assertEqual(200, response.status_code)
        self.assertEqual(0, response.json()["delayMs"])

    def test_error_scenario_returns_500(self) -> None:
        response = self.client.get("/api/v1/scenarios/error")

        self.assertEqual(500, response.status_code)

    def test_flaky_scenario_can_be_forced_to_fail(self) -> None:
        response = self.client.get(
            "/api/v1/scenarios/flaky",
            params={"failure_rate": 1.0, "seed": 42},
        )

        self.assertEqual(500, response.status_code)
        self.assertEqual("error", response.json()["result"])

    def test_state_exposes_scenario_counts(self) -> None:
        self.client.get("/api/v1/scenarios/success")

        response = self.client.get("/api/v1/state")

        self.assertEqual(1, response.json()["requests"]["success"])
        self.assertEqual(1, response.json()["outcomes"]["success"]["success"])

    def test_state_tracks_expected_and_random_failures(self) -> None:
        self.client.get("/api/v1/scenarios/error")
        self.client.get(
            "/api/v1/scenarios/flaky",
            params={"failure_rate": 1.0, "seed": 42},
        )

        state = self.client.get("/api/v1/state").json()

        self.assertEqual(2, state["totalRequests"])
        self.assertEqual({"failure": 1}, state["outcomes"]["error"])
        self.assertEqual({"failure": 1}, state["outcomes"]["flaky"])

    def test_slow_delay_is_bounded(self) -> None:
        response = self.client.get("/api/v1/scenarios/slow", params={"delay_ms": 5001})

        self.assertEqual(422, response.status_code)


if __name__ == "__main__":
    unittest.main()
