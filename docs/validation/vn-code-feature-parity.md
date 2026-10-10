# Kiểm tra bảo toàn chức năng ứng dụng tham chiếu

Ngày: 06/10/2026. Nguồn: `rupphi/test-wcode`, commit gốc `8f7c3d1`, phiên bản
1.1.32; nhánh triển khai `feat/windows-gtin-sync`. Giữ Java 25, JavaFX/FXML,
SQLite, entrypoint và Maven/jpackage của ứng dụng tham chiếu. Không thay thế các service
nghiệp vụ bằng luồng giả lập.

| Chức năng hiện có | Đường đi sau thay đổi | Bằng chứng và giới hạn |
| --- | --- | --- |
| Thêm/sửa/xóa shop WB/Ozon, token/client-id, chọn shop | `HomeController` → `ShopWorkflow`, header/sidebar, `ShopDialogController` | Callback cũ giữ nguyên; FXML, shop/credential và marketplace tests qua |
| Tài chính WB, báo cáo, quảng cáo, scheduler | Route `finance` → `FinanceDashboardController` | Các controller/service và scheduler cũ giữ nguyên; full regression qua |
| WB FBS: đơn hàng, supply, PDF/Excel, KIZ, xuất/in | Routes `supplies`, `packing` và supply detail | Callback export/print/attachment cũ giữ nguyên; packing/PDF/supply tests qua |
| WB FBO/FBW: in sản phẩm, supply orders | Routes `fbo-packing`, `fbo-orders` | Controller cũ giữ nguyên; FBO/FXML tests qua |
| Ozon FBS Standard, sản phẩm, posting, nhãn/in, KIZ | Route `ozon`; packing chuyển tới Ozon queue | Adapter và giới hạn cũ giữ nguyên; Ozon tests qua. Không mở thêm Ozon FBO/rFBS/multibox |
| Ánh xạ KIZ và giới tính | Route `kiz-mapping` | Editor và service cũ giữ nguyên; editor/mapping tests qua |
| Znack/SUZ tự động hóa, pipeline, đăng ký thẻ National Catalog | Routes `znack`, `znack-registration` | Controller và pipeline cũ giữ nguyên; Znack/registration tests qua |
| CryptoPro và cấu hình chứng thư | Dialog/flow hiện có | Không đổi tích hợp; chưa chạy với chứng thư và CryptoPro Windows thật |
| Lịch sử in/in lại; thiết kế mẫu in | Route `print-history`; settings dialog | Callback in lại/designer giữ nguyên; FXML/printing tests qua |
| License, update có chữ ký, About, theme, ngôn ngữ | Dialog và listener hiện có | Không bỏ kiểm tra license/update; regression và Node contracts qua |
| Khóa app-data, snapshot, rollback, nâng schema, từ chối schema mới hơn | Entry/lifecycle và recovery hiện có | Recovery/migration tests qua; schema 2→3 có snapshot giữ schema 2 và shop cũ |
| Module mới GTIN | Route `gtin-sync` → `GtinSyncController` | Matching, source, ownership, preview, SQLite queue, export, controller và FXML tests qua; gửi production đang khóa |

`WorkspaceNavigator` quản lý đăng ký và tái sử dụng view. HomeController vẫn
giữ shop/lifecycle và các callback nghiệp vụ; đây là tách phần điều hướng,
chưa phải phân rã toàn bộ controller. Test navigation kiểm tra 11 route,
cache và access guard. Các kiểm tra license cũ vẫn ở nơi thực hiện nghiệp vụ;
module GTIN dùng `LicenseState.kizAllowed()` trước xác nhận mutation.

## Kết quả thực tế

- `DISPLAY=:99 /workspace/wcode-setup/mvn clean verify`: exit 0; **516 tests,
  failures 0, errors 0, skipped 0** trong lượt triển khai ban đầu. Lượt kiểm tra
  lại trước khi đẩy nhánh qua **518 tests, failures/errors/skipped 0** sau khi
  bổ sung bảo vệ xóa shop có tác vụ GTIN chưa kết thúc. JavaFX chạy trên Xorg dummy Linux.
- `node --test tools/*.test.mjs`: exit 0; **17 pass, 0 fail**.
- `git diff --check`: exit 0.
- Mọi fixture dùng app-data tạm và credential giả. Không chạy mutation seller.
- Baseline trước sửa: 476 tests, không có assertion failure, có một lỗi dọn
  thư mục tạm trong `ZnackGtinWorkflowTest.legacyHttp422IntroductionIsRetriedOnceAfterEndpointCorrection`.
  Retry riêng qua; full verify cuối cùng qua toàn bộ mà không vô hiệu hóa test.

Không suy rộng các kết quả trên thành kiểm chứng trực tiếp tài khoản seller,
máy in thật, CryptoPro hoặc installer Windows. Các bước chưa chạy ghi trong
`windows-gtin-acceptance.md`.
