# Ozon — nhận đơn, thứ tự phiếu nhặt hàng và mẫu giao diện

Ngày 04.11.2026; nhánh `fix/ozon-accept-picking-preview-20261104`, nền `b460bb3` sau Latest 1.6.2; mã sửa và bằng chứng API ở commit `ccd7fc1`. Người dùng cho phép nhận đơn thật bằng API và thử file KIZ đã gửi; đồng thời yêu cầu xem ảnh và duyệt trước khi thay giao diện. **Chưa tạo bộ cài hoặc phát hành các sửa dưới đây.**

## Kết quả thật

- Nhận và đọc lại đúng **22/22 đơn**: 19 đơn ban đầu và 3 đơn mới đến trong lượt làm việc; trạng thái xác nhận `awaiting_deliver`. Tổng cộng 22 yêu cầu ship, không gửi lặp để vượt trạng thái chưa rõ. Lượt quét cuối lúc **18:00:05 UTC**, cửa sổ 90 ngày, 26 trang/2.522 posting duy nhất, không còn `awaiting_packaging` hoặc `awaiting_approve`. Đây là snapshot tại thời điểm kiểm tra, không bao gồm đơn đến sau đó.
- File KIZ có 200 dòng duy nhất, mỗi dòng 31 ký tự, không chứa phần mật mã. Một mẫu validate thật trả HTTP 200 nhưng exemplar bị từ chối: `MANDATORY_MARK_HAS_INCORRECT_CRYPTOTAIL` và `MANDATORY_MARK_IN_INCORRECT_STATUS`. Không gán/tiêu mã này. Các đơn nhận thành công có yêu cầu KIZ bắt buộc rỗng; các trường hợp tùy chọn có hành động API `skip_possible_info`. Thành công nhận đơn không chứng minh file KIZ được chấp nhận. [Hướng dẫn định dạng](../research/2026-11-04-ozon-kiz-input.md).
- Xuất thật **19 đơn trong một task nhãn v3**, không tạo task từng đơn. Phiếu nhặt hàng có đủ 19 đơn, đúng thứ tự nhóm của WB và đúng số vận đơn Ozon trên từng dòng. PDF đầu có thêm một trang barcode sản phẩm EAN-13; sau sửa bộ nhận diện, còn **19 trang vận đơn** và hai trang phiếu nhặt hàng. Đối chiếu content stream/kích thước xác nhận 19 trang vận đơn giữ nguyên.
- Xuất lại đúng task nhãn có sẵn sau sửa mất **8.009 giây**, 19 thành công/0 lỗi, không có API mutation mới. Đây là thời gian xuất lại lô 19 trong môi trường kiểm thử, không phải lần tạo task đầu, không phải benchmark 100/1.000 đơn hoặc máy Windows seller. Runner đầu đã xuất xong PDF nhưng lỗi serialize `java.io.File` khi ghi báo cáo; kết quả được đối soát riêng từ PDF và journal, không gửi lại task vì lỗi của runner.

[Biên nhận đã loại dữ liệu nhạy cảm](2026-11-04-ozon-accept-picking-verified.json) ghi số lượng, lỗi validate, hash mã nguồn sửa và tập kiểm thử.

## Lỗi và thay đổi mã

