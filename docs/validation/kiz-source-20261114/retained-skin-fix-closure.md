# Retained popup skin: bổ sung nghiệm thu RED → GREEN

Ngày 14/11/2026. Đây là bản bổ sung sau điều tra chỉ đọc ban đầu, giữ nguyên lịch sử fail.

Lượt toàn bộ đầu tiên có 4.539 test/569 suite, 0 failure, 1 error, 2 skip. Error là popup Quản lý có chữ palette sáng trên nền selection tối. Hai lượt chạy lại method/class ban đầu pass; không dùng chúng để xóa lỗi toàn bộ.

Sau đó thêm một regression test dùng API công khai: tạo ContextMenu + CustomMenuItem/Button, áp theme DARK cho popup scene trước khi skin xuất hiện, mở/đóng rồi đổi LIGHT và mở lại chính popup/skin đó. Không dùng reflection, không chèn palette giả hoặc hạ ngưỡng contrast. Parent chạy test trên production source chưa sửa và nhận RED: skin vẫn giữ theme-dark, ink=0x24252a, material=0x2c2d32; cả hai kiểm lớp theme và độ tương phản cùng thất bại. Đây là lỗi thật về cập nhật retained skin, không được quy đơn giản cho cache filesystem hoặc bỏ qua như test chập chờn.

Sửa tối thiểu trong ThemeService.material: đồng bộ đúng năm class do ThemeService sở hữu vào node của skin đang tồn tại. Giữ nguyên style property đang bind, ID và mọi class của caller; không sửa CSS rộng hoặc hành vi KIZ. Regression giữ kiểm skin instance không đổi, theme hiện tại, contrast ≥4,5 và thêm kiểm class seller-actions còn nguyên.

Parent đã chạy GREEN: **101 test/7 suite, 0 failure, 0 error, 0 skip**, gồm ThemeService11/11; hoàn tất12:43:05UTC, Maven40,057giây. Log: final-focused-theme-and-kiz.log. Người viết không tự chạy Maven.

**Lượt clean verify mới toàn bộ: đang chờ parent.** Chưa được báo toàn bộ lần cuối đã pass. Các receipt full fail, retained-skin RED và focused GREEN đều giữ nguyên.

Các file ở thời điểm bàn giao (chưa gán commit mới):

- `src/main/java/com/vncode/app/shared/ThemeService.java` — SHA-256 `3c8b3d919edcc52720a6b4a66ed2676c782e396e50fae0bdd74edd29ec6a34d6`.
- `src/test/java/com/vncode/app/shared/ThemeServiceTest.java` — SHA-256 `4299b435d9d44f3390617b89ace2c6feca51eceeadd5c8a46ccd4952b02ee62d`.

Chứng cứ máy đọc: retained-skin-fix-review.json; retained-skin-red.json+xml; log parent; các JSON/javap OpenJFX trong cùng thư mục. Không có thay đổi source sau lượt GREEN của parent trong nhiệm vụ này.

## Bổ sung chốt cuối của parent

Nguồn đã commit `75691c68fa14f728856b0418fb6008083787ef7c`. Lượt clean verify mới hoàn tất **14.11.2026 13:38:44UTC /16:38:44MSK:4.540ca/569suite,4.538đạt,0failure/error,2Linux OSskip có danh tính**. JAR production build và byte review đạt. Đây là lượt thực chạy bởi parent; giữ nguyên lịch sử full đầuFAIL, RED của retainedskin và GREEN101. Không sửa nguồn sau lần kiểm cuối; các tệp sau đó là tài liệu.
