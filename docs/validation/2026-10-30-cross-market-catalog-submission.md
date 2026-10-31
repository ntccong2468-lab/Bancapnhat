# Giới hạn chống gửi trùng Catalog xuyên sàn

Việc cấp GTIN mới đã dùng chung journal theo chủ Catalog và môi trường. Hai sản phẩm WB/Ozon có thể đã cùng liên kết tới một GTIN có sẵn; guard mới đối chiếu checkpoint hai sàn theo GTIN/chủ/môi trường và ghi `FEED_SUBMITTING` hoặc dấu ký `SENDING` trong một giao dịch SQLite trước khi gửi/ký. Hai tiến trình ký và sửa không thể cùng giành quyền trên cùng thẻ. Dữ liệu native và lịch sử cũ được giữ nguyên. Kết quả trong tài liệu này là kiểm thử giả lập, chưa chứng minh vận hành bằng tài khoản thật.

Không thể giải quyết an toàn bằng cách khóa vĩnh viễn theo GTIN: seller có thể cần sửa lại chính thẻ đó sau khi Catalog từ chối hoặc liên kết thẻ đã có. Cũng không được tự xóa khóa chỉ vì lần đọc lỗi, một yêu cầu hết thời gian, hoặc một hàng cũ mang trạng thái `ERROR`; các trạng thái này không tự chứng minh feed đã bị từ chối.

Các điều kiện của guard:

1. Nhận diện chung bằng participant Catalog, môi trường và GTIN chuẩn hóa; giữ nguyên định danh sản phẩm WB/Ozon, chủ cửa hàng và chữ ký riêng để xác minh quyền.
2. Trong một giao dịch SQLite có khóa ghi, kiểm tra checkpoint của cả hai sàn rồi ghi dấu `SENDING` cho một yêu cầu đã được duyệt. Chuyển đổi checkpoint cũ phải giữ được các trường `feed_id`, `good_id`, GTIN và mọi lần gửi có kết quả chưa xác định.
3. Ngăn một yêu cầu thứ hai khi yêu cầu trước đang gửi, đã nhận feed nhưng chưa xác minh, hoặc có kết quả chưa rõ. Khởi động lại hay xóa phiên chạy không tự làm mất dấu mốc này.
4. Cho phép sửa khi có readback mới chứng minh đúng chủ, GTIN, `good_id` và kết quả feed liên quan. Phải so lại dữ liệu và phiên bản bản ghi trong giao dịch trước khi gửi, vì sản phẩm hoặc trạng thái Catalog có thể thay đổi sau lần đọc.
5. Kiểm thử ít nhất hai tiến trình, tắt app trước/sau ranh giới gửi, mất ACK, phản hồi 429/5xx, dữ liệu legacy thiếu scope, đổi chủ/chứng thư, hai sàn cùng liên kết một GTIN và sửa thẻ thật đã bị từ chối. Không xác nhận gửi thành công nếu chưa đọc lại Catalog.

Khi một feed cũ thuộc sản phẩm khác cần được chứng minh đã kết thúc trước lượt sửa, guard yêu cầu feed-status terminal chứa đúng `feed_id`, GTIN và `good_id`, rồi đọc lại đúng thẻ. Nếu thẻ không có `owner_inn`/`participant_inn`, ứng dụng có thể đối chiếu `good_id` bằng lượt đọc mới `/v3/etagslist` lọc theo INN của chứng thư, sau đó đọc lại thẻ và đối chiếu dữ liệu. Không dùng cache hai giờ và không suy `producer_inn` là chủ sở hữu; proof được giới hạn thời gian từ lúc trang chứa `good_id` trả về. Feed 404, thiếu ID, owner sai, quét chưa đủ hoặc dữ liệu thay đổi đều chặn. Nhiều feed liên quan cùng thẻ được đọc trước, rồi chỉ quét owner một lần cho lượt đó. Lần đọc thật `/v3/feed-product` trong môi trường không có `owner_inn`/`participant_inn`; fallback owner mới được kiểm thử giả lập, chưa được nghiệm thu bằng thao tác sửa thật. Với catalog lớn, lượt quét này có thể chậm hoặc cần đối soát nếu mạng/giới hạn yêu cầu cản trở.
