#!/usr/bin/env bash
set -euo pipefail
kit_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
runtime_dir="${VNCODE_WINDOWS_MCP_RUNTIME_DIR:-/workspace/windows-mcp-runtime}"
export UV_CACHE_DIR="${UV_CACHE_DIR:-$runtime_dir/cache}"
export UV_PYTHON_INSTALL_DIR="${UV_PYTHON_INSTALL_DIR:-$runtime_dir/python}"
mkdir -p -- "$runtime_dir"
command -v uv >/dev/null || { printf '%s\n' 'Install uv from https://docs.astral.sh/uv/ first.' >&2; exit 1; }
if [[ ! -x "$runtime_dir/.venv/bin/python" ]]; then
    uv venv --python 3.12 "$runtime_dir/.venv"
fi
"$runtime_dir/.venv/bin/python" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else "Windows MCP client requires Python 3.11 or newer; use a new dedicated runtime directory.")'
uv pip sync --system-certs --require-hashes --python "$runtime_dir/.venv/bin/python" "$kit_dir/requirements.lock"
"$runtime_dir/.venv/bin/python" -c 'import importlib.metadata; print("Windows MCP cloud client installed; SDK " + importlib.metadata.version("mcp")); print("Windows worker connection is not yet verified.")'
