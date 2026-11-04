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

## Nguồn ảnh

Mười PNG trong `screenshots/` là ảnh chụp Chromium từ chính HTML/CSS/JS của thư mục này ngày 04.11.2026. Đây là ảnh giao diện dựng bằng mã, không phải ảnh chụp JavaFX hoặc ảnh sinh bằng AI. Ảnh sản phẩm minh họa tải từ Unsplash; URL và nguồn font Inter ở [assets/PROVENANCE.json](assets/PROVENANCE.json). Không dùng ảnh nhãn, mã KIZ hoặc dữ liệu khách hàng thật trong bản mẫu.

Các bản chụp chính có kích thước và kiểm tra bố cục trong [capture-report.json](screenshots/capture-report.json). Hai menu `more-tools`/`other-tools` chụp tại 1340×865 sau khi animation ổn định; ảnh xác nhận xóa cũng ở 1340×865.
