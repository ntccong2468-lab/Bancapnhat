# VN code 1.6.0

## Giao diện

- Điều chỉnh bộ lọc và thanh thao tác ở Sản phẩm, Đăng ký Znack và FBS WB/Ozon để phù hợp hơn với cửa sổ hẹp; tăng không gian cho danh sách thẻ Znack.
- Tên doanh nghiệp, trạng thái và thông tin kết nối ở GS1 và Znack có thể xuống dòng; tên shipment dài có thể xem đầy đủ. Thanh bên thu gọn vẫn cho biết trạng thái license qua biểu tượng và nội dung hỗ trợ.
- Cải thiện độ tương phản của một số trạng thái, nút và viền khi điều hướng bằng bàn phím.
- Giảm xử lý lặp khi chọn sản phẩm, cuộn để tải thêm và xóa bộ lọc Znack; ảnh thu nhỏ có cùng nội dung được dùng lại trong giới hạn bộ nhớ cache. Danh sách FBS khôi phục các dòng sau khi bỏ lọc với ít lượt tìm kiếm lặp hơn.
- Ở Sản phẩm, ảnh đang hiển thị được giữ khi thay đổi lựa chọn; khi đang chờ kết quả tìm kiếm mới, các thao tác chọn và sửa tạm ngưng để tránh thao tác nhầm trên kết quả cũ. Nút thao tác Znack WB hiển thị đầy đủ nhãn qua tooltip khi bị rút gọn.

## License và khởi động

- Khi mở ứng dụng, license đã được xác minh chữ ký, thuộc đúng thiết bị và còn hiệu lực có thể được sử dụng ngay trong phạm vi thời hạn offline. Việc kiểm tra lại với server chạy ở nền; nếu server trả về quyết định từ chối hoặc thu hồi, ứng dụng cập nhật và áp dụng quyết định đó.
- Mỗi yêu cầu license có giới hạn thời gian; một lượt kiểm tra có thể gồm nhiều yêu cầu và thử tuyến kết nối. Trạng thái ngắn trên thanh điều hướng ghi «License hợp lệ» khi quyền còn hiệu lực; trang license vẫn cho biết trạng thái offline, thời hạn và lỗi kết nối khi có.
- Chẩn đoán mạng phân biệt kết nối đã hoàn tất bắt tay TLS với kết nối còn đang chờ. Thay đổi thời gian chờ và chẩn đoán không bảo đảm khắc phục được mọi lỗi mạng trên máy seller.

## Ozon FBS và PDF

- Tăng nhịp đồng bộ FBS theo cửa hàng đang xem, giữ giới hạn tốc độ và thời gian chờ của API. Danh sách chỉ được coi là đã quét đủ sau khi đọc xong tất cả các trang; các đơn chưa xác minh được báo để xử lý tiếp. Lịch làm mới không bảo đảm độ trễ bằng không.
- Khi nhận lô Ozon, tiếp tục các đơn đủ điều kiện nếu một đơn bị lỗi; giới hạn yêu cầu được xử lý theo thời gian chờ và kết quả không rõ được đối soát trước khi gửi lại. Tối đa 100.000 đơn mỗi lượt chọn; vượt giới hạn cần chia nhóm.
- Để tránh trộn dữ liệu của hai seller, không đổi Client ID Ozon sang seller khác trong cùng một cửa hàng; hãy thêm cửa hàng riêng. Vẫn có thể đổi API key cho cùng Client ID. Dữ liệu đã bị trộn trước bản sửa cần được kiểm tra riêng.
- Xuất nhãn Ozon cho các đơn đủ điều kiện và báo riêng các đơn chưa xuất được. Một lượt xuất tối đa 10.000 đơn; vượt giới hạn cần lọc thành nhiều lượt. Seller có thể chọn in nhóm đủ KIZ trước hoặc tiếp tục xác nhận mua KIZ cho nhóm thiếu.
- Kiểm tra danh tính KIZ/exemplar trước khi ghi PDF và xác nhận đã in. Nếu máy tắt đúng lúc ghi file và lưu cơ sở dữ liệu, KIZ vẫn được giữ cho đơn nhưng có thể phải đối soát do chưa có nhật ký phục hồi tự động.
- Giữ lựa chọn đơn khi chuyển trang; xử lý và báo kết quả riêng cho từng đơn trong lô. Ứng dụng giữ kết quả chưa rõ để đối soát, tránh gửi lặp và không tự mua KIZ khi thiếu bằng chứng.
- Hỗ trợ đề xuất bổ sung KIZ theo các đơn đã chọn và chờ seller xác nhận; đối chiếu dữ liệu trước khi gán và nhận đơn.
- Lấy nhãn Ozon theo lô tối đa 1.000 đơn, tối ưu xử lý PDF và hiển thị thời gian các công đoạn. Trang nhãn phải được xác định đúng đơn trước khi ghép; trường hợp mơ hồ được báo để xử lý.
- Trong khi nhận đơn hoặc xuất nhãn, có thể xem và chuyển tab; các thao tác làm thay đổi lô đang xử lý được khóa để bảo vệ dữ liệu.

