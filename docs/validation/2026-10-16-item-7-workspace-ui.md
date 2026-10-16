# Mục 7 — giao diện chung và cập nhật FBS

Đã triển khai theo [sơ đồ được duyệt](../architecture/2026-10-16-item-7-workspace-ui-proposed-flow.md),
sau khi hoàn tất mục 3 tại `0d0b41b`. Chưa phát hành bộ cài/Latest.
Hồi quy toàn bộ được chốt riêng sau commit mã nguồn mục này.

## Thay đổi

- Header giữ cửa hàng, sức khỏe FBS, đồng bộ, tác vụ và nút cập nhật.
  Các thao tác phụ, số dư, sửa/xóa shop, tin tức và hỗ trợ vào menu khi
  không đủ chỗ. Tái sử dụng đúng các control/callback hiện có; ô cửa hàng
  hiện nhãn sàn/tên, không dùng chuỗi debug `Shop{...}`.
- Sidebar tự thu gọn theo cửa sổ, có nút ghim mở/thu và menu trở về tự động;
  icon có tooltip/tên truy cập. Badge GS1 đổi không làm bung tên ở chế độ
  icon. Chọn trang và điều kiện truy cập cũ giữ nguyên.
- Khung `app-page` áp dụng cho mọi route, 16 màn FXML nghiệp vụ và các
  pane dựng bằng Java: cỡ chữ, khoảng cách, nút, bảng, tab, thẻ và màu trạng
  thái theo cùng theme. Tên mục Tổng quan/liên kết KIZ được bản địa hóa.
- Bảng FBS WB/Ozon, shipment WB và danh sách sản phẩm cập nhật theo key;
  dữ liệu không đổi không xóa/nạp lại cả bảng. Giữ chọn/lọc/cuộn. WB có
  ảnh chụp giá trị đã render để phát hiện cả model bị sửa tại chỗ; Ozon
  theo dõi cả thay đổi dữ liệu sản phẩm/GTIN dù bản thân đơn không đổi.
- Callback ảnh Ozon kiểm tra chủ shop, ô ảnh và đơn hiện tại; không bỏ ảnh
  đang tải chỉ vì một lượt đồng bộ không đổi đã xảy ra. Số lượng 1 không
  lặp thêm badge trên ảnh. Nguồn mua/nhập và điều kiện API không thay đổi.
- Trạng thái FBS tách đang chạy, mới xong một phần, đã hoàn tất, quá hạn
  kiểm tra và lỗi; tooltip có nguồn, lần hoàn tất, lần kiểm tra tiếp và mã
  lỗi. Không dùng một request thành công làm bằng chứng cả luồng xong.
- Dialog giới hạn theo cửa sổ chủ trước khi mở, giữ nội dung và vùng nút.
  Màn xem trước GTIN nội bộ có phần chi tiết thu gọn/vùng cuộn và footer
  luôn tiếp cận được; không đưa công cụ GTIN độc lập trở lại sidebar.
- Tác vụ có bộ lọc trạng thái/tên bước dễ đọc, tên in FBS Ozon và thời gian
  Moscow. Danh sách tác vụ không dựng lại các hàng không đổi. Thư GS1
  cuộn tới tin mới sau khi skin hoàn tất layout, có giới hạn số pulse và
  dừng timer khi đóng pane.

## Kiến trúc và đối chiếu

Giữ `SyncRuntime`, `SyncCoordinator`, executor/quota/Retry-After, journal
và cache ảnh hiện có. Không thêm server, vòng polling theo view, framework
hoặc pool mới. Bộ nhớ bổ sung chỉ là ảnh chụp giá trị của trang đang render;
đổi shop xóa snapshot đó. Thêm index category/type cho catalog Ozon để
refresh danh mục kho không quét lại toàn bảng cho từng nhóm.

FBS tiếp tục polling nền khi đổi trang. Nhịp đang có là queue 10/45 giây,
trạng thái 15/60 giây, nội dung shipment 10/45 giây ở shop đang chọn/shop
khác; vẫn theo hạn mức API. Đây không phải push tức thời. Phép đo UI dưới
đây bắt đầu sau lúc dữ liệu cục bộ đã được ghi, không đo thời gian sàn cập
nhật hoặc thời gian chờ quota/mạng.

Đối chiếu dùng bằng chứng trong sơ đồ: WBCode Latest 1.3.9, ảnh chính thức
1.3.8 và ảnh người dùng. Mã nội bộ/luồng đã đăng nhập của 1.3.9 không công
khai; không tuyên bố sao chép hoặc chạy phần đó.

## Kiểm chứng cục bộ trước hồi quy toàn bộ

- 16 FXML × 4 ngôn ngữ × 2 theme × 2 vùng nội dung: 960 lượt kiểm tra
  control; không có control cố định bị cắt hoặc control trong vùng cuộn
  không có cuộn ngang bị tràn ngang.
- 9 pane Java và các tab cấu hình Znack: 864 lượt kiểm tra control; qua.
- Khung chung ở cửa sổ 1024×600, 1366×768, 1600×900, 4 ngôn ngữ và 2
  theme; kiểm tra menu overflow, nút cập nhật, manual/auto sidebar và dialog.
- Lượt smoke cuối 64/64 qua; kiểm thử riêng ảnh chậm và màu sản phẩm mới
  được xác nhận trên cell thật. Các lượt tập trung có bài trùng nhau,
  không cộng chúng thành tổng số bài riêng.
- Tải tổng hợp 10 shop, 10.000 đơn, 100.000 sản phẩm; mỗi lần ghi đổi 9.001
  dòng, trang đang mở 100 đơn. 20 mẫu: p95 commit → hiển thị khoảng 93 ms,
  p95 đợi JavaFX khoảng 6,5 ms; không ghi nhận khoảng đứng >500 ms.
  Đây là máy cloud 4 logical CPU, JavaFX software rendering, không phải
  máy Windows yếu hoặc ảnh/API thật. Lượt hồi quy cuối ghi kết quả mới riêng.

Các lỗi test ban đầu được xử lý bằng sửa đúng nguyên nhân: kỳ vọng Ozon
cũ chỉ có ba tab, fixture dialog thiếu thư mục dữ liệu, và thư GS1 đặt vị trí
cuộn trước khi skin ổn định. Không tắt assertion hay bỏ bài kiểm thử.

## Giới hạn

Chưa thử DPI/driver/in trên Windows, CryptoPro native, API mua/gán KIZ thật
hoặc seller đồng thời trên máy riêng. Các phần đó cần kiểm chứng khi có
môi trường thật. Mô hình vẫn là mỗi seller chạy VNCode trên máy của mình;
website dùng chung cho đăng ký/license. Không triển khai dịch vụ cloud mới.

![Cửa sổ 1366; dữ liệu tổng hợp](images/2026-10-16-item7-workspace-1366.png)

![Cửa sổ 1024; dữ liệu tổng hợp](images/2026-10-16-item7-workspace-1024.png)
