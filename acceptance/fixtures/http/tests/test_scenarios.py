import unittest

from http_fixture.scenarios import ScenarioState, should_fail


class ScenarioStateTest(unittest.TestCase):
    def test_records_each_scenario_independently(self) -> None:
        state = ScenarioState()

        self.assertEqual(1, state.record("success"))
        self.assertEqual(2, state.record("success"))
        self.assertEqual(1, state.record("slow"))
        self.assertEqual({"slow": 1, "success": 2}, state.snapshot())

    def test_details_track_outcomes_and_reset(self) -> None:
        state = ScenarioState()
        state.record("flaky", outcome="failure")
        state.record("flaky", outcome="success")

        self.assertEqual(
            {"failure": 1, "success": 1}, state.details()["outcomes"]["flaky"]
        )

        state.reset()
        self.assertEqual(
            {"totalRequests": 0, "requests": {}, "outcomes": {}}, state.details()
        )

    def test_flaky_decision_is_reproducible_with_seed(self) -> None:
        first = should_fail(0.5, seed=42)
        second = should_fail(0.5, seed=42)

        self.assertEqual(first, second)
        self.assertFalse(first)

    def test_failure_rate_boundaries(self) -> None:
        self.assertFalse(should_fail(0.0, seed=1))
        self.assertTrue(should_fail(1.0, seed=1))


if __name__ == "__main__":
    unittest.main()
