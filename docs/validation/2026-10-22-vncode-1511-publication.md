# VNCode 1.5.11 — bản nền đã công bố

Latest: https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.5.11

Nguồn ứng dụng bất biến: `2abe716d77a0f2d4d5f08ed0ae6451bf29ea2495`. Bộ cài và toàn bộ gói được dựng, kiểm thử từ cùng commit trong [lượt Windows](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/39760334344). [Lượt kiểm chứng công bố cuối](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/39802571041) đã thành công.

- Windows: 2.879 kiểm thử, 2.877 đạt, 2 bỏ qua, 0 lỗi/thất bại. Node: 117/117 trong lượt build. Nhóm hồi quy KIZ cuối: 76/76 trên Linux.
- Native Windows, bảo vệ dữ liệu DPAPI, cài/gỡ song song và nâng cấp giữ dữ liệu từ 1.5.10 đã chạy. MSI công khai trùng byte với MSI nhúng trong EXE đã thử nâng cấp; giữ UUID nâng cấp và schema 6.
- Đã tải công khai, đối chiếu SHA-256 và kích thước tất cả tệp cốt lõi: EXE/MSI, runtime có Java, mã nguồn ZIP, Git bundle và báo cáo. ZIP nguồn đủ 1.839 tệp theo Git; 1.732 tệp văn bản dùng CRLF do Git archive trên Windows, nội dung chuẩn hóa trùng blob Git. Bundle chứa blob nguồn nguyên byte và toàn bộ lịch sử đã dựng.
- Bộ cập nhật được kiểm tra bằng JAR trong gói đã giao, đọc JSON Latest công khai, chọn đúng MSI, SHA-256 và 146.572.553 byte; UpdateService trả AVAILABLE cho bản 1.5.10. Chưa quan sát popup trên máy seller.

EXE SHA-256: `32e41d174659071bce9f46f50d245445af19d1ef664184979c90089ff4d47ebb`.

MSI SHA-256: `1d86ae2b4b6168c0bf77941d98e8158003a6f6aadf7a19c5bfc9de4de1ca592f`.

GitHub khóa Release ở chế độ bất biến sau công bố. Biên bản bổ sung nằm trong repository: [tệp công khai](evidence/1511/public-verification.json), [bộ cập nhật](evidence/1511/updater-verification.json), [mã nguồn/gói](evidence/1511/artifact-verification.json) và [kết quả Windows](evidence/1511/windows-build-info.json). Các asset đã phát hành không bị sửa lại.

Đã lưu [toàn bộ danh sách release cũ](evidence/1511/previous-release-inventory.json) vào commit `477499aa3eac9784f461307992869ba0b37c24ea` và đối chiếu blob công khai trước khi xóa. Đã gỡ 30 bản cũ, gồm ba bản nháp; chỉ còn 1.5.11 Latest. [Biên bản dọn](evidence/1511/baseline-cleanup.json) có danh sách và đối chiếu tag nguồn với Git bundle. Không xóa Git history/tag, không thay dữ liệu seller.

WB 10.000: pipeline, adapter, worker, báo cáo và SQLite thật với API giả lập đã xác minh đủ 10.000, chia bốn request ghi, giữ barcode cũ và không phát lại khi WB áp dụng trễ. Xem [báo cáo WB](2026-10-22-wb-10000-audit.md). Số thực tế Mpp fashion vẫn chưa biết; không có dữ liệu/credential shop trong cloud. Không coi các số giả lập là kết quả thực tế của shop.

KIZ: giữ giao diện/nút hiện có; cho phép định danh GTIN đúng thẻ/biến thể đã gửi Catalog đang duyệt để mua KIZ. Kho sẵn dùng đúng GTIN vẫn được gán. Giữ kiểm tra chủ sở hữu, môi trường production, giấy tờ/chữ ký, đối soát UUID yêu cầu cũ và điều kiện lưu thông; mã RECEIVED chưa được coi là sẵn dùng. Xem [báo cáo KIZ](2026-10-22-kiz-pending-audit.md). Không thực hiện mua KIZ có phí hoặc ghi seller production trong kiểm tra này.
