# VN Code 1.6.11

Bản cập nhật kế thừa 1.6.10 và gom các chỉnh sửa mua KIZ, đăng ký Znack Ozon, FBS Ozon và công cụ kiểm mã; bổ sung các phần còn thiếu qua đối chiếu toàn bộ hướng dẫn WBCode.

- **Mua KIZ chung WB/Ozon/FBO:** thay engine theo luồng WBCode 1.3.26; gom tối đa 10 GTIN cùng hồ sơ, nhóm hàng và phương thức; ba hàng đợi tạo đơn/tải mã/lưu thông 4/2/2, giữ thứ tự theo GTIN.
- **Mua và lưu thông rõ trạng thái:** kiểm giấy tờ local trước mua, đối soát khi mất phản hồi, xử lý quota/giới hạn tốc độ và lỗi phiên có giới hạn. Tải mã một phần được phục hồi đúng lần cấp; chỉ xác nhận đã lưu thông khi trạng thái từng mã đã được kiểm chứng.
- **Khôi phục an toàn:** giữ hồ sơ, đơn, số lượng và ý định đã duyệt qua khởi động lại; tách phiên theo OMS/chứng thư. Giữ pause/hủy; tự kiểm tra lại trường hợp USB chữ ký mất kết nối tạm thời.
- **Znack và KIZ theo đơn Ozon:** mở đăng ký từ sidebar/FBS; mua đúng phần thiếu, thay phần mã bị từ chối theo đúng đơn vị. Chỉ coi mã mua đã gán khi Ozon xác nhận đúng mã/đơn/sản phẩm/vị trí. Giữ quy tắc mã seller nhập, nhãn vận đơn gốc theo lô và nhãn kết hợp.
- **FBS Ozon theo bố cục WB đã duyệt:** vùng danh sách rộng, giữ năm tab, lựa chọn nhiều trang, nhận/in/tìm kiếm và chi tiết mã; giữ nền sáng/tối, kính và chuyển động hiện có. Thanh lọc tự tăng chiều cao khi xuống dòng ở cửa sổ nhỏ để phân trang không đè bảng.
- **GS1 trong app:** hồ sơ gia nhập/đăng ký lại sáu bước, danh mục kiểm tra chính thức, lý do bị từ chối rõ ràng và hướng dẫn từ lỗi đăng ký Znack sang hồ sơ hoặc xin tiền tố.
- **Thỏa thuận GS1:** tải và xem đúng PDF trước khi đồng ý/ký, theo dõi xác nhận và đối soát khi chưa rõ kết quả; không tự gửi lặp yêu cầu. Có mẫu đơn tiền tố mặc định, giữ mẫu tùy chỉnh đã nhập và trạng thái gửi thư rõ ràng.
- **Sản phẩm:** lọc nhiều danh mục, giữ năm thao tác chỉnh sửa, tìm kiếm và lựa chọn nhiều trang.
- **Ozon — In KIZ:** xuất riêng mã đã gán với thứ tự và mã đơn khi nhãn vận đơn bị giới hạn; luồng vận đơn gốc cùng nhãn kết hợp vẫn được giữ. Sửa trạng thái bận khi đổi cửa hàng trong lúc xuất.
- **Giữ dữ liệu khi nâng cấp:** schema 6→7 hỗ trợ nhiều GTIN trong cùng đơn mua SUZ; snapshot trước migration, giữ ID, CIS, chứng từ, mã đã dùng và hồ sơ gốc. Bản cũ không được mở trực tiếp database đã nâng cấp; cần dùng snapshot tương thích khi khôi phục.
- **Công cụ và chẩn đoán:** tích hợp các hướng dẫn ECC phù hợp Codex Web, validator, bộ nhớ và hồ sơ kiểm chứng; sửa thông báo rỗng Znack/GTIN, lỗi đọc đơn mua cũ và một số lỗi trạng thái/phiên phát hiện khi kiểm tích hợp.

Giữ các kiểm tra đúng biến thể, chủ sở hữu, toàn bộ CIS và điều kiện chấp nhận KIZ của Ozon. Kiểm thử tổng hợp/native không thay thế mua SUZ có phí, ký bằng chứng thư hoặc giao dịch seller thật. Không gọi tải xong mã là đã lưu thông. Bộ cài Windows chưa có chữ ký Authenticode.

Chi tiết mọi nhóm sửa và các giới hạn nghiệm thu: `docs/validation/wb1326-purchase-replacement-20261117/REPORT.md` trong gói nguồn.

Đối chiếu 19 hướng dẫn, phần sửa GS1/Ozon/Sản phẩm và phạm vi kiểm chứng: `docs/validation/wbcode-guides-gs1-20261118/REPORT.md` trong gói nguồn. Việc gửi GS1/Catalog, ký chứng thư và thư SMTP thật vẫn cần tài khoản/cấu hình hợp lệ của seller.
