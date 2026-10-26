# Rà soát sửa sản phẩm và thu gọn giao diện — 26.10.2026

Người dùng yêu cầu kiểm tra 5 thao tác sửa thương hiệu, chất liệu, giới tính, TN VED và giấy tờ trên WB/Ozon; ảnh cho thấy nút giấy tờ xuống hàng và tràn khỏi nền thanh thao tác.

## Sửa đổi

- Tái hiện ở vùng làm việc 1078 px: nền được cấp 66 px nhưng nút cần hai hàng. Thanh thao tác mới đo chiều cao theo chiều rộng thực dành cho các nút; xuống hàng thì nền và chân trang tăng đủ chiều cao. Giữ chọn tất cả mọi trang, 5 thao tác và nút bỏ chọn.
- Thu gọn riêng Sản phẩm: chữ 16 → 14 px, tên 18 → 16 px; chữ nút 15 → 13 px, chiều cao tối thiểu 40 → 34 px. Hàng 104 → 92 px; ảnh 56×76 giữ tỷ lệ. Giữ lề và khung danh sách theo FBS WB. Viên thuốc dài xuống dòng trong chiều rộng danh sách.
- Bảng thương hiệu 720×540 thay 860×620; chất liệu rộng 620 px, giới tính 440 px. Hộp sửa giữ vùng cuộn và giới hạn theo owner; Áp dụng/Hủy vừa cửa sổ nhỏ. Mục kỹ thuật giấy tờ Ozon mặc định thu gọn.
- Sửa Ozon chọn giấy tờ đã tải vẫn bị yêu cầu file: giữ ID/nguồn qua bảng chọn, đọc `/v1/product/certificate/info`, đối chiếu ID/số/loại/ngày/hiệu lực rồi gắn SKU đúng sản phẩm bằng `/v1/product/certificate/bind`. Tải mới vẫn dùng `/v2/product/certificate/create`. Không đổi TN VED sản phẩm theo giấy tờ.
- Chỉ hoàn tất sau khi đọc lại đúng certificate_id ở từng sản phẩm qua `/v1/product/certificate/list`, hỗ trợ nhiều trang. Từ chối rõ ràng chuyển FAILED; mất/thiếu kết quả giữ đối soát và không gửi lặp. Chặn giấy tờ thay đổi hoặc bị từ chối. Bind thành công không được suy là giấy tờ đã được kiểm duyệt.

## Kiểm chứng

**239/239 ca liên quan đạt**, 34 lớp, không lỗi/thất bại/bỏ qua, 2 phút 37 giây trên nguồn `3fd8618bd2d2b3aa5a0b5fbb9c6f4a92b82d6699`.

- Bấm Áp dụng thực trong 10 trường hợp (5 trường × 2 sàn), cửa sổ owner 760×500. Reader/adapter, payload và đọc lại được kiểm tra riêng qua API giả lập; gồm giấy tờ có sẵn và tải mới Ozon.
- 32 tổ hợp chiều rộng 760/940/1078/1331, 4 ngôn ngữ, 2 theme: 5 nút + bỏ chọn nằm trong nền thật, không đè danh sách. 1078 px đại diện vùng làm việc logic hẹp khi có scale; chưa thử DPI Windows native.
- Lô 1.000 sản phẩm WB/Ozon giữ giá trị không sửa và đọc lại đủ; chống gửi lặp, đổi nguồn, từ chối và mất phản hồi. Benchmark 10 lô ghi áp dụng thương hiệu/thuộc tính; giấy tờ có pha tài sản riêng.
- Kiểm tra TN VED, sao chép mã, đề xuất, bố cục workspace và diện tích danh sách so FBS WB. Các lượt thử trước là tập con/nguồn trung gian, không cộng vào 239.

[Trước sửa](images/2026-10-26-product-edit-layout/before.png) · [Sau sửa](images/2026-10-26-product-edit-layout/after.png) · [Biên nhận](../validation/2026-10-26-product-edit-layout-audit-verify.json). Ảnh từ JavaFX thật với dữ liệu tổng hợp.

## Bàn giao

Nhánh `fix/product-card-bulk-edit-identity`, kế thừa sửa khóa sản phẩm `b20c8d2`; nguồn mới và hash cây nằm trong biên nhận. Chưa full suite/native Windows, tạo bộ cài hoặc thay Latest 1.5.17 cho sửa này; không ghi seller production. Lần phát hành sau cần tăng version từ nguồn này, giữ tag/binary 1.5.17 bất biến. Hợp đồng Ozon lấy từ snapshot chính thức có sẵn trong môi trường (`vncode-ozon-swagger-20261024.json`), không nhận là schema vừa tải mới.
