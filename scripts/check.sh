#!/usr/bin/env sh
set -eu

platform_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
install=true
smoke=false
for arg in "$@"; do
  case "$arg" in
    --skip-install) install=false ;;
    --compose-smoke) smoke=true ;;
    *) echo "Usage: $0 [--skip-install] [--compose-smoke]" >&2; exit 2 ;;
  esac
done
(cd "$platform_root/testforge-app" && ./gradlew test verifyModuleBoundaries --no-daemon)
(
  cd "$platform_root/testforge-worker"
  worker_python=python
  if [ -x .venv/bin/python ]; then worker_python=.venv/bin/python; fi
  if [ "$install" = true ]; then "$worker_python" -m pip install -e .; fi
  "$worker_python" -m pytest -q
)
(cd "$platform_root/testforge-mcp" && go test ./... && go vet ./...)
(
  cd "$platform_root/testforge-client"
  if [ "$install" = true ]; then npm ci; fi
  npm test -- --run
)
(
  cd "$platform_root/contracts"
  if [ "$install" = true ]; then npm ci; fi
  npm run validate
)
python "$platform_root/acceptance/validate.py"
python "$platform_root/acceptance/evidence/build_index.py" --check
if [ "$smoke" = true ]; then bash "$platform_root/acceptance/smoke/compose-smoke.sh"; fi
echo '[Check] All selected checks passed.'
