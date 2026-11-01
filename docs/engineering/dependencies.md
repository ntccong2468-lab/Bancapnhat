# Quản lý dependency

Desktop khai báo Maven trong `pom.xml` và Maven Wrapper trong `.mvn`; portal dùng `website/license-portal/package.json` cùng `package-lock.json`. Tác vụ CI dùng phiên bản action ghim commit. Trước khi nâng cấp, ghi phiên bản hiện tại, nguồn/cảnh báo cụ thể, mức ảnh hưởng và phạm vi sử dụng thực tế.

Rà trực tiếp và bắc cầu theo stack: `./mvnw -B dependency:tree` cho Maven; trong portal dùng `npm ci` rồi `npm ls --all`. Khi có mạng và nguồn dữ liệu phù hợp, chạy kiểm tra lỗ hổng dependency, ghi rõ ngày, công cụ, nguồn, lỗi chưa đánh giá và khả năng không truy cập được cơ sở dữ liệu lỗ hổng. Không dùng lệnh tự sửa hàng loạt làm thay đổi lockfile ngoài chủ đích.

Nâng cấp từng nhóm nhỏ, xem breaking changes và license thư viện, kiểm tra tương thích Java 25/JavaFX, Windows, SQLite, PDF, CryptoPro và build; chạy lại các test phù hợp. Giữ `package-lock.json` đồng bộ khi thay đổi package. Báo rõ khi không có lockfile Maven cho toàn bộ đồ thị hoặc khi chưa chứng minh được tính tái lập.
