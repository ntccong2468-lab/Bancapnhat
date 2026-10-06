# Repository guidance

WCode là ứng dụng Java 25 + JavaFX cho Wildberries và Ozon FBS. Maven là build system duy nhất.
JavaFX/FXML trong `src/main` là production UI; không có frontend web hoặc desktop runtime thứ hai.

## Commands

```bash
./mvnw -B clean verify
./mvnw javafx:run
node --test tools/*.test.mjs
./build.sh app-image
build.bat app-image
```

## Architecture

- Entry: `com.tuandev.fbsbarcode.Launcher` → `MainApplication`.
- UI/FXML: `src/main/java/.../ui` và `src/main/resources/.../ui`.
- Core: `features`, `integration`, `models`, `shared`, `config`.
- WB và Ozon có adapter/schema/state riêng; dispatch bằng `Marketplace` bất biến.
- Maven tạo runnable JAR, copy runtime dependencies và cấp input cho `jpackage`.

## Data and security invariants

- Không giả định database mới. Migration phải additive, có test v1→v2, snapshot đã verify và
  `foreign_key_check`/`integrity_check` sạch.
- Giữ app-data lock trong suốt vòng đời app. Binary gặp schema mới hơn phải fail closed.
- Không log/return API key, license secret, raw KIZ, raw upstream response hoặc PII.
- Kiểm tra marketplace và shop ownership trước mọi repository/API operation.
- Mutation seller state phải có confirmation, idempotency và reconciliation sau timeout.
- Test luôn dùng `wcode.appdata.dir` tạm; không chạy test trên `app/database.db` thật.
- User-facing copy phải đồng bộ RU/EN/VI/ZH khi thay đổi UI.

## Verification

Java/domain changes cần targeted tests rồi full `./mvnw -B clean verify`. FXML/controller changes cần
`FxmlSmokeTest`. Packaging changes cần Node contract tests, Maven verify và app-image trên đúng hệ
điều hành. Live marketplace mutation chỉ chạy khi người dùng phê duyệt đúng fixture cụ thể.
