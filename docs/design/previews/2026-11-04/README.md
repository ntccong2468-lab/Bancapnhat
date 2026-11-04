# VN Code — mẫu giao diện chờ duyệt

Đây là bản xem trước riêng, chưa áp dụng vào app. Dữ liệu và các thao tác đều minh họa, không gọi API hoặc sửa cửa hàng thật. Giao diện đề xuất bằng TASTE và Impeccable; [kết quả review](REVIEW.md).

| Màn hình | Ảnh |
| --- | --- |
| Tổng quan sáng, bỏ ba ô KPI và khoảng trống | [overview-light.png](screenshots/overview-light.png) |
| Tổng quan tối | [overview-dark.png](screenshots/overview-dark.png) |
| FBS Ozon, danh sách và thao tác lô | [orders-dark.png](screenshots/orders-dark.png) |
| FBS phóng to 1920×1080 | [orders-maximized.png](screenshots/orders-maximized.png) |
| Sản phẩm và năm nút sửa | [products-light.png](screenshots/products-light.png) |
| Đăng ký Znack | [znack-dark.png](screenshots/znack-dark.png) |
| Quản lý: sửa/thêm cửa hàng | [store-controls.png](screenshots/store-controls.png) |
| Xác nhận xóa đúng cửa hàng | [store-delete-confirm.png](screenshots/store-delete-confirm.png) |
| Tác vụ bổ sung trên thanh trên | [more-tools.png](screenshots/more-tools.png) |
| Công cụ khác | [other-tools.png](screenshots/other-tools.png) |

Để xem tương tác, phục vụ thư mục này bằng HTTP cục bộ rồi mở `index.html`. Không cần dependency hoặc kết nối API. Số dòng, số tiền, tài khoản và tin tức trong mẫu không phải dữ liệu của seller.

## Màn hình bổ sung và chuyển động

[Mở bộ ảnh đầy đủ](gallery.html), [xem video thao tác](motion-demo.mp4), [nguyên tắc chuyển động](MOTION.md). Bản mẫu tương tác có thể mở từ `index.html` sau khi giải nén gói tải về; trong môi trường kiểm thử trình duyệt yêu cầu phục vụ qua HTTP cục bộ. Link Lucian UX/UI được dùng làm nguồn tham khảo, kết hợp TASTE và Impeccable; không cài hoặc chạy script từ website đó.

| Mục | Nền sáng | Nền tối |
| --- | --- | --- |
| Đóng hàng FBO | [Ảnh](screenshots/fbo-light.png) | [Ảnh](screenshots/fbo-dark.png) |
| Liên kết KIZ | [Ảnh](screenshots/kiz-light.png) | [Ảnh](screenshots/kiz-dark.png) |
| GS1 RUS | [Ảnh](screenshots/gs1-light.png) | [Ảnh](screenshots/gs1-dark.png) |
| Cấu hình Znack | [Ảnh](screenshots/znack-config-light.png) | [Ảnh](screenshots/znack-config-dark.png) |
| Kho KIZ seller | [Ảnh](screenshots/kiz-warehouse-light.png) | [Ảnh](screenshots/kiz-warehouse-dark.png) |
| Báo cáo tài chính | [Ảnh](screenshots/finance-light.png) | [Ảnh](screenshots/finance-dark.png) |
| Lịch sử in | [Ảnh](screenshots/history-light.png) | [Ảnh](screenshots/history-dark.png) |
| Thiết kế mẫu | [Ảnh](screenshots/templates-light.png) | [Ảnh](screenshots/templates-dark.png) |
| Cài đặt | [Ảnh](screenshots/settings-light.png) | [Ảnh](screenshots/settings-dark.png) |
| Hướng dẫn | [Ảnh](screenshots/guides-light.png) | [Ảnh](screenshots/guides-dark.png) |
| Mã TN VED | [Ảnh](screenshots/tnved-light.png) | [Ảnh](screenshots/tnved-dark.png) |

Ảnh khác: [bảng chi tiết đơn](screenshots/orders-detail-dark.png), [thông tin tài khoản Znack](screenshots/config-info-light.png), [lịch sử mua KIZ](screenshots/config-history-dark.png), [đơn hàng FBO](screenshots/fbo-orders-light.png), [sao chép bài đăng](screenshots/copy-light.png).

Video minh họa mở/đóng chi tiết, chọn nhiều dòng, chuyển tab, đổi nền giữ lựa chọn, sửa/lưu bản nháp cấu hình, chuyển sang kho KIZ và sửa Article của mẫu tem. Các thao tác chỉ thay đổi trạng thái trong trình duyệt. Không gửi dữ liệu lên sàn, không mua/gán KIZ hoặc sửa cửa hàng thật.

## Nguồn ảnh

37 PNG trong `screenshots/` và video là ảnh/quay Chromium từ chính HTML/CSS/JS của thư mục này ngày 04.11.2026. Đây là giao diện dựng bằng mã, không phải ảnh chụp JavaFX hoặc ảnh sinh bằng AI. Ảnh sản phẩm minh họa tải từ Unsplash; URL và nguồn font Inter ở [assets/PROVENANCE.json](assets/PROVENANCE.json). Không dùng ảnh nhãn, mã KIZ hoặc dữ liệu khách hàng thật trong bản mẫu.

Các bản chụp chính có kích thước và kiểm tra bố cục trong [capture-report.json](screenshots/capture-report.json). Hai menu `more-tools`/`other-tools` chụp tại 1340×865 sau khi animation ổn định; ảnh xác nhận xóa cũng ở 1340×865.
