# Bàn giao VNCode 1.5.16

Đã công bố **Latest v1.5.16 lúc 2026-10-25T07:01:10Z**, Release `423202074`, bất biến. [Trang phát hành](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.5.16) · [Bộ cài EXE](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.5.16/VN-code-1.5.16-Windows-x64.exe).

## Mã và nội dung

- Nhánh `release/vn-code-1.5.16`; mã ứng dụng và tag `96b439d50b14f78d973fb24420f6ac46c078e95c`. Hash cây nguồn `6382ff208a91569bb09a39b31dcc09ddcf2a78a8098135c47f1c77a2efd1f439`.
- Kế thừa toàn bộ 1.5.15, bổ sung sửa In tất cả Ozon bị thiếu danh tính trang PDF và bố cục FBS theo WB. Đọc số đơn/barcode vận chuyển, chuẩn hóa dấu gạch nối và khoảng trắng, hỗ trợ barcode-only; nếu PDF chung không xác minh đủ, dùng tác vụ chính thức riêng từng posting và lưu receipt. Không đoán theo thứ tự trang/barcode sản phẩm, không phát lại giao dịch chưa rõ kết quả.
- Giữ đường nhanh tối đa 1.000 posting dùng một tác vụ/read/download chung khi xác minh được đủ trang, hai nhãn chính với KIZ và xuất PDF nguyên lô. Lỗi trước hoàn tất giữ tệp đích cũ, chưa tiêu KIZ hoặc ghi đã in. FBS có STT, mã đơn/hạn giao, ảnh giữ tỷ lệ, thông tin hàng, Barcode Ozon/GTIN/SKU, KN3, giá; giữ bốn tab đơn và Kho KIZ, phân trang và thao tác trên cửa sổ hẹp.
- Ứng dụng được chốt trước dựng; sau đó chỉ thêm quy trình công bố, biên nhận và tài liệu. Không đổi tag hoặc binary sau kiểm thử.

## Bằng chứng

- [Windows build 40167802021](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/40167802021) đúng SHA: **3019 ca, 3017 đạt, 2 bỏ qua theo OS, 0 thất bại/0 lỗi**. Linux `clean verify`: 3019 ca, 3017 đạt, 2 bỏ qua theo OS, không lỗi/thất bại. Node **117/117**, hồi quy liên quan trước đóng gói **161/161**.
- Native Windows mở app, DPAPI, cài/gỡ và nâng cấp bằng EXE từ **1.5.15**, giữ dữ liệu đều đạt. Giữ UpgradeCode `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`, thư mục cài `%LOCALAPPDATA%\VNcodeApp` và dữ liệu `%LOCALAPPDATA%\VNcodeData`.
- [Công bố 40168947162](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/40168947162) thành công: 16 tài sản; tải công khai và kiểm tra byte/checksum đủ 11 tài sản bắt buộc, manifest/checksum và tag. Gói đúng phiên bản, có các phương thức sửa nhãn/receipt/FBS mới và Catalog kế thừa; 1150 file nguồn/test/pom khớp nguồn chốt.
- Bộ cập nhật từ JAR Windows đã phát hành **1.5.15** đọc JSON `/releases/latest` công khai, nhận 1.5.16 mới hơn và chọn đúng MSI/hash/kích thước. Chưa quan sát thông báo trên thiết bị seller; app có thể nhận qua Kiểm tra phiên bản.

| Tài sản | Kích thước | SHA256 |
| --- | ---: | --- |
| VN-code-1.5.16-Windows-x64.exe | 147282432 byte | `57afd4f82c378cab5e2ae114e998b78effa65cab0c99212a91cbf3abc84cd7f4` |
| VN-code-1.5.16-Windows-x64.msi | 146613513 byte | `6ba2225a98a25e8876c3c7a8e052aaebe129a1718adc2833d67691c41132680d` |

[Biên nhận công bố](2026-10-25-vncode-1516-published-latest.json) · [Tải công khai](2026-10-25-vncode-1516-public-verification.json) · [Full suite trước công bố](2026-10-25-vncode-1516-prebuild.json) · [Chi tiết sửa Ozon](../fixes/2026-10-25-ozon-batch-label-fbs.md).

## Giới hạn và bàn giao

Kiểm thử PDF/KIZ dùng dữ liệu tổng hợp và HTTP localhost; API Ozon thật của lượt sửa chỉ đọc danh sách/chi tiết/barcode. Không tạo nhãn, thay đổi đơn/GTIN, tiêu hoặc mua KIZ production trong lượt phát hành. Native và nâng cấp chạy trên Windows thật của CI; chưa nghiệm thu bấm in trên máy seller. Bộ cài chưa có chữ ký Authenticode. Giữ các release cũ.

Nguồn nền cho phiên tiếp theo là 1.5.16 đã kiểm chứng. Khi sửa ứng dụng, tạo phiên bản mới; không ghi đè tag/tài sản bất biến. Giữ danh tính và trạng thái tác vụ riêng chưa rõ kết quả khi tiếp tục hoặc in lại. PDF chung không đủ bằng chứng sẽ cần thêm request lấy nhãn riêng; không bỏ đối soát để giảm request.
