# Giữ dữ liệu đồng bộ khi cập nhật và mở lại VNCode

Yêu cầu: dữ liệu đã đồng bộ phải được lưu, tránh phải đồng bộ lại từ đầu sau mỗi bản cập nhật. Nhánh `fix/persist-synchronized-data`, nền `4b25e8a9b2e58c238f1e964e15ef5985a329178e`. Nhánh kế thừa sửa diện tích bảng theo FBS WB và sửa GTIN/KIZ/PDF của cả WB/Ozon; các thay đổi này chưa có bộ cài sau v1.5.16.

Mã ứng dụng chốt `b82f1abfed8841e6aaaa95541d1962c11279f0ba`, hash cây nguồn `e79d483fab1637282a19047a0579dc9d7ce4994975d8ce86acdca3d0fd75c341`.

## Nguyên nhân và thay đổi

Dữ liệu ứng dụng vốn nằm trong cơ sở dữ liệu ngoài thư mục cài đặt: Windows dùng `%LOCALAPPDATA%\VNcodeData\database.db`, không có số phiên bản trong đường dẫn. Giữ định danh, thư mục dữ liệu, cơ chế khóa, sao lưu và migration này. Không tạo một database mới theo phiên bản hoặc xóa database để làm mới.

Vấn đề được phát hiện ở cách dùng dữ liệu đã lưu:

- Trang Đăng ký Znack chỉ tải danh sách khi lượt đọc đủ hai nguồn còn mới và thành công. Một lần đồng bộ mới lỗi hoặc kết quả quá 24 giờ làm lần mở màn hình mới không hiện danh sách cũ; cách giữ các hàng đang có trong RAM trước đó không giúp khi đóng/mở app.
- Ozon ở đường tổng quan dự phòng gọi quét toàn bộ sản phẩm từ đầu. Trạng thái đồng bộ có cursor nhưng chưa có dấu mốc hoàn tất riêng, ghi bền vững cùng trang dữ liệu.

Sửa cho cả WB/Ozon:

1. `RegistrationSources.display/requireDisplay` đọc lại thành viên của lần đồng bộ **thành công**, đúng cửa hàng, credential và phạm vi Catalog. Dữ liệu hiển thị không mất chỉ vì đã quá 24 giờ hoặc lần làm mới sau đó lỗi/dở dang. Không dùng danh sách của lượt đọc một phần để thay toàn bộ kết quả tốt trước đó.
2. `RegistrationCardIndex.forDisplay` dựng lại trạng thái thẻ đã quan sát từ SQLite. Hai màn Znack tải/lọc/phân trang trực tiếp dữ liệu này sau khi tạo controller mới. Ozon kiểm tra tới đủ 1.000 sản phẩm đã lưu qua bốn trang; WB vẫn lọc được nhóm hoàn tất. Hiện thời điểm và thông báo đây là dữ liệu đã lưu khi chưa có xác minh mới.
3. Giữ `RegistrationSources.require`, kiểm tra giấy tờ/chủ sở hữu, tuổi bằng chứng Catalog và các cổng ghi hiện có. Kết quả lịch sử không tự trở thành xác minh mới để ghi sàn/Catalog hoặc mua KIZ. Khi kết quả mới chưa đủ điều kiện, nút ghi vẫn bị khóa như trước; xem/lọc/tải thêm dữ liệu đã lưu vẫn hoạt động.
4. Ozon lưu dấu mốc hoàn tất theo fingerprint cửa hàng trong `app_config`, cùng transaction với trang sản phẩm, barcode và cursor. Mở tổng quan ở đường dự phòng dùng lại catalog đã hoàn tất; lượt chưa xong tiếp tục cursor. Dữ liệu từ bản cũ chưa có dấu mốc được đọc tiếp ở cursor đã lưu, không tự quay lại trang đầu. Trang cuối rỗng không được xác nhận hoàn tất nếu tổng số sàn báo còn lớn hơn số hàng đã có.

