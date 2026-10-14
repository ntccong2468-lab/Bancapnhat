# Cập nhật website lên Portal 0.3.2 (Windows)

Bản sửa cần cập nhật cả website và VNCode 1.4.1. Website hiện tại vẫn dùng OTP
cho app cũ; thêm secret không thay được mã Worker.

1. Tải ZIP Portal 0.3.2, giải nén ra một thư mục mới. Cài Node.js 22.13+ hoặc 24
   LTS nếu máy chưa có. Mở PowerShell tại thư mục có package.json/wrangler.jsonc.
2. Kiểm tra tên Worker và D1 trong wrangler.jsonc đúng project đang phục vụ
   vncode.id.vn. Giữ Worker và database hiện tại, không tạo mới.
3. Chạy:

```powershell
npm.cmd ci
npx.cmd wrangler login
npx.cmd wrangler d1 export vncode-accounts --remote --output ..\vncode-before-032.sql
npm.cmd run check
```

Nếu đã dùng Portal 0.3.1, không có migration mới. Nếu database còn ở 0.3.0,
chạy thêm index cũ trước khi deploy:

```powershell
npx.cmd wrangler d1 execute vncode-accounts --remote --file migrations/0003_multi_customer_scaling.sql
```

Deploy mã Worker **và assets trong public/**:

```powershell
npx.cmd wrangler deploy --dry-run
npm.cmd run deploy
```

Giữ nguyên LICENSE_SIGNING_KEY và RESEND_API_KEY ở Worker đang chạy;
LICENSE_KEY_ID vẫn là vncode-1. **Không tạo lại khóa ký**. Khóa công khai app
đang dùng: Gg7F92gqXnCLGdMbI130qHcBJ2hiI6KseZBgWkNCTeI.
Không nhập SQL backup cũ đè database và không gửi secret qua chat.

4. Mở https://vncode.id.vn/admin, nhấn Ctrl+F5. Trong trình duyệt đang đăng nhập,
   mở https://vncode.id.vn/api/admin/desktop/settings: cần thấy
   `registration_mode: "gmail_admin"`, `registration_requires_otp: false`,
   `signing_ready: true`, `key_id: "vncode-1"`.
5. Trong VNCode 1.4.1, nhập Gmail rồi bấm Đăng ký. Trên admin chọn Chờ duyệt,
   Tìm / Làm mới; khách xuất hiện ngay. Chọn số ngày, **Số máy** và Cửa hàng rồi
   Duyệt & cấp khóa. Sao chép khóa và chuyển riêng cho khách. Gửi qua Gmail là tùy chọn.
6. Khách nhập khóa vào mục 2 và Kích hoạt. Máy tiếp theo chỉ cần nhập cùng khóa,
   không cần đăng ký Gmail lại. Đặt Số máy=2 nếu cho phép hai máy, hoặc mức phù hợp
   tối đa 10. Cửa hàng là giới hạn riêng, không phải số máy.

Khóa VNC đã cấp vẫn dùng được. Với khách đã duyệt, chọn bộ lọc Đã duyệt để
Lưu giới hạn; không cần tạo khóa khác chỉ để tăng số máy. Thời hạn tính từ lần
kích hoạt đầu, máy tiếp theo không kéo dài thời hạn. Máy vượt giới hạn cần bạn
nâng Số máy hoặc thu hồi thiết bị cũ trước. Nếu website chưa cập nhật, app sẽ
báo cần Portal 0.3.2, không chuyển sang gửi OTP.

Không có thông tin xác minh hộp thư chỉ từ việc nhập Gmail. Duyệt thủ công và
khóa license đã cấp là điều kiện cấp quyền. Sau deploy, kiểm tra với Gmail/máy
của bạn trước khi chuyển bộ cài cho seller.
