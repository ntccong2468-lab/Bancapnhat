"""Black-box CLI/real MCP transport tests against a synthetic localhost fixture.

These tests do not establish Windows installation, desktop access or VN Code acceptance.
"""
from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import socket
import stat
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request

from fixture_server import PNG_BASE64

HERE = Path(__file__).resolve().parent
CLIENT = HERE / "client.py"
TOKEN = "synthetic-worker-credential-not-a-real-secret"


class ClientProtocolTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="vncode-mcp-protocol-")
        self.root = Path(self.temporary.name)
        self.config = self.root / "public-config.json"
        self.arguments = self.root / "arguments.json"
        self.arguments.write_text("{}", encoding="utf-8")
        self.output = self.root / "captures"
        self.log = self.root / "fixture-calls.jsonl"
        self.process: subprocess.Popen | None = None
        self.server_log = None

    def tearDown(self) -> None:
        if self.process is not None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        if self.server_log is not None:
            self.server_log.close()
        self.temporary.cleanup()

    def start(self, mode: str = "image") -> None:
        with socket.socket() as selected:
            selected.bind(("127.0.0.1", 0))
            port = selected.getsockname()[1]
        self.url = f"http://127.0.0.1:{port}"
        self.config.write_text(json.dumps({"url": self.url + "/mcp", "app_executable": r"C:\Program Files\VN code\VN code.exe"}), encoding="utf-8")
        env = dict(os.environ, FIXTURE_MCP_TOKEN=TOKEN)
        self.server_log = (self.root / "fixture-process.log").open("w", encoding="utf-8")
        self.process = subprocess.Popen([sys.executable, str(HERE / "fixture_server.py"), "--port", str(port), "--log", str(self.log), "--mode", mode], env=env, stdout=self.server_log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                self.fail("Synthetic MCP fixture exited: " + (self.root / "fixture-process.log").read_text())
            try:
                with urllib.request.urlopen(self.url + "/health", timeout=0.3) as response:
                    if response.status == 200:
                        return
            except (urllib.error.URLError, TimeoutError):
                time.sleep(0.05)
        self.fail("Synthetic MCP fixture did not become ready")

    def events(self, kind: str | None = None) -> list[dict]:
        entries = [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []
        return [entry for entry in entries if kind is None or entry["kind"] == kind]

    def run_client(self, *arguments: str, token: str | None = TOKEN, timeout: str = "5") -> subprocess.CompletedProcess:
        env = dict(os.environ)
        env.pop("VNCODE_WINDOWS_MCP_URL", None)
        env.pop("VNCODE_WINDOWS_MCP_APP_PATH", None)
        if token is None:
            env.pop("VNCODE_WINDOWS_MCP_TOKEN", None)
        else:
            env["VNCODE_WINDOWS_MCP_TOKEN"] = token
        # Do not let the managed runner's restrictive umask hide privacy defects.
        process_options = {"umask": 0o022} if os.name != "nt" else {}
        return subprocess.run([sys.executable, str(CLIENT), "--config", str(self.config), "--output-dir", str(self.output), "--timeout", timeout, *arguments], env=env, capture_output=True, text=True, timeout=15, **process_options)

    def assert_success(self, result: subprocess.CompletedProcess) -> None:
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn(TOKEN, result.stdout + result.stderr)

    def assert_failure(self, result: subprocess.CompletedProcess, *sensitive: str) -> None:
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        for value in (TOKEN, *sensitive):
            self.assertNotIn(value, result.stdout + result.stderr)

    def test_probe_negotiates_mcp_and_lists_tools_without_calling_desktop_tools(self) -> None:
        self.start()
        result = self.run_client("probe")
        self.assert_success(result)
        self.assertIn("Screenshot", result.stdout)
        self.assertTrue(self.events("request"), "Protocol requests must actually reach the fixture")
        self.assertEqual([], self.events("tool"), "Protocol availability alone must not perform desktop actions")

    def test_missing_bearer_is_rejected_before_network(self) -> None:
        self.start()
        result = self.run_client("probe", token=None)
        self.assert_failure(result)
        self.assertEqual([], self.events("request"))

    def test_probe_redacts_credential_even_when_reflected_in_server_metadata(self) -> None:
        self.start("echo-server")
        result = self.run_client("probe")
        self.assert_success(result)
        self.assertEqual([], self.events("tool"))

    def test_wrong_bearer_cannot_negotiate_or_leak_credential(self) -> None:
        self.start()
        wrong = "synthetic-rejected-credential"
        self.assert_failure(self.run_client("probe", token=wrong), wrong)
        self.assertTrue(self.events("request"))
        self.assertFalse(any(e["authorized"] for e in self.events("request")))
        self.assertEqual([], self.events("tool"))

    def test_unsafe_urls_fail_before_connection_and_do_not_print_embedded_credentials(self) -> None:
        self.start()
        for url in ("http://example.com/mcp", "ftp://127.0.0.1/mcp", "http://operator:embedded-secret@127.0.0.1/mcp", self.url + "/mcp?token=embedded-secret", self.url + "/mcp#embedded-secret"):
            with self.subTest(url=url):
                self.config.write_text(json.dumps({"url": url}), encoding="utf-8")
                self.assert_failure(self.run_client("probe"), "embedded-secret")
        self.assertEqual([], self.events("request"))

    def test_dangerous_generic_tools_are_blocked_before_network(self) -> None:
        self.start()
        for name in ("App", "PowerShell", "Registry", "FileSystem", "Process", "Scrape"):
            with self.subTest(name=name):
                self.assert_failure(self.run_client("call", name, "--arguments-file", str(self.arguments)))
        self.assertEqual([], self.events("request"))
        self.assertEqual([], self.events("tool"))

    def test_capture_saves_actual_image_privately_without_printing_base64(self) -> None:
        self.start()
        result = self.run_client("capture")
        self.assert_success(result)
        self.assertNotIn(PNG_BASE64, result.stdout + result.stderr)
        images = list(self.output.rglob("*.png"))
        self.assertEqual(1, len(images))
        self.assertEqual(base64.b64decode(PNG_BASE64), images[0].read_bytes())
        if os.name != "nt":
            self.assertEqual(0, stat.S_IMODE(images[0].stat().st_mode) & 0o077)
            self.assertEqual(0, stat.S_IMODE(self.output.stat().st_mode) & 0o077)
        self.assertEqual("Screenshot", self.events("tool")[-1]["name"])

    def test_capture_text_error_is_not_success_even_when_is_error_is_false(self) -> None:
        self.start("text-error")
        self.assert_failure(self.run_client("capture"))
        self.assertEqual([], list(self.output.rglob("*.png")))

    @unittest.skipIf(os.name == "nt", "POSIX output-directory privacy applies to the Codex Linux client")
    def test_capture_refuses_existing_shared_output_directory(self) -> None:
        self.start()
        self.output.mkdir()
        self.output.chmod(0o755)
        self.assert_failure(self.run_client("capture"))
        self.assertEqual([], list(self.output.rglob("*.png")))

    def test_tool_error_flag_is_failure_and_reflected_bearer_is_redacted(self) -> None:
        self.start("echo-token")
        self.assert_failure(self.run_client("capture"))
        self.assertEqual([], list(self.output.rglob("*.png")))

    def test_capture_rejects_malformed_image_data(self) -> None:
        self.start("malformed-image")
        self.assert_failure(self.run_client("capture"))
        self.assertEqual([], list(self.output.rglob("*.png")))

    def test_launch_uses_only_configured_executable_with_separate_empty_arguments(self) -> None:
        self.start()
        self.assert_success(self.run_client("launch"))
        call = next(e for e in self.events("tool") if e["name"] == "App")
        self.assertEqual("launch_executable", call["arguments"]["mode"])
        self.assertEqual(r"C:\Program Files\VN code\VN code.exe", call["arguments"]["executable"])
        self.assertEqual([], call["arguments"]["args"])

    def test_other_executables_unc_and_traversal_cannot_be_launched(self) -> None:
        self.start()
        for executable in (r"C:\Windows\System32\cmd.exe", r"\\host\share\VN code.exe", r"C:\Program Files\..\VN code.exe"):
            with self.subTest(executable=executable):
                self.config.write_text(json.dumps({"url": self.url + "/mcp", "app_executable": executable}), encoding="utf-8")
                self.assert_failure(self.run_client("launch"))
        self.assertEqual([], self.events("request"))

    def test_capture_timeout_is_bounded_and_does_not_save_fake_image(self) -> None:
        self.start("delay")
        began = time.monotonic()
        self.assert_failure(self.run_client("capture", timeout="1"))
        self.assertLess(time.monotonic() - began, 5)
        self.assertEqual([], list(self.output.rglob("*.png")))

    def test_allowed_click_is_sent_with_exact_arguments(self) -> None:
        self.start()
        self.arguments.write_text(json.dumps({"loc": [12, 34]}), encoding="utf-8")
        self.assert_success(self.run_client("call", "Click", "--arguments-file", str(self.arguments)))
        call = next(e for e in self.events("tool") if e["name"] == "Click")
        self.assertEqual({"loc": [12, 34]}, call["arguments"])

    def test_snapshot_returns_saved_ui_text_and_requests_no_image(self) -> None:
        self.start()
        self.assert_success(self.run_client("snapshot"))
        call = next(e for e in self.events("tool") if e["name"] == "Snapshot")
        self.assertEqual({"use_vision": False}, call["arguments"])
        self.assertTrue(any("Synthetic fixture window" in p.read_text() for p in self.output.rglob("*.txt")))
        self.assertEqual([], list(self.output.rglob("*.png")))


if __name__ == "__main__":
    unittest.main(verbosity=2)