## FBS Wildberries: đơn nợ

- Đồng bộ FBS WB giữ dữ liệu mới hơn khi phản hồi cũ đến muộn. Khi WB đã nhận lô đơn nhưng bước đọc lại thất bại, ứng dụng giữ đơn chờ đối soát và không gửi lại yêu cầu thêm đơn.
- Tab «Đơn nợ» hiển thị các shipment có đơn cần giao lại theo danh sách do API Wildberries trả về. Khi mở shipment, ứng dụng chỉ hiển thị các đơn trong danh sách này, cùng thông tin sản phẩm, trạng thái và nút lưu, mở PDF nhãn vận đơn cho từng đơn.
- Wildberries đưa đơn chưa được quét vào danh sách giao lại sau khoảng thời gian do sàn quy định; danh sách chưa có đơn không chứng minh mọi đơn vừa giao đã được quét. Nếu chưa đọc đủ thông tin hoặc trạng thái, ứng dụng vẫn giữ mã đơn và hiển thị cảnh báo. Đơn cũ được tìm trong dữ liệu đã lưu và kho lưu trữ WB trong giới hạn thời gian tra cứu.
- Các màu trạng thái là quy ước hiển thị trong VN Code theo dữ liệu WB trả về; API WB không cung cấp màu. Việc lấy nhãn vận đơn không tự mua hoặc gán KIZ.

## GTIN và thẻ Znack

