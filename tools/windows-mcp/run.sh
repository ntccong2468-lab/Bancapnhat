#!/usr/bin/env bash
set -euo pipefail
kit_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
runtime_dir="${VNCODE_WINDOWS_MCP_RUNTIME_DIR:-/workspace/windows-mcp-runtime}"
if [[ ! -x "$runtime_dir/.venv/bin/python" ]]; then
    printf '%s\n' 'Run tools/windows-mcp/install-client.sh first.' >&2
    exit 1
fi
exec "$runtime_dir/.venv/bin/python" "$kit_dir/client.py" "$@"
