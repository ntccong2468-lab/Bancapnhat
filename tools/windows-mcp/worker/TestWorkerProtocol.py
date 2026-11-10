"""Local worker acceptance only. Never calls App, Click, Type, or seller APIs."""
from __future__ import annotations

import argparse
import base64
import io
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request

URL = "http://127.0.0.1:18765/mcp/"
# The worker test is deliberately fixed to this computer's loopback interface.
# Do not let Windows system proxy discovery forward its bearer to a remote proxy.
LOCAL_HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
EXPECTED_TOOLS = set("App,Snapshot,Screenshot,Click,Type,Scroll,Move,Shortcut,Wait,WaitFor,ControlStatus".split(","))


class ProtocolFailure(Exception):
    pass


def check(condition: bool, code: str) -> None:
    if not condition:
        raise ProtocolFailure(code)


def request(payload: dict, token: str | None, session: str | None = None, protocol: str | None = None):
    headers = {"Accept": "application/json, text/event-stream", "Content-Type": "application/json"}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    if session:
        headers["Mcp-Session-Id"] = session
    if protocol:
        headers["MCP-Protocol-Version"] = protocol
    req = urllib.request.Request(URL, data=json.dumps(payload).encode(), headers=headers, method="POST")
    with LOCAL_HTTP.open(req, timeout=30) as response:
        sid = response.headers.get("Mcp-Session-Id")
        if response.status in (202, 204):
            return response.status, sid, None
        if "text/event-stream" in response.headers.get("Content-Type", ""):
            size = 0
            data = []
            for line in response:
                size += len(line)
                check(size <= 32 * 1024 * 1024, "MCP_RESPONSE_TOO_LARGE")
                decoded = line.decode("utf-8").rstrip("\r\n")
                if decoded.startswith("data:"):
                    data.append(decoded[5:].lstrip())
                elif not decoded and data:
                    item = json.loads("\n".join(data))
                    if item.get("id") == payload.get("id"):
                        return response.status, sid, item
                    data = []
            raise ProtocolFailure("MCP_SSE_REPLY_MISSING")
        raw = response.read(32 * 1024 * 1024 + 1)
        check(len(raw) <= 32 * 1024 * 1024, "MCP_RESPONSE_TOO_LARGE")
        return response.status, sid, json.loads(raw)


def rpc(identifier: int, method: str, params: dict | None = None) -> dict:
    result = {"jsonrpc": "2.0", "id": identifier, "method": method}
    if params is not None:
        result["params"] = params
    return result


def validate(require_image: bool, report: dict) -> None:
    token = os.environ.get("WINDOWS_MCP_AUTH_KEY", "")
    check(bool(token), "MCP_TEST_TOKEN_MISSING")
    initialize = rpc(1, "initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "vncode-worker-validation", "version": "1.0"}})
    deadline = time.monotonic() + 90
    while True:
        try:
            request(initialize, None)
            raise ProtocolFailure("MCP_ACCEPTED_UNAUTHENTICATED_REQUEST")
        except urllib.error.HTTPError as error:
            check(error.code == 401, "MCP_UNAUTHENTICATED_STATUS_NOT_401")
            break
        except urllib.error.URLError:
            check(time.monotonic() < deadline, "MCP_WORKER_STARTUP_TIMEOUT")
            time.sleep(0.25)
    report["missingTokenRejected"] = True
    try:
        request(initialize, "incorrect-local-validation-token")
        raise ProtocolFailure("MCP_ACCEPTED_INCORRECT_TOKEN")
    except urllib.error.HTTPError as error:
        check(error.code == 401, "MCP_INCORRECT_TOKEN_STATUS_NOT_401")
    report["incorrectTokenRejected"] = True
    _, session, message = request(initialize, token)
    check(isinstance(message, dict) and "result" in message and "error" not in message, "MCP_INITIALIZE_FAILED")
    server = message["result"].get("serverInfo", {})
    check(server.get("name") == "windows-mcp", "MCP_WRONG_SERVER")
    report["server"] = server
    protocol = message["result"]["protocolVersion"]
    report["protocolVersion"] = protocol
    request({"jsonrpc": "2.0", "method": "notifications/initialized"}, token, session, protocol)
    _, _, message = request(rpc(2, "tools/list"), token, session, protocol)
    check(isinstance(message, dict) and "result" in message, "MCP_TOOLS_LIST_FAILED")
    names = {tool["name"] for tool in message["result"].get("tools", [])}
    check(names == EXPECTED_TOOLS, "MCP_UI_TOOL_ALLOWLIST_MISMATCH")
    report["tools"] = sorted(names)
    _, _, message = request(rpc(3, "tools/call", {"name": "ControlStatus", "arguments": {}}), token, session, protocol)
    check(isinstance(message, dict) and "result" in message and not message["result"].get("isError"), "MCP_CONTROL_STATUS_FAILED")
    result = message["result"]
    control = result.get("structuredContent")
    if not isinstance(control, dict):
        control = next((json.loads(c["text"]) for c in result.get("content", []) if c.get("type") == "text"), {})
    check(control.get("enabled") is True and control.get("policy") == "balanced", "MCP_DESKTOP_CONTROL_GUARD_CHANGED")
    report["controlEnabled"] = True
    report["controlPolicy"] = "balanced"
    report["desktopImageChecked"] = False
    if require_image:
        _, _, message = request(rpc(4, "tools/call", {"name": "Screenshot", "arguments": {"use_annotation": False}}), token, session, protocol)
        check(isinstance(message, dict) and "result" in message, "MCP_SCREENSHOT_CALL_FAILED")
        result = message["result"]
        images = [c for c in result.get("content", []) if c.get("type") == "image"]
        check(not result.get("isError") and bool(images), "MCP_SCREENSHOT_IMAGE_MISSING")
        # Upstream can return an ordinary text error instead of MCP isError: require decodable pixels.
        from PIL import Image
        with Image.open(io.BytesIO(base64.b64decode(images[0]["data"], validate=True))) as image:
            width, height = image.size
            image.verify()
        check(width > 0 and height > 0, "MCP_SCREENSHOT_DIMENSIONS_INVALID")
        report["desktopImageChecked"] = True
        report["screenshot"] = {"width": width, "height": height, "mimeType": images[0]["mimeType"]}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--require-desktop-image", action="store_true")
    arguments = parser.parse_args()
    report = {"status": "FAIL", "scope": "Pinned Windows worker installation and local MCP auth/protocol, synthetic CI desktop only. No VN Code/seller acceptance, no app launch, no seller API writes."}
    try:
        validate(arguments.require_desktop_image, report)
        report["status"] = "PASS"
    except ProtocolFailure as error:
        report["errorCode"] = str(error)
    except Exception as error:
        # Do not print request objects, headers, the token, or arbitrary remote response content.
        report["errorCode"] = "MCP_PROTOCOL_TEST_" + type(error).__name__.upper()
    arguments.report.parent.mkdir(parents=True, exist_ok=True)
    arguments.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
