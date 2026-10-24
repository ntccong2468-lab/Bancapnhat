# Mua KIZ sau khi thẻ chuyển sang Đã xuất bản

Nền sửa `585d15c`, nhánh `fix/update-gtin-ozon-fbs-layout`. Người dùng gửi ảnh `KIZ_GTIN_MAPPING_MISSING`, thông tin đơn/SKU trống và mã lỗi lặp hai lần.

## Tái hiện

Hai ca mới chạy trên WB/Ozon đều thất bại trước sửa: một đăng ký có owner/scope/payload đúng chuyển từ PROCESSING sang PUBLISHED, Catalog đã xác nhận published/trade unit và khớp article/màu/cỡ, nhưng chưa ghi GTIN vào barcode sàn. Danh sách ánh xạ vẫn nhận GTIN từ đăng ký đã xuất bản, trong khi cổng mua chỉ nhận đăng ký đang xử lý hoặc proof barcode native. Vì vậy chính việc xuất bản làm mất quyền đi tiếp đến bước mua.

Đây là lỗi đã tái hiện trong mã. Chưa có dữ liệu máy seller để kết luận ảnh của họ chỉ có đúng nguyên nhân này; thông báo cùng mã có thể do thiếu hoặc sai dữ liệu ghép thẻ thật.

## Sửa

- Thêm view proof riêng cho mua KIZ, nhận đăng ký đã xuất bản khi owner, chứng thư/môi trường, native product/SKU, payload và article/màu/cỡ vẫn đúng, đồng thời Catalog có quan sát mới thuộc đúng owner/source, published/trade unit và đúng biến thể. Cache không có owner, cũ, khác chủ/cỡ, không published hoặc không rõ danh tính không được cho qua.
- Không bắt buộc hoàn tất ghi barcode WB/Ozon trước khi mua cho đăng ký chính xác đã được Catalog xác nhận. Không thay điều kiện xác nhận ghi GTIN trên sàn, không tạo GTIN mới, không biến hàng nháp thành hàng đã xuất bản.
- Metadata dự phòng cho đăng ký đã xuất bản dùng giấy tờ hiện hành của Catalog thay vì giấy tờ nháp. CIS type được đọc lại theo luồng mua hiện có. Hàng đợi vẫn dùng anchor không active khi chưa có projection Catalog, không tạo bản ghi active giả; đổi owner làm anchor mất quyền mua.
- Giữ thứ tự kiểm tra tồn kho và pipeline chưa xong trước khi mua phần thiếu. UUID/idempotency, owner giao dịch cũ, giấy tờ, chữ ký, OMS và license được giữ nguyên.
- Giữ mã đơn WB hoặc posting Ozon cùng native ID/SKU trong lỗi. Không hiện dòng đơn/SKU trống; mã lỗi chỉ hiện một lần. Bốn bản dịch hướng dẫn đồng bộ Znack và kiểm tra ghép GTIN khi chưa đủ bằng chứng.

## Kiểm tra

111 ca mục tiêu cuối đạt, gồm proof WB/Ozon, luồng mua/đọc lại và migration, tồn kho/bù thiếu, print planning, readiness và thông báo bốn ngôn ngữ. Có ca đi qua điều kiện GTIN/giấy tờ đến bước kiểm tra OMS, lưu hàng đợi và chặn sau đổi owner; các ca âm kiểm tra Catalog thiếu/cũ/sai owner/cỡ/trạng thái. Hai lần biên dịch fixture trung gian sai tên import đã sửa; không tính là lượt kiểm thử đạt.

Full `clean verify` hoàn tất lúc 07:46:15 UTC, BUILD SUCCESS: 2.963 ca, 2.961 đạt, 2 bỏ qua theo hệ điều hành, không lỗi/thất bại, 17 phút 18 giây. Log `/tmp/vncode-kiz-purchase-final-verify.log`. Hash cây mã của lượt này: `3088a54e0c24b40dc6f4cfc60b666eae06f2a73579a91cbf652f65d586ace9d2`.

Không mua KIZ có phí, ký bằng chứng thư seller hoặc ghi WB/Ozon/Catalog production. Chưa tạo bộ cài, chưa đổi Latest. Thử file `zl.txt` và kết luận 200/200 bị Ozon validation từ chối được lưu trong [bàn giao trước](../validation/2026-10-24-update-gtin-ozon-handoff.md); không gán lại file không có crypto này.
