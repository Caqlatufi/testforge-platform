from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from enum import Enum
from types import MappingProxyType

from testforge_worker.runtime.model import (
    AttemptResult,
    ProcessOutcome,
    ProcessSpec,
    TaskEnvelope,
)


class AutomationPlatform(str, Enum):
    WINDOWS = "WINDOWS"
    ANDROID = "ANDROID"
    IOS = "IOS"

    @classmethod
    def from_value(cls, value: object) -> "AutomationPlatform":
        normalized = str(value or "").strip().upper()
        try:
            return cls(normalized)
        except ValueError as error:
            raise UnsupportedAutomationPlatform(
                f"不支持的自动化平台: {normalized or '<empty>'}"
            ) from error


@dataclass(frozen=True)
class AutomationDriverDescriptor:
    name: str
    runner: str
    platforms: frozenset[AutomationPlatform]

    def __post_init__(self) -> None:
        name = self.name.strip().lower()
        runner = self.runner.strip().lower()
        if not name or not runner:
            raise ValueError("Automation Driver name/runner 不能为空")
        if not self.platforms:
            raise ValueError("Automation Driver 至少支持一个平台")
        object.__setattr__(self, "name", name)
        object.__setattr__(self, "runner", runner)


class AutomationDriver(ABC):
    """UI 自动化工具接入 Worker Runtime 的稳定 SPI。"""

    descriptor: AutomationDriverDescriptor

    def prepare(self, task: TaskEnvelope) -> ProcessSpec:
        platform = task_automation_platform(task)
        if platform not in self.descriptor.platforms:
            supported = ",".join(sorted(item.value for item in self.descriptor.platforms))
            raise UnsupportedAutomationPlatform(
                f"{self.descriptor.name} 不支持平台 {platform.value}; supported={supported}"
            )
        return self.prepare_automation(task, platform)

    @abstractmethod
    def prepare_automation(
        self, task: TaskEnvelope, platform: AutomationPlatform
    ) -> ProcessSpec:
        """把平台任务转换为由 Worker Runtime 托管的子进程规格。"""

    @abstractmethod
    def result(self, task: TaskEnvelope, outcome: ProcessOutcome) -> AttemptResult:
        """把驱动输出归一化为平台统一 AttemptResult。"""


class AutomationDriverRegistry:
    def __init__(self, drivers: Iterable[AutomationDriver] = ()) -> None:
        self._drivers: dict[str, AutomationDriver] = {}
        self._names: set[str] = set()
        for driver in drivers:
            self.register(driver)

    def register(self, driver: AutomationDriver) -> None:
        name = driver.descriptor.name
        runner = driver.descriptor.runner
        if name in self._names:
            raise ValueError(f"Automation Driver name 重复: {name}")
        if runner in self._drivers:
            raise ValueError(f"Automation Driver runner 重复: {runner}")
        self._drivers[runner] = driver
        self._names.add(name)

    def resolve(self, runner: str) -> AutomationDriver:
        normalized = runner.strip().lower()
        try:
            return self._drivers[normalized]
        except KeyError as error:
            raise UnknownAutomationDriver(f"未知 Automation Driver: {normalized}") from error

    def as_runner_mapping(self) -> Mapping[str, AutomationDriver]:
        return MappingProxyType(dict(self._drivers))


class UnsupportedAutomationPlatform(OSError):
    pass


class UnknownAutomationDriver(LookupError):
    pass


def task_automation_platform(task: TaskEnvelope) -> AutomationPlatform:
    explicit = task.execution.get("platform")
    if explicit:
        return AutomationPlatform.from_value(explicit)
    parameters = task.execution.get("parameters")
    if isinstance(parameters, Mapping):
        device_uri = str(parameters.get("deviceUri") or "")
        scheme = device_uri.partition(":")[0]
        if scheme:
            return AutomationPlatform.from_value(scheme)
    raise UnsupportedAutomationPlatform("自动化任务缺少 execution.platform")
