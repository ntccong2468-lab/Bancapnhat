# Báo cáo VNCode 1.5.4

## Phạm vi sửa

- Bước «Chuẩn bị dữ liệu» của updater: cố định ảnh đọc SQLite/WAL trước khi sao lưu để các lần ghi nền không làm sao chép quay lại từ đầu. Đường sao lưu có tiến độ theo số byte, tiến độ băm/kiểm tra và xử lý hủy. Giữ toàn bộ kiểm tra checksum, toàn vẹn và bản sao phục hồi; chưa ghi nhận kết quả đo trên máy seller.
- Kho KIZ WB/Ozon: lỗi định danh dạng `stock:<UUID>:GTIN` không được coordinator mua chung chấp nhận được sửa bằng một UUID hợp lệ cho mỗi yêu cầu GTIN mới. Không đổi danh tính của giao dịch đã tạo.
- API nhận mã KIZ: yêu cầu lấy khối mã có tác dụng cấp mã dù dùng GET, nên không được tự gửi lại sau lỗi HTTP hoặc mất phản hồi. Giữ đối soát bằng danh tính đơn/khối hiện có. Chỉ chuyển sang tải xong khi số mã đã lưu đủ số đã mua; phản hồi một phần vẫn chờ.
- Đọc trạng thái mua: chỉ lấy buffer khớp GTIN của đơn. Thiếu, trùng hoặc thiếu trạng thái buffer được báo bằng mã lỗi riêng và lời giải thích; giữ đơn mua hiện có để kiểm tra tiếp, không xem việc đọc chưa rõ là bằng chứng mua thất bại.

Các thay đổi sử dụng luồng chung cho WB/Ozon. Payload mua, loại phát hành mã, license, chữ ký, TLS và kiểm tra toàn vẹn vẫn được bảo vệ. Dữ liệu và các luồng khác của bản nền 1.5.3 được giữ.

## Bản nền và kiểm tra bắt buộc

Bản nền bất biến: [VNCode 1.5.3](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.5.3), source `69ffc7ecb60a91c874510522c4aae3c5be069ccd`. Bộ cài `VN-code-1.5.3-Windows-x64.exe` có 146.864.128 byte, SHA-256 `ec608d3677581c12901ee702e516cb2eea2757a5a9ee8fd84a2ce2438e8a3f31`.

Source cuối 1.5.4 là `688bd01982898e18c9770300fa20cfe29bde1245`; Windows [39415265129](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/39415265129) đang chạy từ đúng source này. Linux `clean verify` của cùng source đạt 2.088 kiểm thử: 2.086 đạt, không thất bại hoặc lỗi, hai bỏ qua theo nền tảng, thuộc 340 bộ kiểm thử; kết thúc lúc 11:41:08 UTC ngày 20/10/2026 sau 12 phút 11 giây. Chưa có kết quả Windows/native cuối, checksum hoặc kích thước bộ cài mới. Trước phát hành phải chạy hồi quy đầy đủ Linux, công cụ phát hành, toàn bộ Java Windows và mọi kiểm tra native/nâng cấp trước đây. Bổ sung nâng cấp từ bộ cài 1.5.3 đúng checksum, giữ dữ liệu và xác minh chính EXE phát hành với `/passive /norestart`. Không dùng kết quả 1.5.3 làm kết quả kiểm thử của mã 1.5.4.

Kiểm tra trọng tâm cần chứng minh: sao lưu kết thúc khi vẫn có ghi WAL, tiến độ và hủy đúng; coordinator nhận yêu cầu bổ sung KIZ mới của WB/Ozon; lỗi nhận khối mã không tự phát lại yêu cầu; số lượng nhận một phần không báo xong; trạng thái khác GTIN, thiếu hoặc trùng buffer không làm sai đơn mua.

Kiểm tra trọng tâm của mã đã đóng phạm vi đạt 193/193 theo kết quả root, gồm sao lưu khi có ghi WAL, updater UI/FXML và KIZ. Bộ công cụ phát hành đạt 92/92 kiểm thử Node sau khi khóa source và lượt Windows thực tế. Đây chưa phải kết quả full Java Windows hoặc bằng chứng bộ cài. [Chi tiết sao lưu/tiến độ](../validation/2026-10-20-vncode-154-update-preparation.md).

## Chưa hoàn tất

Linux toàn bộ đã đạt; Windows/native/bộ cài của source cuối và tải công khai của 1.5.4 còn chờ bằng chứng thực. Chưa thực hiện mua KIZ trả phí thật, ký CryptoPro doanh nghiệp, ghi thẻ Catalog hoặc GTIN lên sàn qua desktop seller trong phiên bản này. Mọi cờ nghiệm thu production tương ứng vẫn false.

Phát hành theo yêu cầu tiếp tục của chủ dự án sau khi các kiểm tra bắt buộc đạt; chưa nhận nghiệm thu production đầy đủ. Quyền license không giới hạn của phiên kiểm thử chỉ áp dụng dữ liệu cô lập WB/Ozon, không thay đổi kiểm tra license/chữ ký của app. Latest hiện vẫn là 1.5.3 và chưa có hành động phát hành hoặc thay tag của 1.5.4.
