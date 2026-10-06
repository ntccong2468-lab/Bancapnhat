# ADR-004: JavaFX là desktop production duy nhất

## Status

Accepted — 2026-08-23

## Decision

- `Launcher` và `MainApplication` JavaFX là entrypoint production.
- Controller/FXML/theme nằm trực tiếp trong `src/main` và được Maven compile/package.
- Loại bỏ desktop stack thử nghiệm, frontend web, Gradle source sets và bridge bindings.
- Giữ nguyên Java core, schema v2, Ozon FBS, Znack, PDF và migration/snapshot safeguards.
- `jpackage` nhận runnable JAR cùng runtime dependencies từ Maven.
- Windows installer giữ Upgrade UUID đã phát hành để nâng cấp in-place từ 1.1.9.

## Consequences

Chỉ còn một UI/runtime và một build graph. JavaFX regression/FXML tests lại kiểm tra đúng artifact
production. Chức năng marketplace không được viết lại; phần presentation hiện có được chuyển nguyên
vẹn về production source set.
