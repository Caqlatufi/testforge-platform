from __future__ import annotations

import unittest

from testforge_worker.executor.airtest import AirtestAdapter
from testforge_worker.executor.spi import (
    AutomationDriverDescriptor,
    AutomationDriverRegistry,
    AutomationPlatform,
    UnknownAutomationDriver,
)


class AutomationDriverSpiTest(unittest.TestCase):
    def test_airtest_descriptor_declares_three_demo_platforms(self) -> None:
        self.assertEqual("airtest", AirtestAdapter.descriptor.name)
        self.assertEqual("airtest", AirtestAdapter.descriptor.runner)
        self.assertEqual(
            {
                AutomationPlatform.WINDOWS,
                AutomationPlatform.ANDROID,
                AutomationPlatform.IOS,
            },
            set(AirtestAdapter.descriptor.platforms),
        )

    def test_registry_resolves_driver_and_exposes_runner_mapping(self) -> None:
        driver = AirtestAdapter()
        registry = AutomationDriverRegistry((driver,))

        self.assertIs(driver, registry.resolve("AIRTEST"))
        self.assertIs(driver, registry.as_runner_mapping()["airtest"])

    def test_registry_rejects_duplicate_runner(self) -> None:
        class AirtestRunnerAliasAdapter(AirtestAdapter):
            descriptor = AutomationDriverDescriptor(
                name="airtest-runner-alias",
                runner="airtest",
                platforms=AirtestAdapter.descriptor.platforms,
            )

        registry = AutomationDriverRegistry((AirtestAdapter(),))

        with self.assertRaisesRegex(ValueError, "runner 重复"):
            registry.register(AirtestRunnerAliasAdapter())

    def test_registry_rejects_duplicate_driver_name(self) -> None:
        class AirtestAliasAdapter(AirtestAdapter):
            descriptor = AutomationDriverDescriptor(
                name="airtest",
                runner="airtest-alias",
                platforms=AirtestAdapter.descriptor.platforms,
            )

        registry = AutomationDriverRegistry((AirtestAdapter(),))

        with self.assertRaisesRegex(ValueError, "name 重复"):
            registry.register(AirtestAliasAdapter())

    def test_registry_rejects_unknown_driver(self) -> None:
        registry = AutomationDriverRegistry()

        with self.assertRaises(UnknownAutomationDriver):
            registry.resolve("missing")


if __name__ == "__main__":
    unittest.main()
