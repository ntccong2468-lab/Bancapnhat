# Vncode 1.1.33 — phục hồi đăng ký GTIN

Bản Windows riêng của Vncode, phát triển từ mã nguồn WCode 1.1.32 và module
GTIN đã bổ sung. Các thay đổi đăng ký dưới đây dựa trên mô tả công khai của
[WCode 1.1.75](https://github.com/rupphi/relatest-wcode/releases/tag/v1.1.75).
Đây không phải bản build từ mã nguồn gốc 1.1.75.

## Thay đổi trong bản này

- Chọn từng sản phẩm hoặc chọn nhiều sản phẩm WB để đăng ký/thử lại.
- Hiển thị “Cần kiểm tra kết quả cấp GTIN” khi mất kết quả xin mã hoặc khi
  khởi động lại sau yêu cầu chưa xác định được GTIN.
- Thao tác “Xin GTIN mới” yêu cầu xác nhận. Yêu cầu trước có thể đã tiêu hao
  hạn mức GS1; ứng dụng không tự xin lại khi kết quả chưa rõ.
- Lưu checkpoint trước yêu cầu cấp GTIN; chặn yêu cầu đang chạy, dữ liệu cũ
  và khôi phục tài khoản làm chạy lại một yêu cầu.
- Giữ GTIN, mã feed, danh tính thẻ và kết quả gửi đã lưu. Thẻ đã hoàn tất
  không bị tạo lại hoặc đổi GTIN bởi thao tác phục hồi.

- Chặn thư viện HTTP tự lặp yêu cầu cấp GTIN/gửi feed sau timeout.
- GTIN đã giữ cho một cửa hàng được loại khỏi lựa chọn của mọi cửa hàng cục bộ.
- Lỗi trước khi cấp GTIN có thể thử lại; lỗi mất kết quả gửi feed yêu cầu kiểm tra.

## Dữ liệu và nguồn cập nhật

Vncode nâng phiên bản schema cục bộ từ 3 lên 4 để binary cũ không đọc nhầm
các trạng thái phục hồi mới thành lỗi có thể cấp lại mã. Không xóa hoặc đổi cấu
trúc các bảng đăng ký hiện có. Khi nâng cấp, ứng dụng tạo snapshot đã kiểm tra,
giữ GTIN/feed/good ID/cờ cập nhật WB và lịch sử cũ. Không chạy bản cũ trực tiếp
trên database đã nâng cấp.

Nguồn cập nhật mặc định chuyển sang `ntccong2468-lab/Vncode`; nguồn mặc định
cũ được nhận diện cả khi đã lưu trong cấu hình. Các URL tùy chỉnh được giữ lại.
Bản này là prerelease Windows chưa ký Authenticode, cài thủ công từ GitHub;
không phát hành manifest tự cập nhật có chữ ký khi chưa có khóa tin cậy.

Workflow `build-java.yml` chạy kiểm thử và kiểm tra native launcher/migration
trên Windows, rồi lưu artifact. Pipeline nhiều nền tảng kế thừa chỉ chạy thủ
công trên fork để tag Windows không tự tạo thêm bản macOS.

## Phạm vi nghiệm thu

Mã nguồn gốc WCode 1.1.75 ở `rupphi/source-wcode` hiện chưa truy cập được;
repository release chỉ có README. Bộ cài tham chiếu 1.1.75 và manifest đã
được đối chiếu SHA-256. EXE tham chiếu có hash
`509e29e167b4731e8a387e4f9309cfb3779405ba84bd75c16ba9fd1ffab42cca`.
Hash này thuộc WCode 1.1.75 gốc, không phải bộ cài Vncode mới.

Kiểm thử của Vncode dùng SQLite tạm và API fixture. Live GS1, CryptoPro,
tài khoản seller và máy in thật cần nghiệm thu riêng. Chức năng thêm/thay
GTIN production của module **GTIN WB / Ozon** vẫn bị khóa tới khi hợp đồng
API chính thức được xác minh.
