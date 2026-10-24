# Đăng ký từng thẻ liên tục và giới hạn phải giữ

## Yêu cầu và lỗi tìm thấy

Seller muốn lô tiếp tục chạy đến hết, không tự tạm dừng sau năm lần đọc chưa có kết quả. Phần bỏ giới hạn năm lần và khôi phục pause cũ đã nằm ở `3ede758`; xem [bàn giao trước](2026-10-24-catalog-registration-retry-continuation.md). Lượt kiểm tra tiếp này phát hiện một đường dừng khác: khi chính yêu cầu cấp GTIN hoặc POST feed bị HTTP 429 từ chối, workflow ghi `GTIN_REVIEW_REQUIRED`/`FEED_REVIEW_REQUIRED`, làm yêu cầu đã bị từ chối rõ ràng bị coi như giao dịch chưa biết kết quả.

## Sửa cho WB và Ozon

- Chỉ HTTP 429 trực tiếp, có kiểu lỗi transport, được coi là lần gửi đã bị từ chối và có thể thử lại. Không dựa vào chữ “429” trong thông điệp hoặc nguyên nhân của một lỗi khác.
- Cấp GTIN bị 429 và chưa nhận mã: trở về `PREFLIGHT_ERROR`; hàng đợi tự thử lại cùng ý định cấp mã sau thời điểm đã lưu.
- POST feed bị 429 và chưa có receipt: trở về `READY_TO_SUBMIT`; dùng lại GTIN và payload đã lưu. Không cấp GTIN mới để thử lại POST.
- Ozon lưu deadline theo nguyên nhân thật của lần gửi, bao gồm `Retry-After` dài hơn backoff. Không chỉ chờ mặc định mười giây khi dịch vụ yêu cầu lâu hơn.
- Yêu cầu đã có feed hoặc thẻ xác nhận vẫn đối soát theo danh tính đó. Không đổi cách đếm: Catalog nhận yêu cầu và đã đọc được thẻ là hai kết quả riêng.

## Những trường hợp không thể gửi không nghỉ

| Tình huống | Xử lý đúng và giới hạn |
| --- | --- |
| HTTP 429 | Chờ thời hạn dịch vụ rồi tự chạy tiếp. Gửi dồn trong thời gian bị giới hạn làm tăng số lần từ chối; không giúp tạo thẻ nhanh hơn. |
| Mất mạng hoặc lỗi đọc 5xx | Giữ hàng đợi và danh tính, thử lại với backoff. Không tự chuyển PAUSED sau số lần thử. |
| Timeout, 5xx hoặc mất receipt sau một lần ghi đã phát đi | Kết quả có thể đã được xử lý bên ngoài. Đối soát trước; không tự gửi lặp hoặc cấp thêm GTIN. Allocation chưa rõ có thể chặn pool để tránh cấp nhầm mã cho thẻ khác. |
| Catalog từ chối dữ liệu/giấy tờ | Ghi rõ lý do cho thẻ đó; các thẻ độc lập còn đủ điều kiện tiếp tục. Không báo đã tạo khi chưa có thẻ thật. |
| 401/403, hết quota GS1, owner/cấu hình thay đổi, thiếu quyền hoặc license | Cần xử lý điều kiện tài khoản/dữ liệu; thử liên tục không sửa được nguyên nhân. Giữ các guard hiện có. |
| Seller Tạm dừng/Hủy | Tôn trọng thao tác, không tự khởi chạy lại. |
| App thoát hoàn toàn, máy tắt hoặc không có mạng | Hàng đợi giữ trong database và tiếp tục khi app/mạng trở lại; không có HTTP khi không còn tiến trình chạy. |

## Kiểm chứng

- Thử giả lập 117 thẻ: năm POST bị 429 ở các lượt 1/22/55/89/120; endpoint đọc bị 429 mỗi bảy lượt; toàn bộ thẻ chưa hiển thị ở giai đoạn gửi. Hàng đợi vượt năm lần thử mà không cần thao tác tiếp tục. Sau khi cho Catalog giả lập hiển thị thẻ, đủ 117 thẻ được xác nhận, 0 lỗi/0 còn lại, 117 lần cấp GTIN, 122 lần thử POST = 117 lần được nhận + 5 lần bị từ chối. Không gửi lại feed đã nhận. Test chủ động đưa deadline lưu về quá khứ để mô phỏng hết thời gian chờ; đây không phải đo tốc độ production.
- Kiểm tra cấp GTIN bị 429 cho cả WB/Ozon, giữ GTIN khi feed bị 429, deadline một giờ qua repository mới và không gửi trước deadline.
- Dùng HTTP server localhost qua `ZnackApiClient` thật để kiểm tra POST 429, journal, retry và đọc lại đúng ba thẻ. Server chỉ cho phép POST `/v3/feed`; không có lần xin GTIN/ký ngoài dự kiến.
- Hồi quy tiếp các trường hợp mất phản hồi sau cấp/gửi/ký, chống hai worker gửi trùng, scope/owner, UI, bộ lọc, báo cáo và giới hạn request.

Mã chốt `59c3dc524d0bc0578aaaa1f320f225bf6769a841` đạt **1.246/1.246 ca hồi quy, không thất bại/lỗi/bỏ qua**, kết thúc 18:53:33 UTC ngày 24.10.2026. Kết quả và hash mã/log nằm trong [biên nhận](../validation/2026-10-24-catalog-continuous-submission-verify.json). Chỉ dùng dữ liệu tổng hợp, SQLite, HTTP localhost và JavaFX/Xvfb. Không tạo thẻ production hoặc đo throughput tài khoản seller. Chưa dựng bộ cài mới hoặc thay Latest 1.5.14; khi đóng gói phải dùng phiên bản mới và kiểm tra Windows đúng mã nguồn.

Cập nhật sau yêu cầu phát hành: các sửa này đã được đưa vào **Latest 1.5.15**, công bố 19:47:09 UTC cùng ngày. Full suite Windows/Linux đạt 3.009 ca, mở app và nâng cấp từ 1.5.14 giữ dữ liệu đạt. [Bàn giao phát hành](../validation/2026-10-24-vncode-1515-handoff.md).
