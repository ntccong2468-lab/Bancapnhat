# Windows-MCP cho Codex Web — cài client và chuẩn bị worker Windows

10.11.2026 MSK. Người dùng đồng ý cài phương án Windows-MCP và chọn máy Windows đang cài VN Code. Phiên hiện tại là Linux cloud, chưa có endpoint Windows, remote session hoặc token MCP của máy người dùng.

## Đã làm

- Clone/review upstream tại `/workspace/vendor/Windows-MCP`, pin `59e3b194799d5a1ced14298bca4cd200f7edbfd8`/0.8.8, MIT. Không thử import/chạy Windows server trên Linux hoặc coi clone là đã điều khiển được Windows.
- Cài MCP SDK2.3.0 và 28 dependency đã khóa hash vào `/workspace/windows-mcp-runtime/.venv`. Shell client, scripts cài/chạy và skill hướng dẫn nằm trong `tools/windows-mcp` và `.agents/skills/windows-mcp-control`. Không thêm dependency vào Maven/runtime VN Code hoặc thay bộ cài app.
- Client dùng proxy/CA hiện hành, HTTPS cho remote, token qua biến Secrets, giới hạn thao tác UI và launch đúng file VN Code cấu hình. Kiểm timeout, tool errors, pagination, che token trong metadata/text và giữ ảnh riêng. Image content/magic bytes không thay việc xem ảnh/đối chiếu app thực.
- Worker tự tải nguồn và uv theo SHA256, Python3.14, `uv sync --frozen --no-dev`; không cần Git/admin. DPAPI CurrentUser/ACL, giữ token khi cài lại; chỉ loopback18765, tắt telemetry, giữ consent/takeover. Xóa/khôi phục override env Windows-MCP để cấu hình cũ không thay profile. Smoke loopback riêng không gửi bearer qua system proxy.
- Có ZIP gồm script và launcher `.cmd`, không chứa token/chứng thư/source archive/vendor runtime/seller data; xem `tools/windows-mcp/windows-setup.manifest.json`.

## Kiểm chứng

Linux **16/16 ca đạt, 0 failure/error/skip**. Giao thức Streamable HTTP/MCP SDK thực với server fixture tổng hợp có auth; không phải Windows hoặc VN Code thật. Bao gồm token sai/thiếu, init/listtools, capture bắt buộc ảnh, text lỗi với isErrorfalse, timeout, schema/args, launch không chạy EXE khác, privacy và che token. Shell syntax/install idempotent và Python compile đạt. Lệnh:

```sh
tools/windows-mcp/install-client.sh
/workspace/windows-mcp-runtime/.venv/bin/python -m unittest discover -s tools/windows-mcp -p test_client.py -v
bash -n tools/windows-mcp/install-client.sh tools/windows-mcp/run.sh
git diff --check
```

Native Windows CI đang chờ chạy khi push nhánh `tools/windows-mcp-20261110`. Chưa dùng kết quả CI như nghiệm thu máy seller. Chi tiết cuối/run/source/biên nhận sẽ bổ sung sau khi có kết quả.

## Bước còn thiếu để thao tác máy người dùng

Cần chạy bộ cài trên Windows đang đăng nhập, khởi động worker và có đường HTTPS/tunnel/VPN từ Codex tới endpoint `/mcp/`. Cấu hình `VNCODE_WINDOWS_MCP_URL` và Secret `VNCODE_WINDOWS_MCP_TOKEN` đúng hostname, không gửi token qua chat. Bộ này không tự mở firewall hoặc công khai desktop. Sau khi kết nối, phải capture/xem ảnh và xác nhận đúng VN Code trước khi kiểm tính năng thật; HTTP200/probe/tools/PID không đủ.

Chưa mở hoặc thử VN Code thật trên Windows người dùng, chưa seller API write, mua KIZ, ký, kích hoạt license, gửi Gmail hoặc phát hành app. Latest1.6.8 và hotfix ngày hết hạn nguồn80e9324 được giữ riêng; cài công cụ không phát hành hotfix đó. [Cách dùng](../../tools/windows-mcp/README.md).
