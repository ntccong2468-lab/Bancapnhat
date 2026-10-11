# VN code — đầu vào cần để lập kế hoạch và tích hợp backend giấy phép

Ngày 12/10/2026. Người sở hữu đã duyệt [thiết kế B](../superpowers/specs/2026-10-12-vn-code-web-license-design.md): email Gmail đăng ký trên website và mật khẩu website; admin duyệt, gia hạn, khóa; mặc định 1 máy và offline 72 giờ, có cấu hình hạn mức.

## Đã có

- Website `vncode.id.vn` đang trả lời; API health trả 200 và client/me không có xác thực trả 401.
- README mô tả `/api/client/login`, `/api/client/me`, Bearer 7 ngày và Cloudflare Workers/D1.
- Thiết kế quyền, luồng dữ liệu, chữ ký, thiết bị, chính sách lỗi và tương thích đã được duyệt. Chưa thêm code giấy phép vào app.

## Cần nhận mã nguồn

README là hướng dẫn, không phải backend. Để viết kế hoạch theo đúng cấu trúc hiện có, cần bản ZIP hoặc repository gồm:

- `src/index.js` và các module Worker mà nó import;
- `schema.sql`, các migration đang dùng, test backend;
- `package.json`, lockfile, cấu hình Wrangler có tên binding/vars công khai;
- nguồn giao diện web (`public/` hoặc thư mục assets thực tế) và `HUONG-DAN-CAI-DAT-LICENSE.md` nếu có.

Không cần `node_modules`, `.git`, `.env`, `.dev.vars`, mật khẩu admin, Cloudflare token, session token, database người dùng thật hay private signing key. Public key ký lease sẽ được đối chiếu riêng khi chủ phần mềm thiết lập signing secret trên server; không đưa private key vào client/repository.

Repository `ntccong2468-lab/vncodeidvn` tìm thấy theo thông tin công khai hiện chưa có ref/mã nguồn đọc được trong lần kiểm tra này. Không coi đây là nguồn website đã được xác nhận.

## Các điểm phải xác minh từ nguồn trước kế hoạch backend

1. Định dạng ID, bảng users/sessions/mã, middleware Bearer và cách tính `active`/thời hạn; trường hợp admin có ngày hết hạn rỗng.
2. Khóa/gia hạn có thu hồi session/challenge cũ hay chỉ đổi UI; đổi mật khẩu ảnh hưởng phiên desktop thế nào.
3. Giao dịch redeem/gia hạn và migration giữ tài khoản/mã/lịch sử hiện có, khả năng D1 thực thi thao tác quota nguyên tử.
4. Vị trí gắn API thiết bị/lease và giao diện admin vào router hiện có; không tạo lớp xác thực thứ hai trái với website.
5. Runtime Workers/Web Crypto/D1 được kiểm thử; public signing key và byte format của lease phải khớp verifier Windows.

Đây là đầu vào cho kế hoạch, không phải một kế hoạch triển khai backend đã hoàn chỉnh. Khi có nguồn, lập kế hoạch với tên file/hàm/migration thật để người sở hữu duyệt theo cách triển khai trực tiếp đã chọn. Không công bố offline, giới hạn máy hoặc thu hồi key production hoạt động chỉ từ README/fixture.

## Tình trạng bản cập nhật

Phần Products/Copy/Ozon/10-shop tiếp tục sửa và kiểm thử độc lập. Phát hành bộ cài 1.4.0 chờ cả phần giấy phép mới và kiểm tra Windows; các release/tag cũ giữ nguyên. Không deploy Worker, sửa D1, phát hành mã hay đăng nhập admin trong đợt nghiên cứu này.
