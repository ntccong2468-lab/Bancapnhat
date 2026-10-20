# Tải VNCode 1.4.3 và Portal 0.3.4

Đây là bộ cài kiểm thử license/cập nhật, chưa phải Release chính thức. Website
thật vẫn chạy Portal 0.3.2 và kích hoạt vẫn bị chặn bởi `SIGNING_NOT_CONFIGURED`.
Cài app không tự cập nhật Worker hoặc sửa Secret trên Cloudflare.

## Portal — triển khai trước

[Tải artifact Portal 0.3.4](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38560360144/artifacts/11890734415).
Đăng nhập GitHub nếu cần. Trong artifact có ZIP gốc
`VNCode-Portal-0.3.4-License-Diagnostics.zip` và checksum. ZIP chứa mã nguồn,
assets và `HUONG-DAN-CAP-NHAT-034.md`; không chứa private key hay node_modules.

SHA-256 của ZIP đã được đối chiếu sau khi tải về:

`0296ffaf22303cdf860c7eea7e3bdaa04bffc1125e3b75a7321a089cdd0875f2`

Deploy lên đúng Worker/DB rồi bấm **Kiểm tra cấu hình license** ở admin.
Giữ Secret hiện có cho lần kiểm tra đầu tiên. Nếu Secret không dùng được và
không còn bản sao khóa riêng, xem hướng dẫn xoay cặp khóa/ID/pin đồng bộ trong
[hướng dẫn Portal](../../website/license-portal/HUONG-DAN-CAP-NHAT-034.md).

## Bộ cài Windows

[Tải VNCode 1.4.3 kiểm thử](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38560360144/artifacts/11892310257).
Giải nén artifact, mở thư mục `out`, đóng VNCode và chạy
`VN-code-1.4.3-License-Diagnostics-Windows-x64.exe`.

SHA-256 của EXE đã được đối chiếu sau khi tải về:

`c64ab113da8697b33cf9f6e41f362d3424d1476116d9448d3d578cee364fc0a0`

Kiểm tra trên PowerShell ở thư mục có EXE:

```powershell
Get-FileHash .\VN-code-1.4.3-License-Diagnostics-Windows-x64.exe -Algorithm SHA256
```

Build [38560360144](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38560360144)
đã đạt tại source `f070581bafd1af06b7d31af7e2aa0b40c45543a9`: 86 kiểm thử Java
Windows, 54 Portal và 48 Node contracts, không lỗi/skip. Native launcher,
migration 3→5, giữ lịch sử/snapshot và DPAPI đã đạt; XML và JSON kết quả nằm trong
artifact. Chưa chạy toàn bộ workspace/installer-upgrade matrix cho 1.4.3.

App thêm thông báo bản mới, notes và nút tải/cập nhật, nhưng repo hiện chưa có
stable public Release. Artifact này không được đưa vào luồng tự cập nhật của
seller. Chưa xác nhận ký quyền và kích hoạt production thành công.

[Bảng đối chiếu Wbcode và sơ đồ](../research/vncode-wbcode-2026-10-14/LICENSE-UPDATE-143.md).
