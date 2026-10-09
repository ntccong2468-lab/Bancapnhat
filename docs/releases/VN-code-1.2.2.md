# VN code 1.2.2 — đối chiếu Wbcode 1.3.1

Đây là bản cập nhật tiếp theo của cùng VN code Windows, giữ UUID bộ cài và dữ liệu VN code. Bản 1.2.1 Preview đã phát hành trước đó vẫn giữ nguyên; 1.2.2 bổ sung hành vi in FBS trong release Wbcode vừa xuất hiện. Chưa tương đương toàn bộ Wbcode 1.3.1.

## Quan sát bản mới nhất

[Wbcode 1.3.1](https://github.com/rupphi/relatest-wcode/releases/tag/v1.3.1) phát hành ngày 09/10/2026 lúc 19:17 UTC. Bộ cài chính thức được kiểm tra SHA-256 `2632e9a06db84990476d97f1f962b61f5dffcec40ccc2c90c7bb7b304b4a0533` và chạy trên Windows tạm: [CI quan sát](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/37983187676).

Tên cửa sổ đúng `Wbcode v1.3.1`; giao diện lần mở đầu là thanh menu biểu tượng, nền sáng, có Hướng dẫn. Chưa nhập shop hoặc kích hoạt license; nội dung chính vẫn trống. Chỉ xác nhận màn hình lần mở đầu, không coi đây là nghiệm thu các luồng đã đăng nhập. [Ảnh quan sát](../validation/wbcode-1.3.1-observation/official-guides-hover.png), [metadata](../validation/wbcode-1.3.1-observation/observation.json), [release gốc](../validation/wbcode-1.3.1-observation/release.json).

Nguồn công khai [test-wcode](https://github.com/rupphi/test-wcode/tree/8f7c3d1ac8598158029b8d037e2d3c128e240727) vẫn là 1.1.32, chưa có nguồn 1.3.1. VN code áp dụng thay đổi mô tả khi xác định được hành vi trong code hiện có.

| Thay đổi Wbcode 1.3.1 | VN code 1.2.2 |
|---|---|
| Nút in FBS hiện ngay khi mở lô; bấm khi đang sync sẽ chờ rồi in | Đã áp dụng: nhận một yêu cầu, in sau sync thành công; lỗi/đổi shop hoặc lô hủy yêu cầu |
| “Catalog changed during scan” tự quét lại khi ký | VN code ký theo từng feed/thẻ, không có scanner này; chưa xác minh hành vi tương đương |
| FBO chỉ in GTIN trên thẻ WB; cột GTIN và danh sách có ảnh cho hàng thiếu | Chưa áp dụng; quy tắc phân biệt GTIN trên thẻ với barcode WB chưa xác minh. Tài liệu WB kiểm tra lại trả HTTP 498 |
| Lỗi in KIZ có trạng thái đơn mua | Chưa bổ sung báo cáo trạng thái này |
| GS1: thư trái/phải, thu gọn, thanh toán sau hoá đơn, hướng dẫn Việt | Chưa có module/hợp đồng GS1 được xác minh |
| License/clock/key bị thu hồi | VN code cá nhân miễn phí, không dùng giấy phép WCode |

## Giao diện và phạm vi

Giữ menu có chữ và bố cục đã duyệt theo ảnh 1.2.0, cùng cải tiến 1.2.1: sáu thẻ, tin tức, bảng supply sáu cột WB/GTIN riêng, kho/cấu hình thu gọn, Hướng dẫn và sửa TN VED tải nền. [Ảnh và mô tả giao diện](VN-code-1.2.1.md). Thanh menu VN code khác thanh biểu tượng ở lần mở Wbcode 1.3.1.

Khi đang tải lô, nút xuất nhãn nhận yêu cầu và hiện trạng thái chờ; không tạo tác vụ in trùng. Yêu cầu được hủy khi tải lỗi, chuyển shop/lô hoặc đóng controller. Kết quả rỗng hay token không còn hợp lệ không tự mở hộp thoại in. Không tự in dữ liệu cache khi đồng bộ thất bại.

Xem [trạng thái đầy đủ](VN-code-1.2.2-status.json). FBO trong Preview vẫn in barcode theo luồng cũ; chưa có thay đổi in GTIN của Wbcode 1.3.1. GTIN hiển thị ở bảng supply lấy từ KIZ đã gán hoặc mapping đúng sản phẩm, không thay thế xác nhận marketplace.

Điểm nhỏ còn lại từ 1.2.1: tiêu đề phần giao hàng thu gọn cần mở lại ứng dụng để đổi ngôn ngữ.

## Kiểm tra và phát hành

[Tải VN code 1.2.2 Preview — Windows x64](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.2.2-preview.1/VN-code-1.2.2-preview.1-Windows-x64.exe), [Release và checksum](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.2.2-preview.1).

Đóng VN code trước khi chạy EXE. Preview cài thủ công, Java được đóng gói; chưa ký Authenticode và chưa có signed update manifest/tự cập nhật.

[CI Windows](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/37984754861) đạt **607 kiểm thử Java/JavaFX và 31 kiểm thử Node**, không lỗi hoặc bỏ qua. Source bộ cài đúng commit `d17ced942afd4231ca6821013c34e0e1ee11e11c`.

- EXE mở đúng cửa sổ VN code v1.2.2; migration schema 3→4 giữ lịch sử, snapshot phục hồi được xác minh.
- Cài/gỡ cạnh WCode 1.1.75 thật giữ nguyên chương trình và dữ liệu WCode.
- Nâng cấp từ VN code 1.1.34, 1.2.0 Preview và 1.2.1 Preview giữ dữ liệu/template, chỉ có một registration VN code.
- Kiểm thử hồi quy xác minh yêu cầu in khi đang tải, hủy khi lỗi/đổi ngữ cảnh, chống preflight trùng và hộp thoại shop cũ.

[Báo cáo](../validation/vn-code-1.2.2/local-verification.json), [launcher](../validation/vn-code-1.2.2/native-smoke.json), [cài cạnh WCode](../validation/vn-code-1.2.2/side-by-side-smoke.json), [upgrade 1.1.34](../validation/vn-code-1.2.2/upgrade-smoke.json), [upgrade 1.2.0](../validation/vn-code-1.2.2/upgrade-preview-smoke.json), [upgrade 1.2.1](../validation/vn-code-1.2.2/upgrade-latest-preview-smoke.json).

SHA-256 bộ cài: `4ef4b3881337f1414d684d0be0ced9b8543be6bf114031ec31e5c83227d93541` (141.094.400 byte). Chưa nghiệm thu với shop hoặc máy in thật.
