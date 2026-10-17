# Đối chiếu bảy sửa đổi VNCode 1.4.16

Nguồn kiểm tra lại ngày 17/10/2026:

- WCode 1.0.27: commit `457a6113301978454f244248956962ab1fcdeb7e`, checksum theo `vncode-1412-wcode-category-sources.json`; tải lại `KizService`, `KizInventoryWorkflow`, `CategoryRepository`, `KizAttachmentCoordinator`, checksum khớp. Kho cũ lấy FIFO theo shop/category và xóa mã đã dùng. VNCode giữ trạng thái/journal nội bộ để chống tái sử dụng nhưng chỉ hiển thị số còn trong shipment.
- Nhánh nguồn công khai `rupphi/test-wcode` vẫn ở `8f7c3d1ac8598158029b8d037e2d3c128e240727`. Đây là nguồn cũ, không phải mã nguồn WBCode 1.3.9. Đọc lại controller đăng ký, HomeController và các tệp kho KIZ.
- WBCode Latest công khai vẫn là `v1.3.9`, xuất bản `2026-10-15T16:56:43Z`. Căn cứ quan sát gói Windows của lượt 1.4.14 và ảnh chủ ứng dụng gửi; không tuyên bố đã có mã nguồn nội bộ mới nhất.

| Yêu cầu | Thay đổi ở VNCode | Kiểm chứng |
|---|---|---|
| Tin trong khung, dịch Việt tự động | Khung cuộn riêng, giới hạn chiều rộng graphic theo nút; dịch không cần khóa riêng, cache theo nguồn/revision, giữ số/ngày/link, worker riêng | NewsPaneLayoutTest; AutomaticNewsTranslatorTest; NewsTranslationServiceTest; yêu cầu dịch công khai HTTP 200 |
| Sửa sản phẩm hàng loạt như ảnh | Điều hướng mở hộp thoại theo trường, áp dụng một lần; kiểm tra tươi và đọc lại nội bộ; TN VED có chips/lọc/gợi ý từng sản phẩm | ProductBulkDialogTest áp dụng thật trên transport fixture cho hai thẻ; bộ ProductBulkEditService/ProductTnved |
| GTIN chạy nền | Dùng pipeline Catalog/GTIN hiện có, chỉ GTIN append; tập biến thể đóng băng; không mở dialog; pill dưới giữa bảng | ZnackGtinBackgroundServiceTest: 650 dòng, phân trang, giới hạn child, không ghi shop khác/không replay; GtinBackgroundStatusTest: hai view cùng nhận kết quả |
| Tự chọn danh mục Znack | Resolver chỉ chọn trong preflight Catalog theo loại hàng sàn; khớp duy nhất mới chọn; WB/Ozon cùng dùng | ZnackCategoryResolverTest; OzonCardRegistrationWorkflowTest |
| Nhập KIZ không hiện số | Không reset context khi FBS đưa DTO cùng shop/shipment; nhập và trả snapshot số còn trong một tác vụ; poll cục bộ chỉ đọc; bỏ cột đã dùng | WbShipmentKizPaneTest: context refresh trong lúc nhập, reserve/consume, nhập lại; quan.txt thật đọc/nhập 600 mã trong DB fixture |
| Kính trong như giọt nước | Alpha surface 0.035 ở mức 0; chữ gần đục, màu tương phản; nút dùng vật liệu kính | ThemeServiceTest: sáng/tối × 0/55/100, đo primary ≥7 và controls ≥4.5; xem PNG render thực |
| Đăng bài một lần bấm | Bỏ preview/confirm khỏi UI; chuẩn bị nội bộ rồi gửi đúng một lần, đọc lại; progress và job states | ListingCopyPaneTest: gửi từng sản phẩm, giữ cấu hình/giá biến thể, rời view không mất job, lỗi một sản phẩm không mất kết quả khác |

Không dùng bản ghi sản phẩm/giấy tờ trong ảnh làm dữ liệu mặc định. Không ghi mã KIZ trong tệp đính kèm, token hoặc API key vào repo. Những thao tác từ thử nghiệm bằng API giả lập không được ghi là đã đăng ký/ký thẻ hoặc gán mã vào đơn thật.
