# Ghép Catalog–Ozon — ảnh dữ liệu tổng hợp

Chụp bằng `OzonCatalogVariantLinkDialogTest` trên JavaFX/Linux/Xvfb sau đề xuất một cặp khác article nhưng cùng màu/size. Chưa đọc API thật, chưa lưu liên kết hoặc ghi GTIN. Mã mẫu/GTIN trong ảnh là fixture tổng hợp, không phải dữ liệu seller. Ngôn ngữ kiểm thử mặc định Nga; app có chuỗi vi/en/ru/zh.

- [Nền tối](ozon-catalog-link-dark.png)
- [Nền sáng](ozon-catalog-link-light.png)
- [Startup JAR production, profile trống](startup.png): không có cửa hàng, không giao dịch seller; xem biên nhận startup trong verification.json.

Test kiểm list cao hơn 300 px và các nút trong viewport; ảnh không chứng minh giao dịch API hoặc Windows đang cài.
