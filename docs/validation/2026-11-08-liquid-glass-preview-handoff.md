# Apple / Liquid Glass — bản xem trước chờ duyệt

Ngày 08.11.2026. **Chưa áp dụng giao diện mới vào source app, chưa phát hành.** [Receipt](2026-11-08-liquid-glass-preview.json).

Người dùng yêu cầu hoàn tất WB/KIZ đang dở, dùng TASTE + IMPECCABLE với hai prompt Apple/Liquid Glass và navigation motion, chụp toàn bộ thay đổi trước khi sửa mã nguồn UI. Việc WB hoàn tất trên `ae2d48a` và bàn giao `d93ae2e`; xem [báo cáo WB](2026-11-08-wb-kiz-color-documents-handoff.md). Sửa WB chưa có nghiệm thu mua có phí vì không có OMS/chứng thư; API chỉ đọc, 217 ca nguồn cuối đạt.

## Artifact bàn giao

Tất cả bản thiết kế nằm ngoài repository tại `/workspace/vncode-apple-preview-20261108`:

- `proposal/gallery.html`: toàn bộ ảnh có bộ lọc nền/nhóm/tên, mở ảnh đầy đủ.
- `proposal/index.html`: bản mẫu tương tác; không API thật, không dữ liệu seller hoặc mã KIZ thật.
- `proposal/screenshots/`: 164 ảnh sau, gồm 46 màn chính, 32 tab, 44 dialog, 24 responsive, 14 tương tác/proof, ba state và một forced-colors/reduced.
- `proposal/before/`: sáu ảnh app JavaFX/Linux hiện tại/profile trống; không phải ảnh app seller Windows.
- `proposal/contacts/`: bảy ảnh tổng hợp. `proposal/motion-demo.mp4`: 22,72 giây/H.264/1440×900/25fps, bản mẫu Chromium. Không phải benchmark JavaFX.
- `proposal/docs/`: audit đối chiếu 20 route native, kiến trúc đề xuất, motion, kế hoạch sau duyệt, review và aggregate verification.
- `VN-Code-Liquid-Glass-preview.zip`: 198 tệp/44.270.656 byte, SHA-256 `f8d7e01ea6ce2799266098fa8916027661ed4d79de4ab95b1c1ee4dc66b4152f`. CRC ZIP và 164 hash ảnh trong ZIP đã đối chiếu.

Bản mẫu/gói ảnh không được commit vào app repo. Receipt chỉ ghi tổng hợp/path/hash; nếu môi trường mất, khôi phục từ ZIP đã giao, không tự suy ra người dùng đã duyệt.

## Đã kiểm ở mức bản mẫu

- 372/372 kiểm tra, 0 JavaScript error và external request; 23 màn chính × hai nền × năm kích thước desktop, cùng filter, selection, scroll, draft, modal, focus/Escape và navigation nhanh.
- TASTE inspect 1280/1440/1920: 0 FAIL/0 WARN cả hai nền trên nguồn prototype cuối. Lint còn ba cảnh báo có căn cứ: kính theo user brief và định dạng số.
- Fresh reviewer ban đầu trả `fix`, sau một batch và recapture đánh giá sáu điểm **resolved**: select đóng thiếu/nuốt nội dung; draft/cache theo shop; theme theo OS và reload; label-for; popover focus; nguồn tin dưới tiêu đề. Disposition `ship` **chỉ cho sáu sửa này**, không phải review toàn app hoặc user approval.
- Detector chạy một lần trên HTML: hai warning/31 advisory, phần lớn so token cũ; giữ Inter/11px chrome-provenance có căn cứ. Không chạy lại để xóa dấu vết; DESIGN.md/sidecar app chưa đổi. Direction contract được tổng hợp sau ảnh đầu, không coi là đã có trước code; không có comp được duyệt.
- 181 raster có metadata origin, không thiếu; ảnh hàng stock và logo cũ, không image generation. Video giải mã và seek ba mốc đã kiểm, reviewer không xem phát video.
- `file://` bị policy Cloud browser chặn, localhost HTTP/gallery đã kiểm; không tuyên bố đã thử file-mode trên Windows. Không có nghiệm thu native Windows, DPI/taskbar/Narrator, hiệu năng app hoặc API nghiệp vụ từ bản HTML.

## Hướng trình bày và ranh giới tiếp tục

Hai nền cùng bố cục; kính nhẹ tại sidebar/toolbar/overlay, bảng nền đục, 70px/dòng thường, đơn nhiều hàng tăng theo nội dung. Giữ bảng rộng/cao, ghép batch với phân trang, mọi route và handler nghiệp vụ. Giữ năm nút sửa/tuyên bố mặc định, bốn trạng thái Znack, FBS WB/Ozon, mapping/kho seller-mua, GS1/cấu hình/tài chính/lịch sử/mẫu và cài đặt.

Motion phân biệt sibling/forward/back/tab/modal/popover/inspector, shared product image, hủy animation cũ, cache scroll/selection/nháp theo shop và reduced motion. Native JavaFX cần paints/fallback cho kính; không sao chép CSS `backdrop-filter` hoặc chuyển app thành web. Giữ titlebar/work-area và API/SQLite/license/owner guards.

**Tiếp theo: đợi người dùng duyệt bộ ảnh.** Sau duyệt mới triển khai vào JavaFX và kiểm handler/native Windows, rồi đóng gói nếu được yêu cầu. Không sửa token production, thêm theme vào app, tạo tag/bộ cài hoặc đẩy Latest trong lúc đang chờ.

App source tại lúc chụp `d93ae2e9d340fefc4406f66e9daf9bf846ed6cc9`, tree sạch; commit bàn giao này chỉ docs. Latest đọc lại vẫn `v1.6.6`/release `437862752`, công bố 07.11.2026 21:46:11 UTC. Không gọi publisher hoặc mutation release trong lượt preview.
