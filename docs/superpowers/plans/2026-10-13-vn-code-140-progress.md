# VNCode 1.4.0 — tiến độ và phần tiếp theo

Nhánh làm việc: `update/vn-code-1.4.0`, tiếp tục từ nhánh Products/Copy đã có.
Nguồn nền: `854b65fc3d0a9853718a3219001e4d5f3b5486e6`.
Đây là mã đang phát triển, không phải công bố toàn bộ tương đương Wbcode 1.3.7.

## Đợt hiện tại

- Client đăng ký Gmail/OTP ngay trong VNCode; chờ admin website duyệt/cấp khóa.
- Nhập khóa VNC trong VNCode, proof thiết bị và xác minh envelope quyền Ed25519.
- Offline có hạn, highwater/monotonic, denial lưu lại; restore kiểm tra server.
- Một worker/timer, queue hữu hạn; logout ưu tiên, generation chống response cũ.
- DPAPI riêng cho license, giữ device pair sau logout, không lưu mật khẩu website.
- UI RU/EN/VI/ZH và sidebar phản ánh trạng thái license thật.
- Guards tại shop, listing/bulk, seller sync, GTIN/Znack/KIZ, packing/in và GS1.
- Giữ checkpoint/owner của tác vụ đã gửi; known-unsent denial không thành timeout.
- Các chức năng Products/Copy/Ozon/hiệu năng sẵn có được giữ.
- Cổng publisher yêu cầu pin thật và bằng chứng backend/Windows khớp source.

## Điều kiện còn thiếu cho license production

1. Triển khai Portal 0.3.1 và migration trên database hiện có sau sao lưu.
2. Cấu hình email thật, private signing key trên server; nhận và đối chiếu public
   key để pin vào client. Đã nhận/pin public key `vncode-1`; chữ ký server và nghiệp vụ thật còn chờ kiểm tra.
3. Nghiệm thu Gmail/OTP, approval/key, quota đồng thời, retry, gia hạn/khóa,
   Windows DPAPI/identity khác và offline. Khóa tài khoản hoặc thu hồi máy offline
   chỉ có hiệu lực sau kiểm tra online hoặc hạn quyền đã ký.
4. Build Windows, migration/upgrades, máy in và seller/certificate thật.

## Những nâng cấp Wbcode vẫn cần đợt nghiệp vụ tiếp theo

- Gender edit hàng loạt; gợi ý TN VED theo loại/giới tính/chất liệu và áp dụng
  từng sản phẩm. Không suy quy tắc pháp lý chỉ từ danh mục nội bộ.
- Znack tự tạo thẻ/GTIN mới khi đổi nhóm 4 số đầu, chuẩn hóa article, giày dép,
  cỡ không số và giấy tờ theo từng thẻ. Cần API/rules và idempotency đã xác minh.
- GTIN đã mở ADD WB/Ozon theo hợp đồng chính thức, giữ barcode cũ và đọc lại;
  thay/xóa barcode sàn và nghiệm thu seller thật vẫn còn thiếu.
- Nhận GTIN nhập tay và mapping lúc mua KIZ/in không quét toàn catalog lặp lại.
- FBO danh sách thiếu GTIN, không chặn SKU hợp lệ bởi SKU chưa công bố; chờ KIZ
  lưu thông, bảng mua/tiến trình, giữ mã đã in.
- GS1 còn cần nguồn mẫu 01.04.01 chính thức đã xác minh và hợp đồng kiểm tra/ký thỏa
  thuận CZ trong app, cùng nghiệm thu chứng thư/mail/doanh nghiệp chưa mở hồ sơ thật.
- Video hướng dẫn trong app, tải không nhảy bố cục và các sửa UX còn lại.

Không phát hành 1.4.0 như đã hoàn thiện các mục này nếu chúng chưa được kiểm chứng.
Không deploy website, push GitHub, sửa installer/tag đã phát hành trong đợt này.

## Đợt GS1 bổ sung

