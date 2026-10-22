# VNCode — context pack

Cập nhật: 2026-10-22. Repo `ntccong2468-lab/Bancapnhat`; nhánh mặc định hiện tại
`update/vn-code-1.3.0` là tên lịch sử, không phải phiên bản ứng dụng đang phát hành.
Kiểm tra HEAD/remote khi bắt đầu phiên mới.

VNCode là ứng dụng Windows Java/JavaFX cho seller WB/Ozon. Bản công khai đã
kiểm chứng: **1.5.11 Latest**, tag `v1.5.11`, nguồn ứng dụng
`2abe716d77a0f2d4d5f08ed0ae6451bf29ea2495`. Commit hoàn tất công bố/dọn release:
`ef5bd2c6de024a0f29eb0e0ec703b2df0330ae66`. 30 release cũ đã gỡ, lịch sử/tag giữ
nguyên. Đây là mốc kiểm chứng ngày trên; kiểm tra GitHub trước công việc phát hành.

Bộ nhớ repo đã cài và kiểm tra tại chỗ: Smart Project Memory (skill + snapshot),
Deja Vu (cue + quyết định/lỗi), Codex-Mem (đã đối chiếu nguồn, chưa bật trong Web).
Xem `docs/ai-memory.md` và `01_current_status.md`. Không tạo release ứng dụng chỉ
vì thay đổi bộ nhớ. Hai lớp file cần được Git lưu/push để phiên sau có thể đọc.

Các điểm cần giữ khi sửa: UI/nút Znack hiện có; lọc Chưa tạo/Đang xử lý/Lỗi theo
trạng thái Catalog; bảng đề xuất điền sẵn để seller Áp dụng; GTIN WB phải đọc lại
đúng giá trị; KIZ pending phải đúng phạm vi danh tính và trạng thái mã.
Quét `memory/impressions.jsonl` rồi chỉ đọc 1–3 record liên quan.

Giới hạn: 10.000 GTIN đã kiểm thử bằng API mô phỏng; chưa đọc được số liệu shop
Mpp fashion thực tế. Không có bằng chứng mua KIZ/ký chứng thư thật trong cloud.
Không suy ra đã nghiệm thu production từ test xanh.

Kiểm thử Windows của 1.5.11: 2.879 ca, 0 lỗi/thất bại, 2 bỏ qua; Node 117/117.
Bằng chứng: `docs/releases/BASELINE.md`, `docs/validation/evidence/1511/`.
Bộ cập nhật Windows ưu tiên MSI; tag và asset 1.5.11 bất biến.
