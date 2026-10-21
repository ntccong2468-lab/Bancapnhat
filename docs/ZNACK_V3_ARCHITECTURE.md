# Đăng ký Znack V3 — thiết kế và phạm vi

Ngày 21/10/2026. Yêu cầu gốc: MASTER PROMPT V3 do chủ dự án cung cấp.
Nền: VNCode v1.5.7, `9b580f42f3ea5b64a89a56591a598740cc7f5f45`.
Nhánh riêng `feature/znack-production-registration-v3`. Không merge/phát hành tự động.

Mục tiêu: seller nhập đúng biến thể WB, chuẩn bị thẻ, xem payload, xác nhận gửi,
theo dõi kiểm duyệt, xem đúng XML trước ký và đọc lại thẻ để chứng minh công bố.
Tạo nháp, gửi feed, gửi chữ ký và công bố là các kết quả khác nhau.

## Lựa chọn

1. Chọn: V3 độc lập với state machine và journal riêng, tái sử dụng
   `ZnackNationalCatalogService`, `productionCatalog`, `WbApiClient` và CryptoPro.
   Giữ dữ liệu/hàng đợi V1.5.7; mở V3 từ màn hình đăng ký để kiểm chứng trước thay thế.
2. Viết lại cả client/chữ ký: tăng rủi ro sai hợp đồng và mất bảo vệ chủ tài khoản.
3. Chỉ đổi giao diện cũ: không đáp ứng journal và các trạng thái yêu cầu.

## Thành phần

- `RegistrationState`: trạng thái và nhãn tiếng Việt, không suy diễn thành công.
- `RegistrationJob`: chủ tài khoản/phạm vi, bản chụp biến thể, draft và bằng chứng.
- `RegistrationStore`: migration cộng thêm; job, remote operation, event ghi atomic,
  kiểm soát revision và chống hai worker ghi cùng job. Không chứa khóa/PIN/token.
- `RegistrationOrchestrator`: chuẩn bị, validate, review, mutation một lần và đối chiếu.
- `RegistrationGateway`: adapter thật của client hiện hữu; test double chỉ trong test.
- `RegistrationContractPolicy`: hợp đồng chưa xác minh chặn ghi, kể cả GET cấp GTIN.
- `RegistrationV3Pane`: bảy bước tiếng Việt, công việc I/O chạy ngoài JavaFX thread.

## Bất biến

Mỗi shop/nmID/chrtID/phạm vi chỉ có một job. Import lại không cấp GTIN và không
xóa trạng thái cũ. Thay tài khoản/chứng thư/môi trường làm mất hiệu lực review.
Mỗi mutation lưu checkpoint trước gọi mạng; unknown không được replay. GTIN được
lưu ngay khi trả về. Consent khớp revision, action và SHA-256 payload/XML, hết hạn
sau 10 phút. Chỉ cho công bố khi đọc đúng GTIN/good_id từ đúng môi trường, sau thao
tác gửi và không có lỗi. Sandbox không thể thành bằng chứng production.

## Giao diện

Sản phẩm → Tạo thẻ → Kiểm tra → Gửi đăng ký → Kiểm duyệt → Ký điện tử → Hoàn thành.
Lịch sử riêng từng sản phẩm. Xác nhận cấp GTIN/gửi/ký/đồng bộ tách nhau. Không tự ký.
Không suy đoán thuộc tính pháp lý. V3 chưa thay hàng đợi Ozon cũ.

## Cổng bên ngoài

Đã đối chiếu API NK 5.68 chính thức qua docs.crpt.ru; nguồn/hash nằm trong API_CONTRACTS
và evidence/znack-v3-catalog-contracts.json. Hợp đồng ghi WB còn UNVERIFIED, sandbox
chưa có đường xác thực đã nghiệm thu; không mở bằng checkbox/cấu hình môi trường.
Thiếu Windows/CryptoPro và seller thật chặn nghiệm thu tương ứng, không thay bằng mock production.