Đã triển khai readiness sáu điều kiện, cảnh báo sidebar từ cache cục bộ và nút xử lý;
INN/tên doanh nghiệp từ chứng thư thật trong CMS, khôi phục metadata certmgr thiếu;
chính sách phí gia nhập/phục hồi, trạng thái đã gửi và kiểm tra trước cả lưu/ ký;
GS1 lấy hồ sơ tự động từ National Catalog; ảnh hồ sơ chỉ minh họa vị trí trường.
Seller cung cấp DOCX đơn giản, tự điền toàn bộ GCP/ngày/người ký, tải Word để in
hoặc ký trong app và xem PDF thực tế; có đính kèm PDF/ảnh JPG/PNG đã ký, ảnh tự
chỉnh hướng. Chữ ký vẽ chỉ tồn tại trong bộ nhớ và PDF đã duyệt. Bố cục thành viên/
readiness/bảng tiền tố bên trái, hộp thư bên phải, co dọc khi cửa sổ hẹp.
Dung lượng 999/99.999 mã; nguồn/hạn mức mọi tiền tố kiểm tra lại ngay trước SMTP,
checkpoint lưu digest và review cùng transaction, draft cũ không bỏ qua guard.

Tự nhắc một thư sau 7 ngày cần khách bật và duyệt đúng nội dung/người nhận. Khi app
mở, một worker kiểm tra mỗi 10 phút; IMAP lỗi/có phản hồi, đổi hộp thư/nội dung hoặc
mất quyền sẽ dừng. Đã gửi hoặc chưa rõ kết quả không tự phát lại. Schema nhắc thư
additive, giữ request/attachment/mail và snapshot cũ.

Mẫu Word cá nhân đã gửi đọc/điền được nhưng không đóng gói hay coi là mẫu chính thức
đã xác minh; mẫu phức tạp chuyển sang PDF/ảnh đã ký. Thỏa thuận CZ mở cổng chính thức,
chưa ký trong app. Ngày Catalog chưa rõ không chứng minh đã đóng phí vô thời hạn.
Fallback hồ sơ mới chỉ áp dụng explicit non-member + không prefix + hồ sơ rỗng từ
phiên đúng chứng thư; lỗi máy chủ thực tế còn cần nghiệm thu.

Kiểm chứng đợt GS1 và bổ sung Word/giao diện: `clean verify` 1.041 đạt/1.042 tổng,
1 DPAPI Windows skip;
42/42 Node contracts; JAR Linux khởi động được. [Báo cáo](../../validation/2026-10-13-vn-code-gs1-140.md).

## Đối chiếu mới: Wbcode 1.3.8 và hai ảnh Znack

Ngày 13/10/2026 đã kiểm tra release công khai mới nhất
[1.3.8](https://github.com/rupphi/relatest-wcode/releases/tag/v1.3.8).
[Báo cáo mã/nguồn và thứ tự nâng cấp](../../research/2026-10-13-vn-code-wbcode-138-znack-audit.md).
Đã triển khai và kiểm thử cục bộ trong đợt bổ sung 14/10/2026:

- Tab Thông tin chủ tài khoản/INN, số dư CZ theo nhóm và hạn chứng thư;
  refresh trong worker, không tự yêu cầu PIN khi mở màn hình.
- Checkpoint gắn INN/môi trường/fingerprint/credential, chặn resume sai owner;
  giấy tờ theo sản phẩm được ưu tiên, giấy tờ chưa rõ cần duyệt.
- Phân trang/chọn tất cả kết quả lọc, ký các thẻ đã duyệt, đồng bộ Catalog;
  Ozon có UI/workflow riêng theo product_id/offer_id/SKU.
- Đăng ký thẻ mới WB/Ozon giữ active GTIN cũ đến khi candidate đã xuất bản
  và xuất hiện trên đúng biến thể của sàn mới chuyển mapping.
- ADD GTIN WB/Ozon theo hợp đồng chính thức, giữ barcode cũ; nguồn Catalog
  production phải đúng chủ và đọc mới. Không tự replay hoặc thay/xóa barcode sàn.
- Bulk KIZ/SQL, hàng đợi công bằng 10 shop, cửa sổ chuẩn bị Ozon hai đơn,
  PDF tuần tự, đối soát KIZ từ lịch sử in và chặn trùng tệp đầu ra.
- Mỗi khách dùng VNCode/dữ liệu riêng trên máy riêng, website license chung.
  Portal 0.3.1 có source tại website/license-portal; chủ website báo đã deploy và đăng nhập admin được, còn chờ kiểm tra nghiệp vụ license thật.

Nguồn Catalog hiện gồm nhóm may mặc và thẻ đúng chủ do VNCode biết; không công
bố đã lấy mọi nhóm bên ngoài. Quy tắc pháp lý TN VED, FBO mới, mẫu nhúng và
nghiệm thu Windows/CryptoPro/mail/seller/máy in vẫn nằm trong phần tiếp theo.
Báo cáo mới: [scaling](../../validation/2026-10-14-vn-code-140-scaling.md) và
[kiến trúc nhiều máy khách](../../architecture/vncode-140-multi-client.md).
