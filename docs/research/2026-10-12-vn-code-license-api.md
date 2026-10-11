# VN code — đối chiếu website quản lý quyền sử dụng

Ngày nghiên cứu: 12/10/2026. Chỉ đọc tài liệu, JavaScript công khai và API không đăng nhập; không tạo tài khoản, cấp mã, nhập mã, thanh toán hoặc thay đổi quyền trên website.

## Nguồn và mức xác minh

- Người sở hữu cung cấp `https://vncode.id.vn/admin` và tệp `README.md`, tiêu đề “VNCode License Center — bản cập nhật 0.2.0 (2026-10-12)”. Tệp là tài liệu, chưa chứa mã Worker, schema D1 hoặc kiểm thử backend.
- Trang web và JavaScript công khai cho thấy mã `VNC-…` được cấp theo tài khoản, thời lượng tính bằng ngày, kích hoạt trên dashboard; admin có thao tác gia hạn, hủy mã chưa dùng và thu hồi quyền. Đây là quan sát giao diện, chưa chứng minh giao dịch hoặc cách lưu mã trong backend.
- GET không xác thực `/api/health` trả HTTP 200, `{ "ok": true, "service": "VNCode Accounts" }`.
- GET không xác thực `/api/client/me` và `/api/license` trả HTTP 401 với lỗi “Bạn chưa đăng nhập”. Kiểm tra này chứng minh đường dẫn trả lời và yêu cầu xác thực, chưa chứng minh token hợp lệ, thu hồi hay ngày hết hạn.
- README mô tả Cloudflare Workers + D1, phiên cookie cho web và Bearer token cho Windows. Một số đoạn nói bộ mã chưa deploy, trong khi website đang hoạt động; không suy phiên bản backend đang chạy từ tiêu đề README.

## Hợp đồng Windows theo tài liệu

| Thao tác | Request | Response cần dùng |
|---|---|---|
| Đăng nhập | `POST /api/client/login`, JSON `{email,password}` | `access_token` và `user`; token có thời hạn 7 ngày theo README |
| Kiểm tra quyền | `GET /api/client/me`, `Authorization: Bearer …` | `user.active` là boolean; `status` và `expires_at` |
| Nhập mã, gia hạn | Khách mở dashboard của website | Website quản lý quyền; ứng dụng kiểm tra lại `/api/client/me` |

Frontend web dùng `expires_at` theo Unix **giây**, không phải mili giây. Cần xác nhận response desktop có cùng quy ước trước nghiệm thu. Bảy ngày là hạn phiên đăng nhập, không phải thời hạn quyền sử dụng; app không tự cộng bảy ngày cho license.

API desktop được README mô tả là không dùng CAPTCHA web, có rate limit D1. Chưa có mã server để xác minh rate limit, CSRF cho cookie, scope token, hashing mã, giao dịch redeem/gia hạn, xóa phiên khi thu hồi và giới hạn thiết bị.

## Kết luận tích hợp

Dùng API desktop hiện có là thay đổi ít nhất cho cùng ứng dụng VN code. Token lưu bằng DPAPI của tài khoản Windows, mật khẩu chỉ dùng lúc đăng nhập. Quyền lấy từ server, không tự tạo bằng dữ liệu cache hoặc role hiển thị trên UI. Kiểm tra chạy nền và dùng chung giữa các shop; không tạo mười phiên hoặc mười timer cho mười shop.

API được mô tả chưa cung cấp lease ký số, đăng ký thiết bị, số máy hoặc hạn mức shop. Không công bố hỗ trợ offline có quyền ghi, khóa theo IP hoặc giới hạn thiết bị chỉ từ hợp đồng này. Muốn bổ sung các khả năng đó phải kiểm tra và cập nhật backend riêng.

Tệp nguồn cần để rà soát backend gồm Worker `src/index.js` (và module liên quan), `schema.sql`, migration, `package.json`/lockfile và test. Không cần mật khẩu admin, Bearer token đang dùng, Cloudflare token hoặc private key trong tài liệu/repository.