1. Ozon nhận ship nhưng trả trạng thái cũ ở lần đọc ngay sau đó. Hai đơn đầu được app coi là đang chờ đối soát; lần đọc tiếp theo xác nhận đã nhận. Thêm bước đối soát chung sau khi gửi hết tập chọn: tối đa ba lượt chờ 1/2/4 giây cho cả lô, tối đa hai lượt đọc đồng thời. Chỉ đọc receipt đã có; không ship lại, không xin/gán KIZ mới. Chưa rõ thì giữ journal cho đồng bộ nền; hủy vẫn giữ phần đã gửi.
2. PDF nhặt hàng chưa sắp xếp các dòng sản phẩm sau khi tách nhiều SKU trong một đơn. Dùng comparator chung theo cấu hình WB đang lưu: sản phẩm/danh mục → article → màu → size → mã đơn, so sánh số tự nhiên và để giá trị thiếu sau. Số vận đơn theo đúng posting đi cùng dòng; không thay thứ tự/nội dung nhãn chính thức.
3. Một sản phẩm có cả barcode OZN và EAN-13 nhưng Ozon in nhãn sản phẩm bằng EAN-13. Bộ lọc cũ chỉ chọn một barcode ưu tiên. Nhận diện nay dùng toàn bộ barcode đã đăng ký của **đúng SKU/product/offer**, hỗ trợ EAN-13; `scanit`, mã vận chuyển và QR/DataMatrix bảo vệ trang vận đơn. Trang chưa nhận diện vẫn được giữ. Chọn barcode cho nhãn kết hợp giữ quy tắc cũ, không tự lấy một mã mơ hồ.

Không thay schema/dependency, cấu hình license, portal, thiết lập quyền hoặc release đã phát hành. `OzonDashboardController` chỉ đổi xử lý receipt, không đổi bố cục/style.

## Kiểm thử

- **105 ca riêng biệt** thuộc tám lớp liên quan đều đạt, 0 thất bại/lỗi/bỏ qua; tổng hợp XML thành công mới nhất của hai lượt kiểm thử có phần giao nhau, không cộng trùng. Bao gồm receipt bất đồng bộ, không gửi lại khi chưa rõ, không ship khi thiếu receipt, hủy, sort/preference/multi-SKU, ảnh PDF, nhãn EAN-13 và giữ nguyên trang vận chuyển.
- Một lượt 95 ca trước sửa EAN, một lượt 67 ca sau sửa EAN. Không chạy lại full Windows/native/installer, không dùng các kết quả này thay gate phát hành.
- API thật gọi qua service production trong runner Java với DB/profile riêng và authority license test. Không kích hoạt license thật; không tuyên bố đã thao tác trên app Windows đang cài của seller.

## Giao diện chỉ là bản xem trước

TASTE và Impeccable đã dựng, chụp và review [mẫu sáng/tối](../design/previews/2026-11-04/README.md): bỏ ba KPI và khoảng trống Tổng quan; quản lý/thêm/sửa và thùng rác nhỏ cạnh cửa hàng; danh sách chiếm vùng làm việc; giữ các chức năng trong navigation/menu. Bảy yêu cầu sửa từ reviewer đã xử lý; disposition `ship` chỉ có nghĩa mẫu sẵn sàng để người dùng duyệt.

Không thay FXML/CSS hoặc giao diện production trước khi người dùng đồng ý ảnh. Các giá trị/ảnh sản phẩm trong mẫu là minh họa, không có dữ liệu khách hàng hoặc mã KIZ. Khi được duyệt mới chuyển sang JavaFX và kiểm tra focus, phóng đại, đa ngôn ngữ, danh sách ảo và handler cũ.

## Tiếp tục an toàn

- Latest hiện vẫn là 1.6.2. Không thay asset/tag cũ; chỉ đóng gói bản mới khi có yêu cầu và hoàn thành kiểm thử phát hành.
- Private API/PDF/journal/DB nằm trong `/tmp/vncode-ozon-accept-20261104`, không commit; đây không phải kho lưu bền sau khi môi trường kết thúc. Đọc receipt và trạng thái API trước bất kỳ giao dịch mới nào; không chạy lại runner nhận đơn để tái hiện báo cáo.
- Định dạng KIZ cần dữ liệu gốc đầy đủ; không thể khôi phục crypto từ CIS 31 ký tự. Khi có mã đầy đủ hợp lệ mới validate trước khi gán. Không tự mua/xin mã hoặc kích hoạt license.
- Cửa sổ quét và tính bất đồng bộ phải được báo rõ; API vẫn quyết định điều kiện nhận/cấp nhãn. Không hứa bỏ qua từ chối nghiệp vụ của sàn.
