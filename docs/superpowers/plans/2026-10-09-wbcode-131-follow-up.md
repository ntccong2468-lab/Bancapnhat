# Wbcode 1.3.1 Follow-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Quan sát Wbcode 1.3.1 và cập nhật luồng yêu cầu in FBS đang đồng bộ cho cùng VN code.

**Architecture:** Giữ JavaFX/HomeController và API hiện có. Một trạng thái yêu cầu in theo token shop/supply chỉ được tiêu thụ sau lượt tải hiện tại thành công; thất bại hoặc đổi ngữ cảnh hủy yêu cầu. Không thay thế barcode WB bằng GTIN thiếu nguồn xác minh.

**Tech Stack:** Java 25, JavaFX, Maven, PowerShell Windows, Node 22.

**Spec:** `docs/superpowers/specs/2026-10-09-wbcode-131-follow-up.md`

## Global Constraints

- Cùng ứng dụng VN code, UUID `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`, dữ liệu VNcodeData riêng.
- Không cập nhật lại binary/tag 1.2.1 đã phát hành; dùng 1.2.2 cho thay đổi production mới.
- Không nhập shop/license WCode hoặc gửi marketplace mutation thật trong kiểm tra.
- Giữ layout đã duyệt và hỗ trợ RU/EN/VI/ZH; dùng thông báo `supply.loading_orders` hiện có khi chờ in.
- Preview ghi rõ FBO GTIN và GS1 chưa tương đương 1.3.1; cài thủ công.

## Review Focus

- Nhấp in nhiều lần trong lúc tải: tối đa một yêu cầu.
- Đổi shop/supply rồi lượt tải cũ hoàn tất: không mở hộp thoại/in của ngữ cảnh cũ.
- Tải thất bại với dữ liệu cache còn có: không tự in cache.
- Supply rỗng/credential hết hiệu lực: không thực hiện in.
- Nâng từ 1.2.1 Preview: chỉ một registration, giữ DB/template.

---

### Task 1: Quan sát và đối chiếu 1.3.1

**Files:** `tools/windows-wcode-ui-observe.ps1`, `.github/workflows/observe-wcode.yml`, `docs/validation/wbcode-1.3.1-observation/*`.
**Interfaces:** Giữ script quan sát không nhận credential; xuất `observation.json` và ảnh screenshot trên runner Windows.

- [x] Cập nhật version/hash đã xác minh, parse PowerShell, commit workflow quan sát.
- [x] Chạy workflow, tải artifact, xem ảnh thực tế và ghi ma trận thay đổi/giới hạn.

### Task 2: In FBS sau đồng bộ

**Files:** Create `src/main/java/com/vncode/app/ui/workspace/SupplyPrintRequest.java`; modify `HomeController.java`; tests `SupplyPrintRequestTest.java` và `FxmlSmokeTest.java`.
**Interfaces:** `SupplyPrintRequest.begin(int shopId, String supplyId, long token)`, `request()`, `complete(int shopId, String supplyId, long token, boolean success)`, `cancel()`, `isLoading()`, `isPending()`. `request` chỉ nhận một lần khi đang tải; `complete` trả true một lần khi đúng ngữ cảnh, thành công và có yêu cầu.

- [x] Viết RED cho nhấp trùng, hoàn tất sai token/shop/supply, thất bại và hủy; chạy targeted test để xác nhận lỗi thiếu class.
- [x] Thực hiện trạng thái đơn giản, tích hợp bắt đầu/hủy/hoàn tất vào HomeController; `onExport` ghi yêu cầu khi loading, không lấy snapshot dữ liệu cache.
- [x] Kiểm tra FXML nút hiện/được bật khi mở supply đang tải; chỉ export sau thành công, có orders, token hợp lệ và shop không bận.
- [x] Chạy targeted tests, toàn bộ Maven verify; commit.

### Task 3: Bộ cài 1.2.2 giữ dữ liệu

**Files:** `pom.xml`, `tools/windows-upgrade-smoke.ps1`, `.github/workflows/build-java.yml`, `tools/publish-vncode-preview.mjs`, release docs/status và Node contract tests.
**Interfaces:** Giữ upgrade proof 1.1.34/1.2.0 và thêm `upgrade-latest-preview-smoke.json` từ 1.2.1, hash `2159133f48de2fcaaa2d39e5983157d9c713929c10479d0007f30463394d483f`; publisher bắt buộc proof này khi version 1.2.2.

- [x] Thêm RED contract cho thiếu/sai upgrade proof 1.2.1; implement thêm probe và gate publication.
- [x] Tăng version 1.2.2, ghi phạm vi đã áp dụng/còn thiếu; chạy Maven/Node/PowerShell parse.
- [x] Review một lượt, sửa phát hiện cần thiết; chạy CI Windows đúng commit.
- [x] Xác minh checksum, native/coinstall/ba lượt upgrade; tạo Preview, tải lại các asset để kiểm tra digest, cập nhật README và bằng chứng.
