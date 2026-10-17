# Đối chiếu bổ sung cho VNCode 1.4.12

## WCode cũ: kho KIZ theo danh mục

Nguồn cố định: [WCode 1.0.27, commit 457a6113](https://github.com/rupphi/test-wcode/tree/457a6113301978454f244248956962ab1fcdeb7e). Đã đọc trực tiếp các tệp dưới đây. Bảng checksum nằm trong `vncode-1412-wcode-category-sources.json` cạnh tài liệu này.

| Nguồn WCode | Hành vi đã đọc | VNCode áp dụng |
|---|---|---|
| `features/kiz/KizInventoryWorkflow.java` | Nhập mã theo shop/category | Phân loại batch nhập theo nhóm seller chọn |
| `features/kiz/CategoryRepository.java`, `models/Category.java` | Seller tạo nhóm riêng và gắn sản phẩm | Nhóm riêng theo cửa hàng/chủ, picker sản phẩm WB/Ozon |
| `features/kiz/KizService.java` | `getKizs(shopId, categoryId, count)`, FIFO `ORDER BY id` | FIFO trong nhóm, không bắt GTIN sản phẩm bằng GTIN mã nhập |
| `features/print/OrderExportWorkflow.java` | nmID → category, lấy mã theo category | Gán WB theo nhóm trước, phần thiếu đi luồng GTIN |
| `features/fbo/FboKizPrintPlanner.java` | FBO lấy mã theo category | Áp dụng cả FBO WB/Ozon; giữ một mã cho mỗi đơn vị hàng |
| `features/print/KizAttachmentCoordinator.java` | Điều phối gửi KIZ | Giữ journal, scope và đối soát bền vững của VNCode |

Đây là thay đổi yêu cầu của chủ VNCode so với thiết kế 1.4.11: việc chọn kho nhập theo danh mục không còn đòi cùng GTIN sản phẩm. Không sửa lại báo cáo lịch sử 1.4.11 để giả rằng bản cũ đã có hành vi mới. Không mang cơ chế xóa mã cũ của WCode vào thay journal chống dùng trùng của VNCode.

## WBCode mới

Khi bắt đầu rà soát, Latest là 1.3.8. Kiểm tra lại trong lúc làm việc phát hiện [WBCode 1.3.9](https://github.com/rupphi/relatest-wcode/releases/tag/v1.3.9), Published `2026-10-15T16:56:43Z` (19:56:43 Moscow). Ghi chú công khai nêu: một biểu tượng bộ cài, đăng ký lại thẻ Ozon đang kiểm duyệt, khôi phục đăng ký WB sau tắt ứng dụng và giảm nhảy hộp thoại GS1 khi cửa sổ nhỏ. Ghi chú không nêu thay đổi kho KIZ hoặc sao chép bài đăng. Không có mã nguồn nội bộ 1.3.9 để khẳng định đã sao chép toàn bộ cơ chế của bản này; không thực hiện đăng nhập seller trong WBCode để nghiệm thu.

WCode 1.0.27 công khai có 308 đường dẫn trong cây đã đọc; không có module listing/copy/clone để làm căn cứ cho tính năng sao chép bài. Phần đó sửa dựa trên dữ liệu nguồn và hợp đồng Seller API đã xác minh trong VNCode, cùng lỗi thực tế do chủ ứng dụng báo. Không lấy tên tính năng tương tự làm bằng chứng hai triển khai giống nhau.

## Sao chép và mua đúng GTIN

| Lỗi/quy tắc | Thay đổi |
|---|---|
| Đã có thuộc tính gốc nhưng combobox ánh xạ trống | Tự ghép duy nhất theo dictionary đích, giữ giá trị gốc |
| Danh mục cùng sàn phải chọn lại | Tự chọn ID danh mục gốc nếu còn trong danh mục đích |
| Giá/kích cỡ/article bị yêu cầu nhập lại | Đọc giá nguồn khi API cho phép, giữ size/RU size và article phù hợp shop đích |
| Phải mở từng bài trong batch | Tạo mặc định từ bài tương ứng; không áp nội dung bài đầu cho các bài còn lại |
| Barcode chưa có Catalog proof bị chặn ngay khi sao chép | Cho kế thừa barcode từ snapshot live; không tạo verified GTIN mapping hay quyền mua KIZ từ việc sao chép |
| Quy tắc category/article cũ có thể mua nhầm GTIN | Trước khi tạo mua bù, kiểm tra native product, barcode/SKU và GTIN Catalog đúng biến thể |

Sơ đồ và giới hạn: [kiến trúc kho KIZ và sao chép](../architecture/2026-10-15-category-kiz-and-listing-copy.md). Chưa có nghiệm thu gán/mua mã thật hoặc đăng bài thật trên tài khoản seller.
