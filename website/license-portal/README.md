# VNCode Portal 0.3.5 — khóa license vncode-2

Bản cập nhật từ Portal 0.3.4, giữ các tài khoản và trang quản trị hiện có.
Website thật đã chạy 0.3.4; chủ website xác nhận Secret cũ không đọc được và D1 sẵn sàng.
0.3.5 chưa deploy lên production.

## Xoay khóa 0.3.5

- Pin chẩn đoán và LICENSE_KEY_ID trong wrangler.jsonc chuyển sang vncode-2.
- Public key: `SPtRxTUJ78fqt5YMJ4DJ_PZwB2cMYegs-J7nY62ADMQ`. Chỉ khóa công khai nằm trong mã nguồn.
- Cần VNCode 1.4.4 trở lên để nhận quyền mới; app vẫn giữ pin vncode-1 cho quyền offline cũ đến hạn.
- Không đổi D1/schema, Gmail, mã VNC, số ngày, quota hoặc thiết bị khi xoay khóa.
- Công cụ `tools/check-signing-key.mjs` xác minh tệp riêng trên máy chủ phần mềm khớp public pin trước khi đổi Secret; không in private key.
- Xem [thứ tự cài app, deploy Portal và đổi Secret](HUONG-DAN-CAP-NHAT-035.md).

## Chẩn đoán license 0.3.4

- Nút **Kiểm tra cấu hình license** ký một thông điệp thử và xác minh bằng public key của VNCode, thay vì chỉ kiểm tra Secret có tồn tại. Chỉ admin truy cập API chẩn đoán.
- Báo riêng khóa không hợp lệ, key ID sai, cặp khóa không khớp và bảng/cột D1 còn thiếu; không trả private key hoặc dữ liệu khách.
- Đọc khóa Ed25519 PKCS8 ở dạng Base64URL, Base64, PEM PRIVATE KEY; xử lý khoảng trắng và chuỗi JSON được đặt trong dấu nháy. Không chấp nhận public key thay cho private key.
- Giữ các chức năng 0.3.3 bên dưới. Xem [hướng dẫn deploy và xử lý mất tệp khóa](HUONG-DAN-CAP-NHAT-034.md).

## Quản trị 0.3.3

- Gmail có vai trò admin cũng nhận được license desktop riêng. Trong mục Người
  dùng, bấm **Cấp license VNCode** ở tài khoản admin để mở đúng Gmail và đặt số
  ngày/máy/cửa hàng. Mã desktop không đổi vai trò hoặc mật khẩu website; quyền
  admin website không tự thay thế khóa, giới hạn máy hoặc thời hạn desktop.
- Nút **Xóa license đã hủy** chỉ hiện cho mã đã hủy. API từ chối xóa mã chưa
  dùng, mã đã kích hoạt hoặc mã có lịch sử đổi license.
- Nút **Xóa Gmail** nằm trong cả danh sách desktop và danh sách người dùng.
  Admin nhập lại chính xác Gmail trước khi xóa tài khoản, khóa, thiết bị và phiên
  liên quan trong một giao dịch. Không cho xóa admin hoặc lịch sử license của
  khách khác. Nhật ký thao tác quản trị được giữ lại.
- Cùng Gmail có thể đăng ký lại; tài khoản mới chờ duyệt, không nhận lại quyền,
  mật khẩu, hạn hoặc mã cũ. Giới hạn đăng ký theo Gmail được đặt lại; giới hạn
  chung theo IP giữ nguyên. Trong app, nhấn **Kiểm tra trạng thái** rồi đăng ký
  lại hoặc **Xóa đăng ký trên máy này** để bỏ phiên cũ trước khi đăng ký.
- Phiên online cũ hết quyền; quyền offline đã ký vẫn còn đến hạn đã cấp.
- Không cần migration mới từ 0.3.2. Xem [hướng dẫn cập nhật Windows](HUONG-DAN-CAP-NHAT-033.md).

## Sửa đăng ký và license 0.3.2

