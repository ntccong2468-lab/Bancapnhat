# VN Code 1.6.2 — bàn giao phát hành

**Đã công bố [1.6.2 lên Latest](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.6.2) ngày 04.11.2026 lúc 08:22:03 UTC**, release `433607147`. Nguồn ứng dụng/tag bất biến `3968f8f4bb2239ddf61b48e9f6245086bd4ad529`, kế thừa sửa `7875708` và toàn bộ 1.6.1. Nhánh `release/vn-code-1.6.2`; công cụ/bằng chứng ở `release/publish-vn-code-1.6.2`.

## Phạm vi đã đưa vào bộ cài

- Nhãn vận đơn Ozon giữ nguyên nội dung và kích thước. Lọc trang barcode sản phẩm lớn đã nhận diện trong PDF lô; giữ nhãn kết hợp và luồng nhận đơn/lấy nhãn hiện có. Không thêm ô mã tự tạo vào vận đơn.
- Phiếu nhặt hàng lấy đúng số in trên nhãn của từng đơn, đối chiếu trường `scanit`; lưu theo shop/owner. Số đầy đủ và bốn ký tự cuối nền đen chỉ là cách hiển thị trên phiếu nhặt hàng. Không ghép bằng article hoặc thứ tự trang.
- Lý do phạt chuyển vào Công cụ → Báo cáo tài chính. Nguồn Ozon vẫn báo chưa hỗ trợ khi chưa xác minh hợp đồng API; không suy phí thành phạt.
- Kế thừa toàn bộ 1.6.1, giữ schema/UpgradeCode/dữ liệu. Không đưa prototype credential migration vào runtime hoặc triển khai portal.

[Ghi chú bản cập nhật](../releases/VN-code-1.6.2.md), [báo cáo sửa và bằng chứng trước đóng gói](2026-11-04-ozon-labels-finance/README.md), [đối chiếu nguồn](2026-11-04-vncode-162-source-inclusion.json).

## Kiểm chứng bộ cài và xuất bản

- [Windows build 41789324429](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/41789324429) SUCCESS trên đúng nguồn: **3.824 ca, 0 thất bại/lỗi, 2 OS skip**, 520 lớp; preflight 69/69. Hai ca bỏ qua là quyền POSIX trên Windows và kiểm tra fallback ngoài Windows. Full suite mất hơn 73 phút; không bị bỏ qua hoặc giảm gate. [Biên nhận](2026-11-04-vncode-162-windows.json).
- Native/migration/snapshot, DPAPI, cài/gỡ độc lập và nâng cấp thụ động bằng đúng EXE từ 1.6.1 đều đạt; dữ liệu và định danh cài đặt được giữ. Hash MSI nhúng khớp MSI phát hành. Không chạy lại full Linux; 302 ca liên quan đã đạt trước đóng gói.
- Công cụ Node 138/138 trên nguồn ứng dụng; 141/141 với publisher. [Publisher 41798807654](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/41798807654) SUCCESS lần đầu. Draft được tạo với `tag_name` và source rõ ràng rồi đọc lại ID/tag/source; không tạo draft trùng. Không thay/xóa release cũ.
- [Cả 16 asset công khai](2026-11-04-vncode-162-public-assets.json) đã tải và đối chiếu hash/kích thước. [Source ZIP](2026-11-04-vncode-162-package.json): 2.646 tệp, 2.417 văn bản chỉ khác CRLF; nhị phân trùng byte. JAR đúng 1.6.2, chứa các lớp sửa nhãn/scanit/Tài chính và không chứa custom shipping badge.
- [Bộ cập nhật từ binary 1.6.1](2026-11-04-vncode-162-updater.json) nhận đúng 1.6.2, chọn MSI, khớp SHA-256 và kích thước với asset tải công khai. Probe ban đầu thiếu dependency SLF4J; đã thêm đúng dependency JAR từ ZIP 1.6.1 được kiểm hash rồi chạy đạt. Đây là lỗi classpath của probe độc lập, không phải lỗi ứng dụng. Chưa quan sát popup/cài trên máy seller.
- [Biên nhận publisher](2026-11-04-vncode-162-publication-receipt.json), [Latest đã xác minh](2026-11-04-vncode-162-published-latest.json).

## Phạm vi thật và tiếp tục

Lượt sửa đọc hai API Ozon và đối chiếu một PDF đã lưu, xác nhận `scanit` trùng số trên nhãn; không tạo task nhãn, nhận đơn hay sử dụng KIZ. Lô 100/1.000 và thời gian trong báo cáo kỹ thuật là mô phỏng. Lượt phát hành không gọi mutation marketplace/Catalog, kích hoạt license hoặc mua/tiêu KIZ. Bộ cài chưa ký Authenticode; các giới hạn security review 1.6.1 vẫn còn, không khẳng định mọi gate bảo mật xanh.

Phiên sau bắt đầu từ source/tag 1.6.2; không thay asset, binary, checksum hoặc tag đã công bố. Publisher pin đúng nguồn/run và kiểm đủ hash/native/upgrade trước Latest. Upload qua Actions; không sao chép credentials để vượt lỗi upload trực tiếp. Khi chạy updater probe ngoài bộ cài, đưa đủ dependency của đúng binary vào classpath. Đọc journal trước khi tiếp tục giao dịch thật để tránh gửi lặp.
