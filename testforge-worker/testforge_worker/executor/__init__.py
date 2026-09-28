"""Runner Adapter 与 Automation Driver 扩展边界。"""

from .spi import (
    AutomationDriver,
    AutomationDriverDescriptor,
    AutomationDriverRegistry,
    AutomationPlatform,
)

__all__ = [
    "AutomationDriver",
    "AutomationDriverDescriptor",
    "AutomationDriverRegistry",
    "AutomationPlatform",
]
