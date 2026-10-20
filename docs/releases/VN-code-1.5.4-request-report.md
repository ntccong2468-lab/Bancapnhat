# Báo cáo VNCode 1.5.4

## Phạm vi sửa

- Bước «Chuẩn bị dữ liệu» của updater: cố định ảnh đọc SQLite/WAL trước khi sao lưu để các lần ghi nền không làm sao chép quay lại từ đầu. Đường sao lưu có tiến độ theo số byte, tiến độ băm/kiểm tra và xử lý hủy. Giữ toàn bộ kiểm tra checksum, toàn vẹn và bản sao phục hồi; chưa ghi nhận kết quả đo trên máy seller.
- Kho KIZ WB/Ozon: lỗi định danh dạng `stock:<UUID>:GTIN` không được coordinator mua chung chấp nhận được sửa bằng một UUID hợp lệ cho mỗi yêu cầu GTIN mới. Không đổi danh tính của giao dịch đã tạo.
- API nhận mã KIZ: yêu cầu lấy khối mã có tác dụng cấp mã dù dùng GET, nên không được tự gửi lại sau lỗi HTTP hoặc mất phản hồi. Giữ đối soát bằng danh tính đơn/khối hiện có. Chỉ chuyển sang tải xong khi số mã đã lưu đủ số đã mua; phản hồi một phần vẫn chờ.
- Đọc trạng thái mua: chỉ lấy buffer khớp GTIN của đơn. Thiếu, trùng hoặc thiếu trạng thái buffer được báo bằng mã lỗi riêng và lời giải thích; giữ đơn mua hiện có để kiểm tra tiếp, không xem việc đọc chưa rõ là bằng chứng mua thất bại.

Các thay đổi sử dụng luồng chung cho WB/Ozon. Payload mua, loại phát hành mã, license, chữ ký, TLS và kiểm tra toàn vẹn vẫn được bảo vệ. Dữ liệu và các luồng khác của bản nền 1.5.3 được giữ.

## Kiểm thử bộ cài phát hành

Source: `688bd01982898e18c9770300fa20cfe29bde1245`. Bản nền: [VNCode 1.5.3](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.5.3); giữ schema 6 và dữ liệu seller.

Linux `clean verify`: 2.088 kiểm thử, 2.086 đạt, 0 thất bại, 0 lỗi, 2 bỏ qua theo nền tảng. Windows Java mới: 2.088 kiểm thử, 2.086 đạt, 0 thất bại, 0 lỗi, 2 bỏ qua theo nền tảng. [Windows build 39415265129](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/39415265129) chạy từ đúng source trên; không dùng lại kết quả Java của bản trước.

Đã xác minh đủ 24 bằng chứng native và 19 ca nâng cấp giữ dữ liệu, gồm 288 bố cục, launcher, workspace, DPAPI và nâng cấp từ bộ cài 1.5.3 đúng checksum. Bàn giao `/passive /norestart` được kiểm tra bằng chính EXE phát hành và MSI nhúng; dữ liệu và đăng ký ứng dụng được giữ.

Bộ cài: `VN-code-1.5.4-Windows-x64.exe`, 146.868.224 byte. SHA-256: `e7079a4aca07c1ae2fec26e46c4e3a3d77096a14b1c0485a88cefe95a3f7a18b`. Checksum và bằng chứng đi kèm dùng để đối chiếu đúng các byte đã kiểm tra. Việc build đạt hoặc updater phát hiện bản mới không chứng minh thông báo đã xuất hiện trên desktop seller.

## Chưa hoàn tất

Chưa nghiệm thu giao dịch mua KIZ trả phí thật, chữ ký CryptoPro doanh nghiệp, license production, tạo/sửa thẻ Catalog hoặc ghi GTIN lên sàn qua desktop seller trong phiên bản này. Mọi cờ nghiệm thu production tương ứng vẫn false; kiểm thử mô phỏng không thay thế bằng chứng giao dịch thật. Chưa đo thời gian cập nhật trên máy seller.

Phát hành theo yêu cầu tiếp tục của chủ dự án với các giới hạn đã công bố, không nhận nghiệm thu production đầy đủ. Quyền license không giới hạn của phiên kiểm thử chỉ áp dụng dữ liệu cô lập WB/Ozon; kiểm tra license, chữ ký, TLS và toàn vẹn của app được giữ. Lượt tải và bàn giao ban đầu từ 1.5.3 vẫn chạy mã updater của 1.5.3; phần sửa updater mới có hiệu lực sau khi cài bản này.

## Xác minh sau phát hành

Đã xuất bản [v1.5.4 trên Latest](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.5.4) ngày 20/10/2026. Tải lại bộ cài công khai xác nhận đúng 146.868.224 byte và SHA-256 ở trên. Mã `UpdateApiClient`/`UpdateService` thật, chạy với cấu hình cô lập đang ở 1.5.3, nhận trạng thái `AVAILABLE` cho 1.5.4 với URL, checksum và kích thước đúng. Chưa quan sát thông báo trên desktop seller. Đã lưu 1.5.4 làm bản nền tiếp theo; giữ bất biến các tag/bộ cài cũ.

[Bằng chứng Windows/native và tải công khai](../validation/2026-10-20-vncode-154-windows-native.json). Lượt phát hành: [39417571803](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/39417571803). Bộ công cụ phát hành đạt 92/92 kiểm thử.
