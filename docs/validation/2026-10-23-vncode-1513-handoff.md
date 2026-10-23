# Bàn giao VNCode 1.5.13 — 23.10.2026

**Đã công bố lên Latest lúc 2026-10-23T19:14:18Z**, Release ID `422171061`, bất biến. [Release](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.5.13) · [Tải EXE Windows x64](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.5.13/VN-code-1.5.13-Windows-x64.exe).

- Tag/mã ứng dụng: `7e88dc0e5e4e491025874f560c3ac069b6718644`. Nhánh bàn giao `release/vn-code-1.5.13`; commit sau tag chỉ sửa workflow/tài liệu, không thay mã ứng dụng hay binary.
- Windows build [40002256619](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/40002256619) đạt: 2.929 ca, 2.927 đạt, 2 bỏ qua theo OS, không lỗi/thất bại. Native launch, migration, DPAPI, cài/gỡ và nâng cấp thụ động 1.5.12 → 1.5.13 giữ dữ liệu đều đạt. Node 117/117 đạt.
- Công bố [40002567923](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/40002567923) đạt; 16 tài sản, 11 tài sản bắt buộc đã tải công khai và kiểm tra byte/digest/checksum/tag. Không xóa Release cũ, không thay định danh nâng cấp.
- EXE 147.192.320 byte, SHA256 `7593e46c6eb243b5f310025c43c4e6d9fc25f7e0aed201e7cd347af8c4fea9a9`. MSI 146.523.401 byte, SHA256 `a0a64b71962879c64ed64dea02fa6198f1ebfe9d16e9b2332a3e84a46827ebe7`.
- Đã kiểm tra JAR Windows thật có version 1.5.13, phương thức sửa Catalog và CSS mới; toàn bộ nguồn sản xuất trong source ZIP khớp nhánh phát hành. Bộ cập nhật của JAR này phân tích phản hồi Latest thật và chọn đúng MSI/digest/size; mã bộ cập nhật không đổi so với 1.5.12. Chưa quan sát thông báo trên máy seller. Trong app: Cài đặt → Kiểm tra phiên bản; tự kiểm tra khi mở và theo chu kỳ 45–60 phút.

## Nội dung đã phát hành

Đọc lại các biên nhận còn chờ cuối lô; chỉ đưa vào Đang xử lý khi đã đọc thấy thẻ thật trên Catalog. Biên nhận gửi không được coi là đã có thẻ, yêu cầu đang chờ không bị chọn lại để gửi lặp. Tạo từng thẻ Ozon dùng chung kiểm tra dữ liệu/hàng đợi với theo lô và giữ khả năng khôi phục lô cũ. Ánh xạ Китай chỉ khi dictionary thật xác nhận CN với tên đầy đủ phù hợp. Hàng Sản phẩm/Đăng ký còn 104/76 px, giữ ảnh đúng tỉ lệ và thông tin/thao tác hiện có.

Các quy tắc giấy tờ/TN VED, luồng đồng bộ/KIZ và giao diện nền đen của 1.5.12 tiếp tục được giữ. Ghi chú chi tiết: [1.5.13](../releases/VN-code-1.5.13.md).

## Phạm vi bằng chứng và việc tiếp tục

Người dùng đã bấm Đăng ký cho một fixture cụ thể trước đóng gói; đúng một GTIN và một feed đã được tạo bằng API production, đọc lại khớp article/màu/size/TN VED. Đọc lại trước công bố vẫn thấy đúng thẻ draft/moderation. Không cấp/gửi thêm, không ký CryptoPro, không mua KIZ có phí hoặc ghi GTIN WB/Ozon. Cờ live=false trong build-info là phạm vi CI; biên nhận API riêng xác nhận gửi thẻ thật, không phải nghiệm thu hai nút trên máy seller hay thẻ ký xanh.

Định danh/payload thật giữ riêng ở `/tmp/vncode-live-catalog-20261023/`, không commit vào repo. `live-attempt.json` đã CATALOG_CONFIRMED: không chạy lại allocation/feed. Nếu cần theo dõi chỉ đọc/đối soát giao dịch này. Trước nghiệm thu ký/xuất bản tự động, tiếp tục đối chiếu chuẩn hóa danh mục/nước trong validateReviewedBusinessFields theo [bàn giao phiên sửa](2026-10-23-catalog-readback-compact-handoff.md).

Bằng chứng: [Latest](2026-10-23-vncode-1513-published-latest.json), [Windows build-info](2026-10-23-vncode-1513-windows-build-info.json), [artifact/runtime](2026-10-23-vncode-1513-candidate-verification.json), [tải công khai](2026-10-23-vncode-1513-public-verification.json), [nâng cấp](2026-10-23-vncode-1513-upgrade-smoke.json), [bộ cập nhật](2026-10-23-vncode-1513-updater-metadata.json), [API thật](2026-10-23-catalog-one-card-api-live.json).
