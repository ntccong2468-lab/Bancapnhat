# Windows-MCP cho VN Code và Codex Web

Tích hợp gồm **client trong Codex cloud** và **worker trên máy Windows đang cài VN Code**. Hai máy khác nhau; `localhost` trong Codex không trỏ về máy Windows của người dùng.

Nguồn worker: [CursorTouch/Windows-MCP](https://github.com/CursorTouch/Windows-MCP), MIT, commit `59e3b194799d5a1ced14298bca4cd200f7edbfd8` (0.8.8). Mã upstream được tải riêng, không đưa vào runtime hoặc bộ cài VN Code. Python MCP client SDK pin 2.3.0 và toàn bộ dependency có hash trong `requirements.lock`.

## Cài phía Windows

Yêu cầu Windows 10/11 x64, phiên desktop đang đăng nhập, có mạng tải GitHub/PyPI/Python. Worker chạy bằng tài khoản hiện tại, không cần quyền quản trị. Bộ cài tự tải uv và Python 3.14 cùng dependency đã khóa; không cần Git hoặc Python có sẵn.

1. Tải và giải nén [windows-setup.zip](windows-setup.zip).
2. Chạy `Install-WindowsMCP.cmd`. Giữ cửa sổ mở nếu có lỗi để xem mã lỗi. Tệp cùng thư mục phải được giữ đủ, không chạy riêng một PS1 đã tách ra.
3. Chạy `Start-WindowsMCP.cmd` và giữ cửa sổ đó mở. Dừng bằng Ctrl+C.

Tương đương khi dùng source:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Install-WindowsMCP.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Start-WindowsMCP.ps1
```

Cài vào `%LOCALAPPDATA%\VNCode\WindowsMCP`, không sửa dữ liệu app/cửa hàng, không thêm service, tác vụ tự khởi động hoặc firewall. ZIP nguồn và uv được kiểm SHA-256; source giữ manifest để phát hiện thay đổi. Token ngẫu nhiên lưu bằng DPAPI CurrentUser trong thư mục chỉ user và SYSTEM có quyền. Chạy cài lại giữ token cũ. Telemetry tắt; desktop control/nhường quyền cho người dùng bật. Chỉ mở các công cụ UI cùng `ControlStatus`; App vẫn có thể mở EXE nên đây không phải sandbox hệ điều hành.

Worker chỉ nghe **`http://127.0.0.1:18765/mcp/`**, có bearer authentication. Địa chỉ này chưa đủ để Codex cloud kết nối. Cần đường HTTPS/tunnel/VPN từ Codex tới máy Windows; bộ này không tự công khai desktop ra Internet. Tunnel phải giữ `Authorization`, hỗ trợ MCP Streamable HTTP và đổi Host gửi về origin thành `127.0.0.1`/`localhost` để qua kiểm tra Host của upstream. Không tắt TLS hoặc dùng cờ bỏ xác thực remote.

## Kết nối từ Codex

Khi đã có địa chỉ HTTPS thực:

- Cấu hình biến `VNCODE_WINDOWS_MCP_URL` bằng endpoint `/mcp/` thực.
- Chạy `Export-ConnectionToken.cmd` trên Windows để chủ động sao chép token; dán vào **Secrets của môi trường Codex** tên `VNCODE_WINDOWS_MCP_TOKEN`, giới hạn đích đúng hostname HTTPS. Không gửi token trong chat. Xóa clipboard sau khi dán.
- Copy `connection.example.json` thành `connection.local.json` (đã gitignore) và điền `app_executable` đúng file app đang cài. Không chọn file bộ cài. Helper launch chỉ nhận `VN code.exe`/`VNcode.exe` trên ổ đĩa Windows cục bộ, không truyền thêm đối số.

```sh
tools/windows-mcp/install-client.sh
tools/windows-mcp/run.sh --config tools/windows-mcp/connection.local.json probe
tools/windows-mcp/run.sh --config tools/windows-mcp/connection.local.json capture
tools/windows-mcp/run.sh --config tools/windows-mcp/connection.local.json snapshot
tools/windows-mcp/run.sh --config tools/windows-mcp/connection.local.json launch
```

Client dùng proxy và CA của môi trường, không có tùy chọn bỏ kiểm chứng TLS. `probe` trả metadata/tool schema; `MCP_PROTOCOL_READY` chưa chứng minh desktop/VN Code hoạt động. `capture` phải có image content, không coi text lỗi là ảnh. Ảnh/text lưu riêng dưới `/tmp/vncode-windows-mcp-captures` hoặc `--output-dir`, không in base64/token. Mở ảnh trả về bằng `view_image`, xác nhận đúng app/cửa hàng rồi mới thao tác. Ảnh có thể chứa dữ liệu riêng, không commit hoặc đưa vào artifact phát hành.

Để bấm/nhập: tạo JSON đúng schema lấy từ probe rồi chạy `call Click --arguments-file /tmp/click.json`. Helper chỉ nhận các công cụ UI/wait/control; không nhận PowerShell/Registry/FileSystem hoặc App tùy ý. Chuột/bàn phím vẫn có thể thao tác ngoài app, nên cần tuân thủ phạm vi người dùng giao. Phiên test UI không tự cấp quyền mua KIZ, ký, kích hoạt license hoặc ghi dữ liệu seller.

## Kiểm chứng và trạng thái

```sh
/workspace/windows-mcp-runtime/.venv/bin/python -m unittest discover -s tools/windows-mcp -p test_client.py -v
bash -n tools/windows-mcp/install-client.sh tools/windows-mcp/run.sh
```

Linux: 16 ca client đạt với MCP HTTP thực và **server fixture tổng hợp**, không phải desktop Windows. Workflow `.github/workflows/windows-mcp-validation.yml` cài worker trên Windows CI, kiểm parser PowerShell, cài lại không đổi token, kiểm 401 không token/sai token, initialize/tools/ControlStatus đúng hạn chế. `capture_desktop` tùy chọn còn buộc có ảnh giải mã được; đó vẫn là desktop runner, không phải VN Code máy seller. Báo cáo chỉ chứa metadata tổng hợp, không có token/ảnh/log riêng.

Trạng thái đã cài client, kiểm Windows CI và kết nối máy người dùng được ghi riêng trong `docs/validation/2026-11-10-windows-mcp-setup.md`. Không tự phát hành VN Code khi cài công cụ này. Hướng dẫn cho AI: [windows-mcp-control](../../.agents/skills/windows-mcp-control/SKILL.md).
