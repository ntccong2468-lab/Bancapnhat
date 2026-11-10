#!/usr/bin/env python3
"""Codex shell client for a separately installed, authenticated Windows-MCP worker."""
from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import json
import logging
import math
import os
from pathlib import Path, PureWindowsPath
import re
import sys
from urllib.parse import urlsplit
import uuid

import httpx2
from mcp import ClientSession, types
from mcp.client.streamable_http import streamable_http_client

ALLOWED_CALLS = {"Click", "Type", "Scroll", "Move", "Shortcut", "Wait", "WaitFor", "ControlStatus"}
MAX_IMAGE = 16 * 1024 * 1024


class ClientError(Exception):
    pass


def validate_url(value: str) -> str:
    try:
        url = urlsplit(value)
        port = url.port
    except ValueError:
        raise ClientError("MCP_URL_INVALID") from None
    if (not url.hostname or url.username is not None or url.password is not None
            or url.query or url.fragment or (port is not None and not 1 <= port <= 65535)
            or any(char.isspace() for char in value) or "\\" in value):
        raise ClientError("MCP_URL_INVALID")
    local = url.hostname in {"localhost", "127.0.0.1", "::1"}
    if url.scheme != "https" and not (url.scheme == "http" and local):
        raise ClientError("MCP_HTTPS_REQUIRED")
    return value


def validate_app(value: str) -> str:
    path = PureWindowsPath(value)
    if (not path.is_absolute() or not re.fullmatch(r"[A-Za-z]:", path.drive)
            or ".." in path.parts or path.name.casefold() not in {"vn code.exe", "vncode.exe"}
            or any(char in value for char in ("\x00", "\n", "\r", '"'))):
        raise ClientError("MCP_VNCODE_EXECUTABLE_REQUIRED")
    return str(path)


def load_config(path: str | None) -> dict:
    try:
        config = json.loads(Path(path).read_text(encoding="utf-8-sig")) if path else {}
    except (OSError, ValueError):
        raise ClientError("MCP_CONFIG_INVALID") from None
    if not isinstance(config, dict):
        raise ClientError("MCP_CONFIG_INVALID")
    return config


