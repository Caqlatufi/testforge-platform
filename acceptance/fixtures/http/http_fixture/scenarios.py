from __future__ import annotations

from dataclasses import dataclass, field
from random import Random
from threading import Lock


@dataclass
class ScenarioState:
    _counts: dict[str, int] = field(default_factory=dict)
    _outcomes: dict[str, dict[str, int]] = field(default_factory=dict)
    _lock: Lock = field(default_factory=Lock)

    def record(self, scenario: str, outcome: str = "success") -> int:
        with self._lock:
            count = self._counts.get(scenario, 0) + 1
            self._counts[scenario] = count
            outcomes = self._outcomes.setdefault(scenario, {})
            outcomes[outcome] = outcomes.get(outcome, 0) + 1
            return count

    def snapshot(self) -> dict[str, int]:
        with self._lock:
            return dict(sorted(self._counts.items()))

    def details(self) -> dict[str, object]:
        with self._lock:
            return {
                "totalRequests": sum(self._counts.values()),
                "requests": dict(sorted(self._counts.items())),
                "outcomes": {
                    scenario: dict(sorted(outcomes.items()))
                    for scenario, outcomes in sorted(self._outcomes.items())
                },
            }

    def reset(self) -> None:
        """Clear in-memory counters for an isolated test application."""

        with self._lock:
            self._counts.clear()
            self._outcomes.clear()


def should_fail(failure_rate: float, seed: int | None) -> bool:
    if not 0.0 <= failure_rate <= 1.0:
        raise ValueError("failure_rate must be between 0 and 1")
    return Random(seed).random() < failure_rate