- Trong đăng ký Znack của Ozon, bổ sung «Ghép GTIN từ WB»: đối chiếu từng biến thể WB/Ozon với thẻ Catalog đã công bố, hiển thị các cặp đề xuất và trường khác biệt để seller duyệt. Tên bài có thể khác giữa hai sàn; trường hợp mơ hồ không được tự chọn.
- Lưu liên kết đã duyệt để xem và sử dụng lại; trước khi ghi, ứng dụng đọc lại danh tính, quyền sở hữu và thông tin sản phẩm. Ghi GTIN theo lô qua hàng đợi Ozon và chỉ báo xác minh sau khi đọc lại; dữ liệu thay đổi hoặc kết quả chưa rõ được đưa vào báo cáo cần xử lý/đối soát. Đổi kết nối hoặc dữ liệu bài có thể yêu cầu duyệt lại.
- Có thể xem nhanh dữ liệu đã lưu hoặc đồng bộ đủ hai cửa hàng và Catalog trước khi ghép. Khi nguồn chưa đầy đủ, kết quả tự động chỉ mang tính gợi ý, cần kiểm tra trước khi áp dụng. Các lượt đọc và xác minh quyền theo từng biến thể có thể mất thời gian; chưa có phép đo hiệu năng với tài khoản/sàn thật.
- Đồng bộ Ozon có điểm tiếp tục đã lưu cho danh mục lớn và chỉ xác nhận mapping sau khi đọc thấy barcode trên Ozon. Nếu GTIN đã thuộc sản phẩm khác trong dữ liệu được xác minh, ứng dụng báo lý do và hỗ trợ chọn sản phẩm đăng ký thẻ mới.
- Với thao tác đăng ký lại từ báo cáo GTIN trùng trên Ozon, ứng dụng cần đối chiếu được chủ thẻ, GTIN, `good_id` và `feed_id` của thẻ Catalog cũ; nếu thiếu chứng cứ, seller cần đồng bộ hoặc đối chiếu trước. Ứng dụng kiểm tra lại xung đột trước khi xin GTIN mới và giữ liên kết cũ cho đến khi đọc lại được GTIN mới trên đúng sản phẩm Ozon.
- Với các sản phẩm WB đã chọn, ứng dụng kiểm tra giá theo size, tính giá trung bình khi cần và xác nhận cập nhật giá trước khi gửi GTIN. Giá từng size có thể tăng hoặc giảm. Lượt đọc giá được gom tối đa 1.000 mã sản phẩm và kiểm tra lại trước khi gửi GTIN; ứng dụng xử lý giới hạn API và chặn gửi khi token không đủ điều kiện xác minh giá mới an toàn.
- Trạng thái Znack phân biệt thẻ nháp bị từ chối và thẻ chưa ký, giữ chi tiết lỗi để seller xem và xử lý. Khi không đọc được lịch sử lỗi, ứng dụng báo lỗi thay vì hiển thị đang tải vô thời hạn.
- WB và Ozon dùng chung một phần xử lý gửi và theo dõi kết quả Catalog, đồng thời vẫn giữ trạng thái và bộ cập nhật GTIN riêng cho từng sàn. Ứng dụng lưu các yêu cầu đã gửi hoặc chưa rõ kết quả để đối soát, tránh tự gửi lại khi chưa xác định được kết quả.
- Trước khi đăng ký, seller xem và xác nhận dữ liệu theo từng sản phẩm; thông tin lấy từ sàn không tự được coi là đã xác minh. Nếu sản phẩm, danh mục Catalog hoặc kết nối đã thay đổi, ứng dụng có thể yêu cầu xem lại. Việc xác nhận đăng ký hoặc sửa không thay thế quyền ký thẻ hay cập nhật GTIN lên sàn.
- Seller có thể cho phép tự sửa tối đa hai lượt cho các lỗi được hỗ trợ, sau khi ứng dụng xác minh thẻ bị từ chối, chủ sở hữu, GTIN và mã thẻ Catalog. Các sửa đổi có căn cứ, như thuộc tính bộ hàng hoặc chuẩn hóa một số giá trị theo lỗi và danh mục hiện tại, được lưu vào lịch sử. Những lỗi thiếu bằng chứng hoặc chưa có quy tắc vẫn cần seller xử lý; ứng dụng chưa tự điền được mọi trường thiếu bằng dữ liệu đã xác minh.
- Cơ chế chống trùng cho việc cấp GTIN và gửi hoặc ký thẻ được phối hợp giữa WB và Ozon trong cùng cơ sở dữ liệu của app. Trường hợp kết quả chưa rõ, dữ liệu cũ thiếu căn cứ hoặc không xác minh được chủ thẻ vẫn cần đối soát; cơ chế này không chứng minh chống trùng giữa các máy dùng cơ sở dữ liệu riêng.
- Việc xin GTIN cho Ozon trong môi trường sandbox hiện bị chặn vì đường xác thực chưa được xác minh. Trên thẻ mới, ảnh tùy chọn chưa được seller duyệt không được tự gửi; nếu danh mục bắt buộc thông tin chưa được xác minh, ứng dụng yêu cầu xử lý trước.

## Phạm vi xác minh

- Các luồng GTIN, cập nhật giá và Catalog được kiểm thử bằng dữ liệu và API mô phỏng. Các bài đo PDF cũng dùng Ozon mô phỏng, không bao gồm thời gian mạng Ozon, ảnh hoặc KIZ thật; không phải cam kết thời gian xử lý trên máy seller.
- Trong một lượt thử mô phỏng sau khi loại lượt đọc giá khỏi bước chứng minh GTIN, 10.000 GTIN Ozon được đối chiếu thành công sau khoảng 11 phút 58 giây xử lý; lượt thử có 60.000 lần đọc chứng từ và không đọc giá cho bước chứng minh. Việc đọc chứng từ theo từng sản phẩm vẫn có thể tốn thời gian và yêu cầu API; kết quả không bao gồm độ trễ hoặc giới hạn của sàn thật.
- Chưa nghiệm thu ghi giá hoặc GTIN thật, đăng ký và ký thẻ Catalog thật, mua KIZ có phí, gán KIZ hoặc nhận đơn thật trong lượt kiểm tra này. Các kết quả không rõ, giới hạn API và trường hợp thiếu dữ liệu vẫn cần được đối soát trước khi xử lý tiếp.
- License còn hiệu lực offline có thể được dùng trong lúc kiểm tra nền; quyết định thu hồi từ server chỉ có hiệu lực trên app sau khi app nhận được quyết định đó hoặc quyền đã ký hết hạn.