- Khách nhập Gmail, bấm Đăng ký: xuất hiện ngay trên admin để duyệt, không có OTP.
- Không cần dịch vụ gửi mail để đăng ký, duyệt hoặc kích hoạt; gửi khóa là tùy chọn.
- Máy mới có thể nhập khóa trực tiếp; không phải đăng ký Gmail lại trên từng máy.
- Cùng khóa dùng được trên nhiều máy theo giới hạn Số máy admin cấp (1–10).
  Mỗi máy có private key/session riêng; kích hoạt lại không cộng ngày lần nữa.
- Giữ nguyên khóa VNC đã cấp, public key vncode-1, mật khẩu và dữ liệu hiện có.
- Không có migration mới nếu đã ở 0.3.1. App VNCode 1.4.1 dùng API mới;
  client OTP cũ còn được hỗ trợ bằng các route cũ.

## Nâng cấp 0.3.1

- Các máy Windows của khách hoạt động riêng; website chỉ xác thực và cấp quyền
  có chữ ký cho đúng tài khoản/thiết bị. Đơn hàng và KIZ vẫn xử lý trên từng máy.
- Worker chia sẻ một tác vụ import khóa Ed25519 đang chạy trong mỗi isolate.
  Thay secret hoặc key ID tạo tác vụ mới; phản hồi giữ đúng cặp khóa/key ID.
- Mỗi lần cấp quyền đọc tài khoản, chính sách và thiết bị bằng một truy vấn.
  Sau khi ký, kiểm tra lại thu hồi/hạn license/revision trước khi trả quyền.
- Migration `0003_multi_customer_scaling.sql` bổ sung index, không thay dữ liệu.
- Cron mỗi phút dọn tối đa 500 dòng hết hạn của từng bảng token, phiên,
  challenge và giới hạn tần suất. Không xóa khách, thiết bị, license hay audit.
- Kiểm thử 100 và 1.000 khách đồng thời: mỗi khách chỉ nhận quyền 10 cửa hàng
  thuộc đúng tài khoản/thiết bị; không trộn số dư hay quyền giữa các khách.

Client kiểm tra khi cadence 150–180 giây đến hạn, tick mỗi 60 giây nên thời điểm
thực tế làm tròn theo timer. Có jitter/backoff để chừa chỗ cho
thao tác thủ công trong giới hạn server 30 yêu cầu cấp/gia hạn mỗi giờ/mỗi token.
Số cửa hàng thực tế lấy từ chính sách Admin; kiểm thử 10 cửa hàng không đổi
chính sách mặc định 1 cửa hàng của khách hiện tại.

## Luồng sử dụng

1. Khách nhập Gmail trong VNCode rồi bấm Đăng ký.
2. Server lưu khách ở trạng thái Chờ duyệt; app nhận registration token riêng,
   không phải mật khẩu hoặc quyền sử dụng. Khách không cần đăng ký website trước.
3. Admin mở `/admin`, phần **Đăng ký từ VNCode & thiết bị**, lọc/tìm Gmail.
4. Chọn số ngày, máy, cửa hàng, thời gian offline; bấm **Duyệt & cấp khóa**.
   Có thể gửi khóa qua email, hoặc sao chép một lần để chuyển riêng cho khách.
5. Khách nhập khóa trong app. Máy mới có thể nhập khóa trực tiếp, không cần Gmail.
   Server kiểm tra khóa đúng tài khoản, chữ ký thiết bị, giới hạn máy và trạng thái
   duyệt; cấp quyền có chữ ký Ed25519. Nhập Gmail không xác minh hộp thư.
6. App kiểm tra lại quyền; admin có thể gia hạn, khóa tài khoản hoặc thu hồi máy.

Ngày license bắt đầu khi khóa được kích hoạt. Khóa đã đổi không cộng thời gian
lần thứ hai; thử lại sau mất phản hồi không đổi mã hoặc tạo GTIN/license mới.
Đăng ký không tự cấp quyền; nhập khóa không tự phê duyệt khách.

## Những mục mới ở Admin

