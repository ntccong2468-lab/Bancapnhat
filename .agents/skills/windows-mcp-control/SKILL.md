---
name: windows-mcp-control
description: Connect from the Codex cloud shell to an authenticated Windows-MCP worker, open VN Code, inspect real screenshots and UI state, and reproduce bugs before changing source.
---

# Windows desktop testing for VN Code

The official Windows-MCP server runs on the user's Windows desktop. The Codex
workspace is Linux and holds the MCP client, source, builds and test evidence.
Read `tools/windows-mcp/README.md` and current memory before setup or use.

## Connection

- Run `tools/windows-mcp/install-client.sh` if its dedicated client venv is absent.
- Use a private, ignored `connection.local.json` with `url` and `app_executable`,
  or `VNCODE_WINDOWS_MCP_URL` for the URL. The bearer token must come from
  `VNCODE_WINDOWS_MCP_TOKEN` configured in environment Secrets for the exact
  destination. Never request tokens in chat, commit them, print them, or put them
  in command arguments. Preserve the environment proxy and CA verification.
- `tools/windows-mcp/run.sh --config /path/to/connection.local.json probe` checks
  MCP initialization and tools. `MCP_PROTOCOL_READY` is not desktop acceptance.
- No configured/working endpoint means Windows is not connected. Installing this
  skill or cloning the server into Linux does not create a Windows desktop.

## Observe, act, verify

1. Read the current request and authorization; do not infer paid purchases,
   activation, signing, messages or seller API writes from a request to test UI.
2. Run `capture`, then view the returned local image using `view_image`. Confirm
   the visible app, store and current state. Screenshots/logs may contain private
   data; keep raw captures outside Git and outside release artifacts.
3. Use `snapshot` for current element IDs; reacquire after changes. A text error
   from Screenshot/Snapshot is not successful observation. If JavaFX accessibility
   is incomplete, use inspected screenshot coordinates and keyboard navigation.
4. `launch` starts only the configured local `VN code.exe`/`VNcode.exe` with no
   arguments. A PID alone does not prove the expected window opened. Capture and
   inspect afterward. Generic `call` accepts only UI input/wait/control status.
5. Pass input as an arguments JSON file: `call Click --arguments-file /tmp/click.json`.
   Read the live tool schema from `probe`/upstream before guessing parameters.
   Keep server human takeover and consent enabled; yield when the person acts.
6. Reproduce the specific failure; record expected/actual behavior and sanitized
   evidence. Fix source, run relevant regression tests, build, then verify the
   new build on Windows. Do not claim all bugs found or a seller action succeeded
   merely because a tool call returned or HTTP status was 200.

If a launch/input call times out or disconnects, its outcome may be unknown.
Read/capture the current state before repeating it; do not assume the action
failed or blindly repeat a button press that could submit data.

The tool allowlist and fixed launch helper are not an OS sandbox: mouse/keyboard
actions can operate other programs. Stay within the authorized app and workflow.
Never auto-publish app builds as a side effect of connecting the desktop.
