# Windows-MCP cho Codex Web — cài client và chuẩn bị worker Windows

10.11.2026 MSK. Người dùng đồng ý cài phương án Windows-MCP và chọn máy Windows đang cài VN Code. Phiên hiện tại là Linux cloud, chưa có endpoint Windows, remote session hoặc token MCP của máy người dùng.

## Đã làm

- Clone/review upstream tại `/workspace/vendor/Windows-MCP`, pin `59e3b194799d5a1ced14298bca4cd200f7edbfd8`/0.8.8, MIT. Không thử import/chạy Windows server trên Linux hoặc coi clone là đã điều khiển được Windows.
- Cài MCP SDK2.3.0 (tổng 28 gói đã khóa hash) vào `/workspace/windows-mcp-runtime/.venv`. Shell client, scripts cài/chạy và skill hướng dẫn nằm trong `tools/windows-mcp` và `.agents/skills/windows-mcp-control`. Không thêm dependency vào Maven/runtime VN Code hoặc thay bộ cài app.
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

Nguồn công cụ đã push **`def8e8ff0467a4141fc2399ecd68372ebe1b42f9`**, nhánh `tools/windows-mcp-20261110`, kế thừa hotfix ngày hết hạn80e9324 nhưng không phát hành app. **Native Windows run [42883495032](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/42883495032) SUCCESS**, đúng source: parser PowerShell, kiểm byte ZIP/source, cài/rerun DPAPI token giữ nguyên, CLI, thiếu/sai token401, initialize/tools/list và ControlStatus bật/balanced đều đạt. [Receipt Windows](windows-mcp-20261110/native-windows.json), [protocol](windows-mcp-20261110/native-protocol.json), [16 ca client riêng](windows-mcp-20261110/client-tests.json).

Đã giữ bằng chứng hai lượt trước: [run42882348806](windows-mcp-20261110/first-native-failure.json) cài đạt nhưng helper POST `/mcp/` gặp307; sửa URL chuẩn `/mcp` và giữ strict401, từ chối redirect, thêm stage/status. [run42883195117](windows-mcp-20261110/second-native-failure.json) bị chặn ở kiểm byte ZIP do Git Windows đổi LF→CRLF, chưa chạy install/protocol; sửa `.gitattributes` chỉ riêng đường dẫn tools/windows-mcp, không nới hash. Không dùng hai lượt FAIL làm nghiệm thu.

Windows CI không gọi App/Click/Type/Screenshot; `desktopImageChecked=false`. Trường `server.version=4.1.3` là framework FastMCP, không phải version VN Code hay package windows-mcp0.8.8; pin thực lấy từ source/archive manifest. Không coi việc CI cài được và có giao thức là đã kết nối máy người dùng.

ZIP đã [tải lại từ URL public cố định theo commit](windows-mcp-20261110/distribution.json), 12.634 bytes, SHA256 `50e6d7c921fea17bdaa48dc0880f1bd923d4b9d76fc37e504af2b0195382b6dc`, khớp source và manifest. `python3 tools/windows-mcp/build_bundle.py --check` kiểm cả tập file và bytes; không có token/runtime/seller data trong ZIP. Commit tài liệu/skill sau source trên không đổi ZIP, client hoặc worker đã kiểm.

## Bước còn thiếu để thao tác máy người dùng

Cần chạy bộ cài trên Windows đang đăng nhập, khởi động worker và có đường HTTPS/tunnel/VPN từ Codex tới endpoint `/mcp`. Đã gửi ZIP và yêu cầu người dùng xác nhận cài/khởi động; chưa nhận xác nhận tại checkpoint này. Cấu hình `VNCODE_WINDOWS_MCP_URL` và Secret `VNCODE_WINDOWS_MCP_TOKEN` đúng hostname, không gửi token qua chat. Bộ này không tự mở firewall hoặc công khai desktop. Sau khi kết nối, phải capture/xem ảnh và xác nhận đúng VN Code trước khi kiểm tính năng thật; HTTP200/probe/tools/PID không đủ.

Chưa mở hoặc thử VN Code thật trên Windows người dùng, chưa seller API write, mua KIZ, ký, kích hoạt license, gửi Gmail hoặc phát hành app. Latest1.6.8 và hotfix ngày hết hạn nguồn80e9324 được giữ riêng; cài công cụ không phát hành hotfix đó. [Cách dùng](../../tools/windows-mcp/README.md).

## Đường tunnel chính thức có thể dùng ở bước ghép máy

Đã đọc tài liệu, **chưa cài/tạo tunnel**: [OpenAI Secure MCP Tunnels](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels), [openai/tunnel-client](https://github.com/openai/tunnel-client), [onboarding](https://github.com/openai/tunnel-client/blob/master/docs/onboarding.md). Release quan sát v0.0.20 có Windows amd64/arm64. Cách này không cần domain/VPN hoặc inbound port nhưng cần tunnel được tạo trong Platform organization, gắn đúng ChatGPT workspace, runtime OpenAI API key quyền Tunnels Read+Use và người tạo quyền Read+Manage. Không dùng khóa sàn/OMS thay thế hoặc xin key trong chat.

Đường tài liệu hỗ trợ là cấu hình client Windows bằng `init` (stdio hoặc `--mcp-server-url`), kiểm `doctor`, giữ `run`, rồi tạo/cài custom MCP plugin với Connection:Tunnel và cùng tunnel ID. Mở cuộc trò chuyện mới để kiểm tool được phát hiện và gọi thật. Chưa có bằng chứng generic Python MCP SDK của kit truy cập được `/v1/mcp/{tunnel_id}` với cơ chế auth caller đó; không tự thay URL và báo hoạt động. Đây là phương án nối tiếp khi người dùng có quyền/cấu hình cần thiết, không phải trạng thái đã cài hoặc đã kết nối trong phiên này.
