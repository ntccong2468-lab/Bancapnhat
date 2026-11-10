"""Build or verify the Windows-only setup ZIP; never bundle local credentials/runtime."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile

KIT = Path(__file__).resolve().parent
NAMES = ("Export-ConnectionToken.cmd", "Export-ConnectionToken.ps1", "Install-WindowsMCP.cmd",
         "Install-WindowsMCP.ps1", "Start-WindowsMCP.cmd", "Start-WindowsMCP.ps1",
         "Test-WindowsMCP.ps1", "TestWorkerProtocol.py", "WindowsMCP.Common.ps1")
README = """CÀI WINDOWS-MCP CHO VN CODE

1. Giải nén toàn bộ thư mục này trên máy Windows đang cài VN Code.
2. Bấm đúp Install-WindowsMCP.cmd, chờ thông báo cài xong.
3. Bấm đúp Start-WindowsMCP.cmd và giữ cửa sổ đó mở.

Chạy bằng tài khoản Windows bình thường, không cần Run as administrator.
Công cụ chưa tự nối máy Windows với Codex cloud: cần endpoint HTTPS/tunnel/VPN tới worker.
Server cục bộ: http://127.0.0.1:18765/mcp (có xác thực).
Không gửi token trong chat. Khi đã có endpoint, dùng Export-ConnectionToken.cmd rồi dán vào Secrets môi trường Codex tên VNCODE_WINDOWS_MCP_TOKEN, đúng hostname.

Hướng dẫn đầy đủ: tools/windows-mcp/README.md trong repo VN Code.
Nguồn Windows-MCP pin59e3b194799d5a1ced14298bca4cd200f7edbfd8; không sửa dữ liệu app hoặc tạo service/firewall/tunnel.
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    entries = {name: (KIT / "worker" / name).read_bytes() for name in NAMES}
    entries["README-VI.txt"] = README.encode("utf-8-sig")
    archive, manifest_path = KIT / "windows-setup.zip", KIT / "windows-setup.manifest.json"
    digest = lambda data: hashlib.sha256(data).hexdigest()
    if args.check:
        manifest = json.loads(manifest_path.read_text())
        assert manifest["sha256"] == digest(archive.read_bytes()), "ZIP checksum differs"
        assert manifest["files"] == {name: digest(data) for name, data in entries.items()}, "Source manifest differs"
        with zipfile.ZipFile(archive) as z:
            expected = {"VNCode-Windows-MCP-Setup/" + name: data for name, data in entries.items()}
            assert set(z.namelist()) == set(expected), "ZIP file set differs"
            for name, data in expected.items():
                assert z.read(name) == data, "Stale ZIP entry: " + name
        print("Setup ZIP matches all worker sources; no additional files included.")
        return
    content = io.BytesIO()
    with zipfile.ZipFile(content, "w") as z:
        for name, data in entries.items():
            info = zipfile.ZipInfo("VNCode-Windows-MCP-Setup/" + name, date_time=(2026, 11, 10, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            z.writestr(info, data)
    data = content.getvalue()
    archive.write_bytes(data)
    manifest = {"artifact": archive.name, "sha256": digest(data), "bytes": len(data),
                "files": {name: digest(value) for name, value in entries.items()},
                "scope": "Setup scripts only; no token, vendor archive, runtime, seller data or app binary."}
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: manifest[key] for key in ("artifact", "sha256", "bytes")}))


if __name__ == "__main__":
    main()
