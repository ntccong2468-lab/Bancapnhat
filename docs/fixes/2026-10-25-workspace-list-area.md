# Mở rộng khung Sản phẩm, Znack và FBS Ozon theo FBS WB

Người dùng xác nhận phần cần sửa là: **«Khung danh sách phải rộng, cao và chiếm đầy vùng làm việc như FBS WB»**. Mẫu đối chiếu là màn FBS WB chính (`packing-view.fxml`). Giữ cỡ chữ, ảnh và chiều cao từng hàng hiện có.

Nguồn nền: v1.5.16 đã phát hành. Nhánh sửa `fix/workspace-size-match-wb-fbs`; mã ứng dụng chốt `f11a6af43d546478f76091379ce7cbda6c315682`, hash cây nguồn `1886169b4d35291bd461ab74c5857af97c4121b68ffb3c3215db80c76e960e2e`. Chưa tạo bộ cài hoặc thay Latest cho thay đổi này.

## Nguyên nhân và thay đổi

- Sản phẩm có lề trên 34 px, lề trái/phải 34/30 px và nhiều khoảng cách 14/16 px. Thanh lọc dạng FlowPane đẩy một số bộ lọc xuống dòng dù vẫn có thể thu vừa một hàng. Chuyển về lề 16 px như FBS WB, khoảng cách 8 px và HBox có thể co ô tìm kiếm/bộ lọc. Giữ chọn tất cả, danh mục, trạng thái giấy tờ, các chip và bảng sửa hàng loạt.
- Đăng ký Znack của cả WB và Ozon có lề trên 38 px và khoảng trống 45 px trước thanh lọc. Các nút bị ép vào kích thước rộng cố định nên có thể xuống dòng chữ. Bỏ khoảng trống này, dùng lề 16 px và để nút lấy kích thước theo nội dung. Ozon không giữ chỗ cho nhãn trạng thái trống. Bảng tiếp tục giãn theo chiều cao cửa sổ; giữ các báo cáo, hàng đợi và nút đăng ký.
- FBS Ozon trước đó lấy kiểu card của màn chi tiết lô hàng và dành dòng riêng cho hướng dẫn, nút chọn đơn, báo không có đơn và phân trang. Dùng bố cục trang như FBS WB, đưa chọn đơn vào cùng hàng tìm kiếm và phân trang lên thanh trên. Chọn đơn vẫn chỉ xuất hiện ở tab Chờ nhận như trước. Thông báo không có đơn nằm trong chính bảng; hướng dẫn ở tooltip tiêu đề/tab. Giữ bốn tab đơn, Kho KIZ, STT, barcode/GTIN, KN3, giá và các thao tác hiện có.

Các bảng dùng hết phần còn lại của vùng làm việc, rộng lên và cao lên khi mở rộng cửa sổ. Không sửa API, dữ liệu, luồng mua/gán KIZ, đăng ký Catalog, đồng bộ GTIN, row height hoặc font.

## Đo cùng điều kiện trước và sau

Vùng nội dung 1200×720 px, cùng JavaFX/Xvfb, theme và fixture chưa chọn cửa hàng. Đây là vùng làm việc bên trong app, chưa tính sidebar/header hệ thống của cửa sổ chính.

| Màn hình | Khung trước sửa | Khung sau sửa |
| --- | ---: | ---: |
| FBS WB — mẫu giữ nguyên | 1168×528 | 1168×528 |
| Sản phẩm | 1136×476 | 1168×524 |
| Đăng ký Znack WB | 1152×409 | 1168×520 |
| FBS Ozon | 1174×390 | 1168×526 |

Kiểm tra thêm với dữ liệu tổng hợp trên năm màn (cộng Znack Ozon), ở 940×650, 1200×720 và 1480×900: 15 lần đo. Ở 1200×720, bảng Sản phẩm 1168×553, Znack WB 1168×520, Znack Ozon 1168×504 và FBS Ozon 1168×526. Sản phẩm có thêm chỗ khi thông báo chọn cửa hàng không còn hiển thị. Khi tăng chiều cao từ 720 lên 900, mỗi bảng tăng thêm 180 px. Không khóa bảng ở một chiều cao nhỏ cố định.

## Kiểm chứng

- Hồi quy cuối: **165/165 ca đạt**, gồm toàn bộ 57 FXML, controller đăng ký WB/Ozon, FBS Ozon, cửa sổ/điều hướng, theme, bảng sửa sản phẩm và trạng thái GTIN.
- Thêm **11/11 ca** tương tác sửa/làm mới/chọn sản phẩm đạt; tổng 176 ca riêng biệt, không lỗi/thất bại/bỏ qua. Lượt 119 ca trước đó là tập con của 165, không cộng lại.
- `WorkspaceListAreaTest` so bảng với FBS WB thực qua `WorkspaceViewport`, kiểm tra đủ chiều rộng, chiều cao, biên cửa sổ và bảng tăng kích thước theo cửa sổ. Dữ liệu chỉ là fixture nội bộ.
- 16 trang FXML × bốn ngôn ngữ × hai theme × hai kích thước: 896 kiểm tra nút, không khuất. 10 trang Java và các tab Znack: 987 kiểm tra nút, không khuất.
- Kiểm tra chọn tất cả, sửa hàng loạt, chuyển tab và phân trang giữ hành vi hiện có. Các lỗi ban đầu khi dựng bài thử mới là import tên Order và Client ID WB phải là NULL trong fixture; đã sửa dữ liệu/bài thử, giữ nguyên cổng xác minh chủ cửa hàng.

[Biên nhận, ma trận và hash log](../validation/2026-10-25-workspace-list-area-verify.json).

## Ảnh giao diện sau sửa

Ảnh từ view JavaFX thật với dữ liệu tổng hợp, không phải tài khoản seller.

- [FBS WB đối chiếu](images/2026-10-25-workspace-list-area/wb-fbs.png)
- [Sản phẩm](images/2026-10-25-workspace-list-area/products.png)
- [Znack WB](images/2026-10-25-workspace-list-area/wb-registration.png)
- [Znack Ozon](images/2026-10-25-workspace-list-area/ozon-registration.png)
- [FBS Ozon](images/2026-10-25-workspace-list-area/ozon-fbs.png)

Latest vẫn là v1.5.16 bất biến; bản sửa giao diện này chưa nằm trong bộ cài đó. Chưa chạy lại full Java suite hoặc bộ cài/native Windows cho thay đổi này. Khi được yêu cầu đóng gói, tăng phiên bản từ nguồn sửa đã chốt; giữ dữ liệu và định danh cài đặt, không ghi đè tag/tài sản v1.5.16.
