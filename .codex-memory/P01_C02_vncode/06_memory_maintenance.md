# Bảo trì

Context pack giữ ngắn, trạng thái chỉ ghi việc còn hiệu lực. Snapshot dài thì
đưa kết luận bền vững sang Deja Vu, giữ liên kết tới bằng chứng và bàn giao gần nhất.
Không tự xóa lịch sử quyết định; supersede và sửa cue khi có thay đổi.

Sau chỉnh bộ nhớ: chạy Smart validator và Deja Vu doctor theo AGENTS.md.
Khi cập nhật upstream: kiểm tra nguồn, license, script trước khi chạy; cập nhật
`tools/memory/sources.lock.json` cùng digest các file vendor. Không thay file
bộ nhớ đã điền bằng template hoặc chạy initializer với `--overwrite`/`--force`.
