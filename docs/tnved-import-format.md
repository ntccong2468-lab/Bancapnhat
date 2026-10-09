# Nhập danh mục TN VED

Chỉ nhập dữ liệu đã kiểm tra nguồn, phiên bản và ngày hiệu lực. URL hợp lệ không tự chứng minh tính pháp lý. Module không có bộ mã chi tiết được tạo sẵn: dữ liệu ban đầu chỉ gồm 21 phần gốc.

JSON UTF-8: một object gồm `version` (chuỗi), `source_url` (HTTPS), `valid_from` (`YYYY-MM-DD`) và `nodes` (mảng object). Không dùng số cho mã; nếu mã đã mất số 0 đầu, không thể tự phục hồi.

Mỗi node gồm `code`, `parent_code`, `node_type`, `section_code`, `name_ru`, `name_vi`, `is_leaf`, `is_active`, `valid_from`; tùy chọn `valid_to`, `description_ru`, `notes`, `source_url`. Boolean JSON phải là `true/false`, không phải chuỗi. `source_url` của node thiếu thì lấy từ manifest. Mã cha được tham chiếu bằng code trong cùng phiên bản, không dùng ID nội bộ.

CSV UTF-8 dùng cùng tên cột. Bắt buộc có header `code,parent_code,node_type,section_code,name_ru,name_vi,is_leaf,is_active,valid_from`. Manifest JSON đi kèm tên `<tên-file.csv>.manifest.json`, có `version`, `source_url`, `valid_from`. CSV hỗ trợ trường có dấu phẩy, dấu ngoặc kép escape bằng hai dấu ngoặc kép và xuống dòng trong trường được quote. Boolean CSV chỉ dùng chữ thường `true` hoặc `false`.

`node_type` là SECTION/CHAPTER/HEADING/SUBHEADING/DETAIL/LEAF. Mã SECTION là I–XXI; chương 2 chữ số, heading 4, subheading 6, mã cuối 10 chữ số. Phải lấy loại nhánh và cha/con từ danh mục nguồn, không tự suy ra mã chi tiết. Chương 77 không được nhập như chương đang có hiệu lực.

Giới hạn mỗi file 32 MiB và 100.000 node. Node trùng, thiếu cha, chu trình, phần/chương sai, mã cuối sai độ dài hoặc khoảng ngày sai làm import thất bại. Phiên bản cũ không bị thay thế một phần khi lỗi. Người dùng phải xác nhận nguồn và ngày hiệu lực trước khi kích hoạt phiên bản mới.

Trước import, module tạo bản sao SQLite nhất quán ở `tnved/backups` trong thư mục dữ liệu VN code, kèm `.sha256`. Chức năng Sao lưu cũng tạo bản sao này. Phục hồi tự động toàn ứng dụng chưa bao gồm database TN VED; không tự chép đè database đang mở hoặc xóa WAL.
