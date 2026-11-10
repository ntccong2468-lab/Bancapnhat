"""Synthetic MCP protocol fixture; it does not control or emulate Windows desktop UI."""
from __future__ import annotations

import argparse
import asyncio
import base64
import json
import os
from pathlib import Path
import struct
import zlib

import uvicorn
from mcp.server.mcpserver import MCPServer
from mcp.types import CallToolResult, ImageContent, TextContent

def _png_chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


# A complete 1x1 RGBA PNG with valid chunk lengths/CRCs, generated without an image dependency.
PNG_BYTES = (b"\x89PNG\r\n\x1a\n"
             + _png_chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0))
             + _png_chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00\xff"))
             + _png_chunk(b"IEND", b""))
PNG_BASE64 = base64.b64encode(PNG_BYTES).decode("ascii")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--mode", default="image")
    options = parser.parse_args()
    token = os.environ["FIXTURE_MCP_TOKEN"]

    def record(kind: str, **values: object) -> None:
        with options.log.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps({"kind": kind, **values}) + "\n")

    server_name = "synthetic-echo-" + token if options.mode == "echo-server" else "synthetic-windows-mcp-fixture"
    server = MCPServer(server_name, version="0.0.0-fixture")

    @server.tool(name="Screenshot")
    async def screenshot(display: list[int] | None = None, region: list[int] | None = None) -> CallToolResult:
        record("tool", name="Screenshot", arguments={"display": display, "region": region})
        if options.mode == "delay":
            await asyncio.sleep(8)
        if options.mode == "text-error":
            return CallToolResult(content=[TextContent(type="text", text="Error capturing screenshot: synthetic capture unavailable")])
        if options.mode == "error-flag":
            return CallToolResult(content=[TextContent(type="text", text="Synthetic tool failure")], is_error=True)
        if options.mode == "malformed-image":
            return CallToolResult(content=[ImageContent(type="image", data="not-valid-base64!", mime_type="image/png")])
        if options.mode == "echo-token":
            return CallToolResult(content=[TextContent(type="text", text="Synthetic echo " + token)], is_error=True)
        return CallToolResult(content=[ImageContent(type="image", data=PNG_BASE64, mime_type="image/png")])

    @server.tool(name="Snapshot")
    def snapshot(use_vision: bool = False) -> str:
        record("tool", name="Snapshot", arguments={"use_vision": use_vision})
        return "Synthetic fixture window: VN Code. This is protocol-test data, not an observed app."

    @server.tool(name="App")
    def app(mode: str = "launch", executable: str | None = None, args: list[str] | None = None,
            cwd: str | None = None, name: str | None = None) -> str:
        record("tool", name="App", arguments={"mode": mode, "executable": executable, "args": args, "cwd": cwd, "name": name})
        return json.dumps({"pid": 12345, "executable": executable, "args": args or []})

    @server.tool(name="Click")
    def click(loc: list[int]) -> str:
        record("tool", name="Click", arguments={"loc": loc})
        return "Synthetic click acknowledged"

    @server.tool(name="ControlStatus")
    def control_status() -> str:
        record("tool", name="ControlStatus", arguments={})
        return "Synthetic status: user; enabled=true"

    @server.tool(name="PowerShell")
    def powershell(command: str = "") -> str:
        record("tool", name="PowerShell", arguments={"command": command})
        raise RuntimeError("This fixture tool must never be invoked by the restricted client")

    application = server.streamable_http_app(stateless_http=True, json_response=True)

    async def authenticated(scope, receive, send):
        if scope["type"] != "http":
            await application(scope, receive, send)
            return
        if scope["path"] == "/health":
            await send({"type": "http.response.start", "status": 200, "headers": [(b"content-type", b"text/plain")]})
            await send({"type": "http.response.body", "body": b"synthetic fixture only"})
            return
        headers = dict(scope["headers"])
        valid = headers.get(b"authorization", b"").decode() == "Bearer " + token
        record("request", authorized=valid, method=scope["method"], path=scope["path"])
        if not valid:
            await send({"type": "http.response.start", "status": 401, "headers": [(b"content-type", b"application/json")]})
            await send({"type": "http.response.body", "body": b'{"error":"Unauthorized"}'})
            return
        await application(scope, receive, send)

    uvicorn.run(authenticated, host="127.0.0.1", port=options.port, log_level="error")


if __name__ == "__main__":
    main()