def request_for(args: argparse.Namespace, config: dict) -> tuple[str, dict] | None:
    if args.command == "probe":
        return None
    if args.command == "capture":
        return "Screenshot", {}
    if args.command == "snapshot":
        return "Snapshot", {"use_vision": False}
    if args.command == "launch":
        executable = config.get("app_executable", "")
        if not isinstance(executable, str):
            raise ClientError("MCP_VNCODE_EXECUTABLE_REQUIRED")
        return "App", {"mode": "launch_executable", "executable": validate_app(executable), "args": []}
    if args.name not in ALLOWED_CALLS:
        raise ClientError("MCP_TOOL_NOT_ALLOWED")
    try:
        arguments = json.loads(Path(args.arguments_file).read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        raise ClientError("MCP_ARGUMENTS_INVALID") from None
    if not isinstance(arguments, dict):
        raise ClientError("MCP_ARGUMENTS_INVALID")
    return args.name, arguments


def save_result(result, output_dir: Path, *, require_image: bool) -> dict:
    if getattr(result, "is_error", False):
        raise ClientError("MCP_TOOL_FAILED")
    content = getattr(result, "content", None)
    if not isinstance(content, list):
        raise ClientError("MCP_TOOL_RESULT_INVALID")
    texts = [block.text for block in content if isinstance(block, types.TextContent)]
    images = [block for block in content if isinstance(block, types.ImageContent)]
    # Upstream can return a text error with isError=false. A screenshot needs actual image bytes.
    if require_image and not images:
        raise ClientError("MCP_SCREENSHOT_MISSING")
    decoded = []
    if len(images) > 8:
        raise ClientError("MCP_IMAGE_INVALID")
    for block in images:
        mime = block.mime_type
        if mime not in {"image/png", "image/jpeg"} or len(block.data) > MAX_IMAGE * 4 // 3 + 4:
            raise ClientError("MCP_IMAGE_INVALID")
        try:
            data = base64.b64decode(block.data, validate=True)
        except (ValueError, TypeError):
            raise ClientError("MCP_IMAGE_INVALID") from None
        if not data or len(data) > MAX_IMAGE:
            raise ClientError("MCP_IMAGE_INVALID")
        if not (data.startswith(b"\x89PNG\r\n\x1a\n") if mime == "image/png" else data.startswith(b"\xff\xd8\xff")):
            raise ClientError("MCP_IMAGE_INVALID")
        decoded.append((data, ".png" if mime == "image/png" else ".jpg"))
    token = os.environ.get("VNCODE_WINDOWS_MCP_TOKEN", "")
    redact = lambda value: value.replace(token, "[REDACTED]") if token else value
    if output_dir.is_symlink():
        raise ClientError("MCP_OUTPUT_DIRECTORY_NOT_PRIVATE")
    output_dir.mkdir(parents=True, mode=0o700, exist_ok=True)
    if output_dir.stat().st_mode & 0o077:
        raise ClientError("MCP_OUTPUT_DIRECTORY_NOT_PRIVATE")
    run = output_dir / uuid.uuid4().hex
    run.mkdir(mode=0o700, exist_ok=False)
    paths = []
    for index, (data, suffix) in enumerate(decoded):
        path = run / (str(index) + suffix)
        with path.open("xb") as stream:
            os.chmod(path, 0o600)
            stream.write(data)
        paths.append({"path": str(path.resolve()), "sha256": hashlib.sha256(data).hexdigest()})
    text_path = run / "text.txt"
    with text_path.open("x", encoding="utf-8") as stream:
        os.chmod(text_path, 0o600)
        stream.write(redact("\n".join(texts)))
    return {"images": paths, "text_path": str(text_path.resolve())}


async def execute(url: str, token: str, request, output_dir: Path, timeout: float) -> dict:
    # Keep the managed environment's proxy, CA trust and credential routing intact.
    async with httpx2.AsyncClient(headers={"Authorization": "Bearer " + token},
                                  timeout=timeout, trust_env=True, follow_redirects=False) as http:
        async with streamable_http_client(url, http_client=http, max_sse_event_size=MAX_IMAGE * 2) as streams:
            async with ClientSession(*streams, read_timeout_seconds=timeout) as session:
                initialized = await session.initialize()
                tools = []
                cursor = None
                seen_cursors = set()
                for _ in range(20):
                    page = await session.list_tools(params=types.PaginatedRequestParams(cursor=cursor) if cursor else None)
                    tools.extend(page.tools)
                    cursor = page.next_cursor
                    if not cursor:
                        break
                    if cursor in seen_cursors:
                        raise ClientError("MCP_TOOL_PAGINATION_INVALID")
                    seen_cursors.add(cursor)
                else:
                    raise ClientError("MCP_TOOL_PAGINATION_LIMIT")
                names = sorted({tool.name for tool in tools})
                if request is None:
                    return {"status": "MCP_PROTOCOL_READY", "desktop_verified": False,
                            "server": initialized.server_info.model_dump(), "protocol": initialized.protocol_version,
                            "tools": names, "tool_schemas": {tool.name: tool.input_schema for tool in tools
                                if tool.name in ALLOWED_CALLS | {"App", "Screenshot", "Snapshot"}}}
                name, arguments = request
                if name not in names:
                    raise ClientError("MCP_TOOL_UNAVAILABLE")
                result = await session.call_tool(name, arguments, read_timeout_seconds=timeout)
                files = save_result(result, output_dir, require_image=name == "Screenshot")
                return {"status": "MCP_TOOL_RETURNED", "tool": name, **files,
                        "desktop_verified": False,
                        "note": "Inspect the saved image/text to verify the expected VN Code window and behavior."}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config")
    parser.add_argument("--output-dir", default="/tmp/vncode-windows-mcp-captures")
    parser.add_argument("--timeout", type=float, default=30)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("probe", "capture", "snapshot", "launch"):
        commands.add_parser(name)
    call = commands.add_parser("call")
    call.add_argument("name")
    call.add_argument("--arguments-file", required=True)
    args = parser.parse_args(argv)
    logging.disable(logging.CRITICAL)  # Do not print SDK HTTP URLs, headers, or server-reflected errors.
    try:
        config = load_config(args.config)
        request = request_for(args, config)  # Reject disallowed operations before any network access.
        value = os.environ.get("VNCODE_WINDOWS_MCP_URL") or config.get("url", "")
        if not isinstance(value, str) or not value:
            raise ClientError("MCP_NOT_CONFIGURED")
        url = validate_url(value)
        token = os.environ.get("VNCODE_WINDOWS_MCP_TOKEN", "")
        if not token or any(c.isspace() for c in token):
            raise ClientError("MCP_TOKEN_REQUIRED")
        if not math.isfinite(args.timeout) or not 0 < args.timeout <= 120:
            raise ClientError("MCP_TIMEOUT_INVALID")
        async def bounded():
            async with asyncio.timeout(args.timeout):
                return await execute(url, token, request, Path(args.output_dir), args.timeout)
        output = asyncio.run(bounded())
        def redact_output(value):
            if isinstance(value, str):
                return value.replace(token, "[REDACTED]")
            if isinstance(value, dict):
                return {redact_output(k): redact_output(v) for k, v in value.items()}
            if isinstance(value, list):
                return [redact_output(item) for item in value]
            return value
        print(json.dumps(redact_output(output), ensure_ascii=False))
        return 0
    except ClientError as error:
        print(json.dumps({"status": "ERROR", "code": str(error)}))
    except TimeoutError:
        print(json.dumps({"status": "ERROR", "code": "MCP_TIMEOUT"}))
    except Exception as failure:
        # Exceptions may contain server content or secrets; use fixed diagnostics on stdout.
        def known_code(error):
            if isinstance(error, ClientError):
                return str(error)
            if isinstance(error, BaseExceptionGroup):
                for nested in error.exceptions:
                    found = known_code(nested)
                    if found:
                        return found
            return None
        print(json.dumps({"status": "ERROR", "code": known_code(failure) or "MCP_CONNECTION_OR_PROTOCOL_FAILED"}))
    return 1


if __name__ == "__main__":
    sys.exit(main())
