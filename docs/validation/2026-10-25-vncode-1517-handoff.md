# Bàn giao VNCode 1.5.17

Đã công bố **Latest v1.5.17 lúc 25.10.2026 16:11:04 (Moskva)**, Release `423411451`, bất biến. [Trang phát hành](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.5.17) · [Bộ cài EXE](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.5.17/VN-code-1.5.17-Windows-x64.exe).

## Nguồn và hành vi

- Nhánh `release/vn-code-1.5.17`, mã ứng dụng/tag `d49b7165064f30fd9aaa27a3a619bb3de072e0cb`, hash cây `8d72fadeb7ec4fc2dd4d1d9c414c39768d9db968dd94ebc88e66a116daca4880`. Sau chốt nguồn chỉ thêm chứng cứ/quy trình công bố/tài liệu.
- FBS Ozon: nhận thành công mở Đơn mới; xuất PDF thành công tự chọn Đang giao hàng và trang đầu. Đơn đã in có receipt của đúng cửa hàng được loại khỏi In tất cả, đơn mới nhận thêm vẫn ở Đơn mới. Hủy hoặc xuất lỗi giữ đơn chưa in; tab Đã bàn giao và trạng thái vận chuyển sàn độc lập.
- Khôi phục lịch sử thành công từ cả `OZON_FBS_PRINT` và `ozon_fbs_print`; không nhận báo cáo chờ/lỗi hoặc PDF chưa hoàn tất làm đã in. UI thử tác vụ xuất, hai PDF fixture, chuyển tab và lần in kế tiếp chỉ ARRIVED.
- Giữ dữ liệu sản phẩm/thuộc tính/giấy tờ, GTIN, Catalog/feed, KIZ và lịch sử in qua cập nhật/mở lại. Znack phục hồi danh sách từ lần đọc tốt; dữ liệu lịch sử không tự cho phép ghi mới. Ozon tự mở dùng catalog đã lưu, Đồng bộ thủ công vẫn lấy thay đổi mới.
- Sửa GTIN mua/in WB đúng barcode của size và Ozon thẻ đã published; nhãn riêng Ozon v3 create/v2 get, chờ và phục hồi receipt. Bảng Sản phẩm/Znack/FBS Ozon rộng/cao theo FBS WB. Kế thừa toàn bộ 1.5.16.

## Kiểm chứng

- Windows build [40192806404](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/40192806404) đúng nguồn: **3044 ca, 3042 đạt, 2 bỏ qua theo OS, 0 thất bại/0 lỗi**. Linux full đúng nguồn: 3044 ca, 3042 đạt, 2 bỏ qua, không lỗi/thất bại. Node 117/117. Các lượt liên quan 1043, 43, 34 và 40 là tập con theo từng lần chốt, không cộng lặp.
- Native Windows mở app, DPAPI, cài/gỡ và nâng cấp EXE từ **1.5.16**, giữ dữ liệu đạt. Giữ UpgradeCode `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`, thư mục `%LOCALAPPDATA%\VNcodeApp` và dữ liệu `%LOCALAPPDATA%\VNcodeData`. Kiểm thử Java nâng phiên bản so hash từng bảng nghiệp vụ, giữ cả cursor/kết quả/deadline.
- Publisher [40194271648](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/40194271648) thành công. Đủ 16 tài sản; tải công khai và xác minh checksum/byte cho 11 tài sản bắt buộc cùng manifest/checksum/tag. 1152 file nguồn/test/pom trong archive khớp; JAR có phiên bản và các sửa mới.
- Updater parser lấy từ runtime Windows **1.5.16 đã phát hành**, đọc JSON Latest công khai, nhận 1.5.17 mới hơn và chọn đúng MSI/hash/kích thước. Chưa quan sát popup trên thiết bị seller; app có thể nhận qua Kiểm tra phiên bản.

| Tài sản | Kích thước | SHA256 |
| --- | ---: | --- |
| VN-code-1.5.17-Windows-x64.exe | 147290624 byte | `1552ac8f9dcc2ec812b7bc3ee458d1c79280709208a1a8c7732e4d7df9acbb3c` |
| VN-code-1.5.17-Windows-x64.msi | 146621705 byte | `a747da3fba36b60e75446389dad19839ff46349896680e875b1cd03dde0b0226` |

[Biên nhận Latest](2026-10-25-vncode-1517-published-latest.json) · [Tải công khai](2026-10-25-vncode-1517-public-verification.json) · [Full suite](2026-10-25-vncode-1517-prebuild.json) · [Luồng Đơn mới/in](../fixes/2026-10-25-ozon-new-orders-print-routing.md).

## Phạm vi và tiếp tục

PDF/KIZ dùng fixture và HTTP localhost; không thay đơn/Catalog/GTIN hoặc mua/tiêu KIZ production trong lượt phát hành. API Ozon thật của lượt sửa trước không có đơn awaiting_deliver phù hợp để thử PDF seller. Native và nâng cấp chạy trên Windows CI; chưa nghiệm thu in trên máy seller. Bộ cài chưa có Authenticode. Giữ các release cũ.

Các lượt Windows 40189154399, 40189545605 và 40191235029 đã hủy trước công bố do sửa manual sync/yêu cầu bổ sung/tương thích lịch sử; không dùng artifact của chúng. Chỉ nguồn cuối `d49b7165064f30fd9aaa27a3a619bb3de072e0cb` được phát hành. Phiên sau dùng 1.5.17 làm nền và tăng phiên bản khi sửa ứng dụng; không đổi tag/binary bất biến.
