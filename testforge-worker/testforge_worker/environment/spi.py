from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Mapping
from dataclasses import dataclass, field
from types import MappingProxyType

from testforge_worker.executor.spi import AutomationPlatform


@dataclass(frozen=True)
class SandboxSession:
    """A single environment owned by one UI Worker.

    runtime_environment may contain an ephemeral bridge token.  It is hidden from
    repr/equality and exposed as an immutable mapping so it cannot accidentally
    become part of a task payload or diagnostic dump.
    """

    environment_id: str
    platform: AutomationPlatform
    device_id: str
    device_uri: str
    features: tuple[str, ...]
    resolution: str
    process_id: int | None
    runtime_environment: Mapping[str, str] = field(repr=False, compare=False)

    def __post_init__(self) -> None:
        for name, value in (
            ("environment_id", self.environment_id),
            ("device_id", self.device_id),
            ("device_uri", self.device_uri),
        ):
            if not value.strip():
                raise ValueError(f"SandboxSession.{name} 不能为空")
        object.__setattr__(
            self,
            "runtime_environment",
            MappingProxyType(dict(self.runtime_environment)),
        )


class SandboxEnvironmentProvider(ABC):
    """Replaceable sandbox base controlled by the Worker lifecycle."""

    @abstractmethod
    def start(self) -> SandboxSession:
        """Start and authenticate one sandbox, returning only after it is healthy."""

    @abstractmethod
    def health(self, session: SandboxSession) -> bool:
        """Return whether the exact owned session is still usable."""

    @abstractmethod
    def stop(self, session: SandboxSession) -> None:
        """Stop the sandbox and all children.  The operation must be idempotent."""
