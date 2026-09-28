import json
import argparse
from collections.abc import Sequence

from testforge_worker import __version__
from testforge_worker.service import ServiceConfig, WorkerService


def health_payload() -> dict[str, str]:
    return {
        "service": "testforge-worker",
        "status": "ready",
        "version": __version__,
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--serve", action="store_true")
    args, _ = parser.parse_known_args(argv)
    if args.serve:
        service = WorkerService(ServiceConfig.from_environment())
        try:
            service.serve()
        except KeyboardInterrupt:
            service.stop()
        return 0
    print(json.dumps(health_payload(), ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
