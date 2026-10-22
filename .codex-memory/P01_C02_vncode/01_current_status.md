# Trạng thái công việc

Ngày: 2026-10-22. Mốc trước cài đặt: `ef5bd2c6de024a0f29eb0e0ec703b2df0330ae66`.

- Công việc trước đã xong: 1.5.11 công khai Latest, đủ gói cài/nguồn, updater
  xác minh; 30 release cũ đã gỡ, history/tag được giữ.
- Đã cài Smart Project Memory gốc, workspace phiên VNCode, Deja Vu file protocol,
  công cụ offline và AGENTS ở root repo; có CI kiểm tra bộ nhớ riêng.
- Kiểm tra tại chỗ: Smart validator đạt; Deja Vu doctor 0 lỗi/0 cảnh báo;
  3 cue liên quan trả strong, cue không liên quan không nạp record;
  59 file vendor đúng digest và nguồn pin; snapshot/khôi phục phiên độc lập đạt;
  bộ kiểm thử Node 22 hiện có đạt 117/117. Xem báo cáo bên dưới.
- Codex-Mem đã tải/đọc nguồn và pin revision; chưa bật Web, chưa có MCP/capture,
  Bun/worker và lưu trữ qua phiên được kiểm chứng. Hướng dẫn CLI đã ghi trong repo.
- Việc production vẫn mở: số GTIN shop thật và nghiệm thu mua/gán KIZ với seller;
  không dùng kết quả mô phỏng để báo production thành công.

Bằng chứng cài: `docs/validation/2026-10-22-ai-memory-installation.md` từ gốc repo.
Phiên mới: kiểm tra HEAD/remote có commit cài bộ nhớ và CI, rồi theo yêu cầu mới.
Không phát hành lại 1.5.11 hay chạy lại cleanup theo ghi chú lịch sử.
