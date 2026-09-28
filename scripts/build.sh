#!/usr/bin/env sh
set -eu

platform_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
component=${1:-all}

build_backend() {
  (cd "$platform_root/testforge-app" && ./gradlew :app:bootJar --no-daemon)
}

build_client() {
  (cd "$platform_root/testforge-client" && npm ci && npm run build)
}

build_python() {
  project=$1
  (
    cd "$platform_root/$project"
    export PYTHONPYCACHEPREFIX="$PWD/build/pycache"
    if [ -x .venv/bin/python ]; then
      project_python=.venv/bin/python
    elif [ -x .venv/Scripts/python.exe ]; then
      project_python=.venv/Scripts/python.exe
    else
      python -m venv .venv
      if [ -x .venv/bin/python ]; then
        project_python=.venv/bin/python
      else
        project_python=.venv/Scripts/python.exe
      fi
    fi
    "$project_python" -m pip install -e .
    "$project_python" -m pip wheel . --no-deps --wheel-dir build/wheels
  )
}

build_mcp() {
  (
    cd "$platform_root/testforge-mcp"
    mkdir -p build
    go build -trimpath -o build/testforge-mcp ./cmd/testforge-mcp
  )
}

case "$component" in
  backend) build_backend ;;
  client) build_client ;;
  worker) build_python testforge-worker ;;
  mcp) build_mcp ;;
  all)
    build_backend
    build_client
    build_python testforge-worker
    build_mcp
    ;;
  *)
    echo "Usage: $0 [all|backend|client|worker|mcp]" >&2
    exit 2
    ;;
esac
