# VN Code 1.6.2 — bàn giao phát hành

## Trạng thái

Người dùng đã yêu cầu phát hành bản mới chứa các sửa nhãn Ozon và báo cáo tài chính. Nguồn ứng dụng chốt: `3968f8f4bb2239ddf61b48e9f6245086bd4ad529`, kế thừa sửa `7875708` và toàn bộ 1.6.1. Nhánh ứng dụng `release/vn-code-1.6.2`; nhánh công cụ/bằng chứng `release/publish-vn-code-1.6.2`.

Windows build [41789324429](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/41789324429) đang chạy full Java, native, cài/gỡ và nâng cấp từ Latest 1.6.1. **Chưa công bố 1.6.2 tại thời điểm chuẩn bị này**; không dùng kết quả 1.6.1 thay cho kiểm chứng binary mới.

## Phạm vi

- Giữ nguyên nhãn vận đơn Ozon; lọc trang barcode sản phẩm lớn đã nhận diện, giữ nhãn kết hợp. Đọc nhãn theo lô hiện có, không tải lại từng đơn để lọc trang.
- Phiếu nhặt hàng lấy đúng số in trên nhãn từng đơn, dùng `scanit` hỗ trợ đối chiếu, hiển thị số đầy đủ và bốn ký tự cuối nền đen. Không thêm ô mã vào vận đơn nguyên gốc.
- Lưu số nhãn theo shop/owner; không ghép theo article hay thứ tự trang. Không làm thay đổi điều kiện gán/tiêu KIZ.
- Chuyển Lý do phạt sang Công cụ → Báo cáo tài chính, giữ trạng thái chưa hỗ trợ nguồn Ozon khi chưa có hợp đồng API được xác minh.
- Giữ toàn bộ các sửa 1.6.1. Không triển khai prototype credential migration, không đổi schema hoặc UpgradeCode.

[Ghi chú bản cập nhật](../releases/VN-code-1.6.2.md), [báo cáo sửa và bằng chứng trước đóng gói](2026-11-04-ozon-labels-finance/README.md).

## Bằng chứng trước đóng gói

- 302 ca Java liên quan đạt, không lỗi/thất bại/bỏ qua; đây là tập liên quan, không phải full suite.
- Công cụ phát hành trên nguồn ứng dụng: 138/138; sau bổ sung publisher riêng 1.6.2: 141/141 đạt.
- Đọc hai API Ozon và đối chiếu một nhãn thật đã lưu: `scanit` trùng caption, không có mutation. Các phép đo 100/1.000 là mô phỏng.
- Không nhận đơn khách, tạo tác vụ nhãn thật, kích hoạt license, mua/tiêu KIZ hay gửi lại Catalog trong lượt phát hành.

## Tiếp tục

Chỉ lấy artifact của nguồn và run đã pin sau khi đạt toàn bộ Windows gates. Publisher `tools/publish-vncode-162.mjs` kiểm tra build, nguồn, 16 asset/hash, native và nâng cấp bằng đúng EXE trước Latest. Giữ release cũ; không chạy `prune`. Đối chiếu tag/target/ID sau thao tác draft; tránh draft trùng hoặc tag tạm. Upload qua Actions do upload trực tiếp môi trường gặp HTTP 401 ở lượt trước.

Sau công bố: tải công khai/đối chiếu checksum, kiểm tra source ZIP và JAR, chạy bộ đọc updater từ binary 1.6.1 để xác nhận 1.6.2/MSI/hash/kích thước, rồi ghi kết quả và cập nhật bộ nhớ. Chưa quan sát popup hay cài bản mới trên máy seller. Bộ cài chưa ký Authenticode; các giới hạn bảo mật đã ghi ở bản 1.6.1 vẫn giữ nguyên.