Đồng bộ thủ công đầy đủ ở Sản phẩm/Znack và lịch đồng bộ nền hiện có vẫn kiểm tra dữ liệu mới trên sàn. Không tuyên bố API Ozon có giao thức lấy riêng mọi thay đổi thuộc tính: đường `syncCatalog/syncFromStart` vẫn phục vụ lần làm mới đầy đủ; sửa này tránh việc bỏ dữ liệu cũ và ép người dùng đồng bộ chỉ để xem sau cập nhật. `SyncStreamStore` tiếp tục lưu cursor, kết quả và thời hạn thử lại, không đổi chúng theo số phiên bản.

## Kiểm chứng

Hồi quy cuối **1.043/1.043 ca đạt**, 97 lớp kiểm thử, không lỗi/thất bại/bỏ qua; 6 phút 48 giây. Bao gồm đăng ký WB/Ozon, mua/gán KIZ, PDF, sửa sản phẩm, GTIN, scheduler, khóa/thư mục dữ liệu, migration, backup và khôi phục. Đây là tập hồi quy theo phạm vi, chưa phải toàn bộ Java suite. Lượt ba ca nâng cấp/mở lại trước đó nằm trong tập cuối, không cộng lặp.

- `SynchronizedDataUpgradeTest`: dữ liệu tổng hợp cho từng sàn, có snapshot sản phẩm/thương hiệu, TN VED/chất liệu/giới tính, giấy tờ, thẻ đăng ký/feed, Catalog, mapping GTIN đã xác nhận, kho KIZ, lịch sử in và trạng thái đồng bộ đang dở/thời hạn chờ. Qua `LocalDataMigrationGate` với nhãn phiên bản 1.5.16 → 1.5.17 → mở lại 1.5.17, so hash nội dung **từng bảng nghiệp vụ** và đọc lại bằng repository. Không mất/sửa/tạo lại các hàng dữ liệu đó. Nhãn 1.5.17 ở đây là tham số bài thử, chưa phải bộ cài đã phát hành.
- Hai màn JavaFX được hủy và tạo lại với dữ liệu nguồn cũ một tuần. WB vẫn tải/lọc đúng; Ozon tải thêm đến 1.000 hàng. Số lượt nguồn không tăng khi mở, lọc hoặc phân trang. Lượt đọc mới lỗi vẫn khôi phục được danh sách tốt trước đó sau khi tạo lại màn hình.
- Kiểm tra thẻ Catalog lịch sử còn hiện, nhưng không thể dùng nó để vượt kiểm tra ghi mới. Đổi tài khoản/chứng thư hoặc thời điểm nguồn không hợp lệ không được nhận bằng chứng của phạm vi cũ.
- HTTP localhost xác nhận catalog Ozon đã hoàn tất qua đổi phiên bản không phát sinh yêu cầu quét mới. Dữ liệu cũ chưa có dấu mốc chỉ đọc tiếp cursor một lần trong fixture; lần mở tiếp dùng dấu mốc đã lưu. Dữ liệu còn thiếu không bị đánh dấu đã đủ.

Lượt thử đầu có một kỳ vọng sai của bài thử mới: nhóm «Hoàn tất» đòi GTIN đã nằm trong barcode WB, nhưng fixture chưa có barcode đó. Bổ sung đúng fixture, giữ nguyên quy tắc bộ lọc. Sau đó sửa kiểu `ProductKey` của bài thử nâng cấp để dùng đúng miền dữ liệu GTIN; không đổi mã ứng dụng theo các lỗi bài thử này.

## Bàn giao

[Biên nhận mã nguồn, lệnh kiểm thử và kết quả cuối](../validation/2026-10-25-persist-synchronized-data-verify.json).

Không gọi API seller/Catalog production hoặc mua/tiêu KIZ trong lượt này. Chưa chạy bộ cài nâng cấp/native Windows, chưa full suite và chưa tạo bộ cài mới. Việc đổi nhãn phiên bản trong bài thử xác minh migration/khôi phục dữ liệu bằng mã Java trên Linux, không thay thế nghiệm thu MSI/EXE. Giữ Latest v1.5.16 bất biến; khi được yêu cầu phát hành phải tăng phiên bản từ nhánh có đủ các sửa đang chờ.
