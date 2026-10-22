# Kiểm tra khôi phục

Phiên sạch chỉ đọc AGENTS/context pack/cue liên quan phải tìm được:
1. Mốc ứng dụng đã công bố, khác với tên nhánh lịch sử.
2. Số GTIN production chưa được xác nhận; test mô phỏng không thay nghiệm thu thật.
3. KIZ pending vẫn theo đúng danh tính và trạng thái mã.
4. Codex-Mem chưa hoạt động trong Web, không cần tự công bố bản ứng dụng.

Sau chỉnh bộ nhớ, chạy hai validator theo AGENTS và thử đọc lại với phiên sạch.