- Danh sách khách: chờ duyệt/đã duyệt/bị khóa, tìm Gmail, phân trang.
- Duyệt và cấp khóa trong một giao dịch, tùy chọn gửi khóa qua email.
- Chính sách: 1–10 máy, 1–100 cửa hàng, offline 0–168 giờ; mặc định 1/1/72.
- Thiết bị: tên, lần kết nối cuối, hạn quyền offline cuối, thu hồi/giải phóng slot.
- Trạng thái cấu hình email và khóa ký (chỉ có/không, không trả secret).
- Các mục cũ người dùng, mã đã cấp, gia hạn, khóa và thu hồi được giữ.
- Audit ghi tác vụ đăng ký, cấp khóa, thay đổi chính sách và thu hồi.

## Mã nguồn

- `src/index.js`: router và chức năng web cũ; không cho mã tự duyệt khách.
- `src/desktop.js`: đăng ký, proof thiết bị, kích hoạt, gia hạn và Admin API.
- `src/mail.js`: Resend API hoặc adapter EMAIL cũ.
- `public/desktop-admin.js`: phần quản trị mới.
- `migrations/0002_desktop_license.sql`: migration cộng thêm, chạy lại được.
- `migrations/0003_multi_customer_scaling.sql`: index cho nhiều khách và dọn hạn.
- `src/signing-key.js`: import khóa theo cấu hình ký đã chụp lại.
- `src/maintenance.js`: dọn credential hết hạn theo lô giới hạn.
- `docs/DESKTOP-API.md`: hợp đồng để nối ứng dụng Java/Windows.
- `tools/create-signing-key.mjs`: tạo khóa trên máy tin cậy của chủ phần mềm.

## Kiểm tra và phát triển

Node.js >=22.13 (kiểm thử dùng node:sqlite); Wrangler cần phiên bản Node được hỗ trợ.

```sh
npm ci
npm run check
npm test
npx wrangler d1 execute vncode-accounts --local --file schema.sql
npm run dev
```

Kiểm thử dùng database trong RAM và khóa test. Không dùng SQL backup tài khoản
thật làm fixture. SQLite mô phỏng không chứng minh throughput/độ trễ hay giới hạn
CPU/đọc/ghi trên D1 production. Xem `docs/VALIDATION-031.md`.

## Triển khai

Đọc **HUONG-DAN-CAP-NHAT-032.md**. Cần secret ký và public key tương ứng
trong VNCode Windows; email chỉ cần khi chọn gửi khóa qua Gmail.
Không có secret/private key trong bộ mã. Không deploy trước khi sao lưu dữ liệu.

Resend adapter gửi email tới khách Gmail. Nếu giữ binding EMAIL từ bản cũ,
phải kiểm tra API và quyền gửi tới khách ngoài tài khoản; email routing bị giới hạn
địa chỉ không thay dịch vụ gửi OTP đại trà. Test hiện dùng adapter giả.

## Giới hạn và yêu cầu client

- Client Java/Windows nằm trong `src/main` của repo cha; bộ thư mục website này
  chỉ chứa mã server/trang quản trị. Windows native còn cần nghiệm thu riêng.
- Cửa hàng/máy/offline được ghi trong quyền ký; ứng dụng phải kiểm tra và thực thi.
- App phải lưu device private key, registration token và session bằng DPAPI;
  không tin public key trả từ server để tự chọn khóa xác minh.
- Offline chỉ dùng quyền có chữ ký, đúng máy, còn hạn. Đã nhận 401/403 phải dừng,
  không coi đó là mất mạng. Với offline=0, không dùng lease làm quyền offline;
  online_valid_until chỉ cho phiên vừa kiểm tra, tối đa 5 phút.
- Thu hồi không thể lập tức vô hiệu máy đang offline. Admin nhìn hạn offline cuối.
- Email/signing-ready chỉ là có cấu hình, không chứng minh dịch vụ hoạt động.
- Nền xác thực website cũ dùng PBKDF2 100000 vòng do giới hạn Workers; chưa nâng
  sang hệ xác thực quản lý. Không thay hash mật khẩu cũ trong migration.
