# Nhãn Ozon, phiếu nhặt hàng và báo cáo phạt — 04.11.2026

Nhánh `fix/ozon-labels-finance-20261104`, dựa trên mã sau phát hành 1.6.1 (`62e440a`). Đây là bản sửa nguồn chưa đóng gói/phát hành; Latest 1.6.1 không bị thay thế.

## Yêu cầu đã chốt

- Nhãn vận đơn giữ nguyên nội dung, kích thước, barcode và ô số do **Ozon trả về**. Không thêm ô mã đơn, không thay số trên nhãn. Yêu cầu trung gian tự tạo ô `4801` đã được người dùng thay bằng yêu cầu giữ nhãn gốc 100%.
- PDF in giữ nhãn vận đơn và nhãn kết hợp KIZ/barcode sản phẩm; loại trang barcode sản phẩm Ozon đứng riêng, kể cả khổ lớn. Lấy nhãn và nhận đơn vẫn theo luồng lô hiện tại.
- Phiếu nhặt hàng có cột **Số nhãn vận đơn**, lấy đúng số từ nhãn của từng đơn, bốn số cuối trắng trên nền đen như WB. Đây là cách trình bày trên phiếu nhặt hàng, không sửa nhãn vận đơn.
- Chuyển báo cáo lý do phạt từ Tổng quan sang **Công cụ → Báo cáo tài chính → Lý do phạt**.

## Sửa nguồn

1. Lọc trong chính PDF đã tải theo lô, không chuyển thành tải từng đơn. Chỉ loại trang được nhận diện là barcode sản phẩm: so sánh mẫu Code 128 dạng vector để xử lý nhanh; trường hợp khác dùng bộ đọc ảnh có giới hạn bộ nhớ. Mã vận đơn, QR/DataMatrix, trang chưa nhận diện được đều được giữ để tránh mất nhãn. Không suy loại trang theo chẵn/lẻ hoặc thứ tự yêu cầu.
2. Giữ nguyên stream nội dung và kích thước trang vận đơn. Renderer nhãn kết hợp không thay đổi; chỉ KIZ đã xác minh binding mới được in/trừ kho. Thiếu KIZ không làm mất nhãn vận đơn sàn đã cấp.
3. Bổ sung trường API **`scanit`** vào DTO và cache `ozon_posting_scanit.v1`, tách biệt `tracking_number`. Cache ràng buộc shop + fingerprint quyền API, giữ tương thích dữ liệu cũ, không thêm schema hay xóa dữ liệu. Kết quả sync cũ đến muộn không ghi đè quan sát mới.
4. Đọc số hiển thị từ PDF nhãn và ghép với đúng posting bằng `scanit`/danh tính xác minh. Hỗ trợ số có bốn ký tự cuối ở text run riêng trên nền đen. Không ghép theo thứ tự trang và không dùng GTIN/barcode sản phẩm thay số vận đơn. Nếu chưa xác minh được, phiếu ghi rõ chưa đọc được số; nhãn vận đơn vẫn được giữ.
5. Báo cáo phạt giữ bộ lọc ngày, phân trang, ngôn ngữ, theme và phạm vi chủ cửa hàng; dừng polling khi đổi tab hoặc rời màn hình. Thống kê Tổng quan và phần tổng hợp tài chính vẫn còn. Khả năng nguồn báo cáo giữ nguyên: WB được kiểm chứng; nguồn loại giao dịch phạt Ozon vẫn báo chưa hỗ trợ như trước, không tự gán nghĩa cho phí.

## Kiểm chứng

- Xem [biên nhận kiểm thử](verification.json) để biết số ca và các tập chạy. Kiểm tra JavaFX bằng Xvfb, hai theme, bốn ngôn ngữ, ba kích thước cửa sổ; kiểm tra route thật trong Home và đổi shop/khóa.
- Lô giả lập 100/1.000 nhãn: một task create + một status read + một tải PDF; không có task từng đơn. Lô hỗn hợp 100 vận đơn + 100 barcode sản phẩm: đầu ra đủ 100 vận đơn, mọi stream nội dung và khổ giấy vận đơn giống nguồn. Nhãn kết hợp được kiểm tra riêng cả đọc lại barcode và DataMatrix.
- [API đọc thật](live-readonly.json): `/v3/posting/fbs/get` và `/v4/posting/fbs/list` đều trả `scanit` khớp số trong một PDF Ozon thật đã tải trước đó. Không nhận đơn, tạo task nhãn, mua/gán KIZ hay kích hoạt license trong lượt này.
- Chạy trực tiếp bộ đọc mới với PDF thật và `scanit` từ API: nhận diện đúng posting, số trích xuất bằng số in trên nhãn. Dữ liệu/API/PDF thật nằm ngoài Git; chỉ lưu kết luận không chứa mã đơn, khóa hoặc KIZ.
- Các số thời gian trong biên nhận là **giả lập trên máy kiểm thử**, không phải tốc độ API Ozon/máy seller. Chưa chạy bộ cài Windows hoặc phát hành các thay đổi này.

## Ảnh và mẫu

- [PDF nhặt hàng, dữ liệu tổng hợp](picking-synthetic.pdf) · [Ảnh](picking-synthetic.png)
- [Báo cáo tài chính, nền tối](finance-dark.png) · [nền sáng](finance-light.png)

## Bàn giao

- Không dùng `tracking_number`, `lower_barcode` hay số đơn để thay cho số in trên nhãn khi `scanit` là mã Ozon thực tế. Ví dụ đã kiểm chứng: tracking rỗng, lower/upper là số dạng khác; `scanit` mới khớp nhãn.
- Không đưa việc đọc từng nhãn từ mạng trở lại khi cần ghép phiếu nhặt hàng. Đọc metadata/cache theo lô, kiểm chứng từ PDF đã tải; giữ task receipt và cơ chế chống tạo lại task sau timeout chưa rõ kết quả.
- Tách yêu cầu của phiếu nhặt hàng khỏi nhãn vận đơn: phiếu có thể tô đậm số cuối, còn nhãn vận đơn phải giữ nguyên Ozon.
- Nếu được yêu cầu phát hành tiếp: tạo phiên bản mới từ nhánh này, chạy kiểm chứng Windows/upgrade và quy trình publisher. Không thay byte asset của 1.6.1 đã công bố.
