# Nghiệm thu ghi thật sản phẩm và Catalog — 03.11.2026

Theo yêu cầu “nghiệm thu ghi thật”, đã chạy các service/adapter production của VN Code bằng API được cấu hình trong môi trường. Thực hiện trên dữ liệu kiểm thử cục bộ tách biệt; không mở dữ liệu cài đặt seller. Nhánh `fix/product-edits-catalog-20261103`, [PR #17](https://github.com/ntccong2468-lab/Bancapnhat/pull/17). Đây là nghiệm thu API, chưa phải nghiệm thu thao tác Windows hoặc phát hành bộ cài.

## Kết quả ghi lên hai sàn

| Thao tác | WB | Ozon |
| --- | --- | --- |
| Thương hiệu | Ghi 1 lần, RECONCILED | Ghi 1 lần, RECONCILED |
| Chất liệu | Ghi 1 lần, RECONCILED | Ghi 1 lần, RECONCILED |
| Giới tính | Ghi 1 lần, RECONCILED | Ghi 1 lần, RECONCILED |
| TN VED | Ghi 1 lần, RECONCILED | Ghi 1 lần, RECONCILED |
| Giấy tờ hiện có | Ghi 1 lần, RECONCILED | Ghi 1 lần, RECONCILED |

Tổng cộng **10 yêu cầu ghi thật, 10 lượt đọc lại xác nhận giá trị đích**. Để tránh đưa dữ liệu kinh doanh mới chưa được chỉ định lên bài đăng, các lượt này gửi lại giá trị đang có. Kết quả chứng minh đường ghi API và đối soát hoạt động với hồ sơ thử; không chứng minh việc thay từng trường sang một giá trị kinh doanh mới hoặc mọi danh mục trên sàn.

Luồng chạy: `ProductBulkEditService` → `ProductEditApiSession` → adapter WB/Ozon → `ListingHttpClient` → API thật → trạng thái tác vụ và sản phẩm đọc mới. Không gọi API ghi thay thế để bỏ qua service, claim hay kiểm tra nguồn. Manifest lưu job/receipt trước khi chuyển bước; các lượt đã RECONCILED không được apply lại.

Một lượt đọc mới cuối cùng ở mỗi sàn xác nhận danh tính, tên, mô tả, thương hiệu, danh mục, thuộc tính, biến thể/barcode, ảnh, kích thước kiện, giấy tờ và metadata giá giữ nguyên so với trước thử. Đây là 12 nhóm kiểm tra mỗi sàn; không dùng HTTP 200 thay cho xác nhận giá trị.

## Lỗi Ozon được phát hiện và sửa trong lượt thật

Sau lượt thương hiệu, Ozon thay trạng thái kiểm duyệt và đảo thứ tự thuộc tính đơn dù các giá trị không đổi. Hash phiên bản trước đây coi đó là sửa sản phẩm, khiến lượt chất liệu bị chặn `Product changed; preview again` **trước khi ghi**. Lượt bị chặn có 0 mutation, trạng thái `AWAITING_CONFIRMATION`; giữ nguyên job cũ và tạo preview mới sau khi sửa, không gửi lại một yêu cầu chưa rõ kết quả.

Nguồn sửa `f69f125d533e0f093c52a7650963195f3527c49c`:

- Hash bỏ riêng metadata `info.statuses` và `attributes_with_defaults`; chuẩn hóa thứ tự thuộc tính đơn theo ID.
- Giữ response nguyên gốc trong `metadata.source.payload`. Thứ tự nhóm phức hợp, giá trị và ảnh vẫn có ý nghĩa; danh tính, giá, category và nội dung thực vẫn làm đổi revision.
- Một preview chưa gửi lưu bằng cách hash cũ có thể cần lập lại preview sau nâng cấp. Không sửa/xóa hàng đợi cũ hoặc coi việc đổi thuật toán hash là quyền gửi lại yêu cầu đã gửi.

Test mới tái hiện **1 thất bại/9 ca** trước sửa. Sau sửa, **82/82 ca đạt**, không lỗi/thất bại/bỏ qua, gồm revision, snapshot reader, adapter và lô 1.000 sản phẩm. Sau đó chất liệu, giới tính, TN VED và giấy tờ Ozon đều ghi thật và RECONCILED. WB và lượt thương hiệu Ozon chạy từ `97a021c`; bốn lượt Ozon còn lại và Catalog chạy từ nguồn sửa `f69f125`.

## Đăng ký một thẻ Catalog thật

1. Đọc đủ danh sách và chi tiết **1.379 thẻ**, kiểm tra danh tính bằng quy tắc chuẩn hóa article của app, màu và size, trên cả draft và published; không có thẻ trùng biến thể thử.
2. Đọc lại đúng biến thể WB; lấy TN VED từ sản phẩm, không lấy từ giấy tờ. Category được resolver đối chiếu với danh sách Catalog; trường bắt buộc và từ điển nước sản xuất đọc từ API hiện hành. Giấy tờ được registry xác minh trước gửi.
3. Qua `ZnackNationalCatalogService.generateFreshOne`, xin **1 GTIN mới** và lưu trước khi gửi. Không dùng lại GTIN của một draft chưa chứng minh thuộc biến thể thử.
4. Qua `ZnackNationalCatalogService.submit`, gọi **1 lần `POST /v3/feed`**, nhận và lưu `feed_id`.
5. Đọc `GET /v3/feed-status` và `GET /v3/feed-product`: thẻ thật tồn tại, đúng GTIN/`good_id`, article, màu và size; trạng thái **draft / moderation (`CardModeration`)**. Chưa ký và chưa công bố.
6. Đọc độc lập hai endpoint bằng client khác, cả hai HTTP 200; kiểm tra danh tính, TN VED và các thuộc tính đã gửi. Nước sản xuất được gửi bằng ISO trong từ điển, response hiển thị tên nước; tên đó khớp nguồn sản phẩm đã xác minh. Các trường khác khớp chính xác.

Lần đọc đầu sau receipt trả 404 khi thẻ chưa đọc được. Giữ nguyên GTIN/feed, chạy lại bước đọc; không allocation hoặc submit lần hai. Biên nhận riêng vẫn được giữ để phục hồi và đối soát.

Thẻ thử dùng API key Catalog trong môi trường, không dùng `omsId`/`omsConnection`. Không tự ký, không đồng bộ GTIN của thẻ mới lên sàn trước công bố. Không gửi ảnh tùy chọn trong lượt này; không thể dùng kết quả này để chứng minh Catalog tải được URL ảnh WB. Không chạy bước kết nối chứng thư/đối chiếu doanh nghiệp CryptoPro trong UI; kết quả chỉ xác nhận service đăng ký bằng key được cung cấp, chưa xác nhận toàn bộ màn hình đăng ký trên máy seller.

## Dữ liệu và giới hạn bàn giao

- [Biên nhận đã loại dữ liệu seller](2026-11-03-product-edit-catalog-live-verify.json) lưu số lượt, trạng thái và phạm vi. API key, dữ liệu sản phẩm, GTIN, `good_id`, `feed_id`, raw response và SQLite không đưa vào repository.
- Hồ sơ/manifest riêng ở `/tmp/vncode-live-acceptance-20261103`, quyền truy cập hạn chế. Không chạy lại nguyên runner: đọc manifest trước; một marker allocation/feed chưa có receipt phải đối soát, không tự gửi lại. `/tmp` có thể mất khi môi trường được thay.
- Dùng authority license kiểm thử có chữ ký trong profile riêng, theo phạm vi đã được phép; không kích hoạt license thật hoặc bỏ kiểm tra license production. Không gửi Gmail, mua/tiêu thụ KIZ, nhận đơn hoặc ký bằng chứng thư seller.
- Lượt đọc bổ sung từ điển sau kiểm chứng gặp HTTP 429. Không thử lại ghi; điều này không thay thế hoặc phủ nhận từ điển đã được service đọc/xác minh trước allocation và submit. Luôn giữ cooldown nếu tiếp tục đọc.
- Chưa nghiệm thu bộ cài Windows, đăng ký bằng nút UI, ký/công bố hoặc cập nhật GTIN mới lên sàn. Không thay Latest; không coi trạng thái moderation là published. Quyết định kiểm duyệt thuộc Catalog.
