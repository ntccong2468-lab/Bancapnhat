# JavaFX Live Preview (isolated development tool)

This standalone tool loads only safe FXML without controllers or scripts. It is **not connected to VN Code's production app, SQLite, seller APIs or credentials**. It starts with `sample/dashboard.fxml`. Work on copies of app views: arbitrary production FXML with controllers or custom tags is intentionally rejected.

Prerequisites: JDK 25, Maven and graphical desktop (Windows 10/11 preferred).

```powershell
cd tools/live-preview
mvn javafx:run
```

Choose a control in the right-side preview. Change its text, inline JavaFX CSS style, preferred width and height in the inspector; **Apply (preview)** to inspect without writing, **Save to FXML** to persist, or **Cancel** to discard. The file watcher reloads external changes. Only direct FXML `fx:id` attributes with unambiguous matching source nodes are persisted; if unavailable, saving fails safely.

A preview window is native JavaFX and cannot automatically embed in Codex Web. The app is not running in this chat and no public preview address is available. Playwright is appropriate for a future browser-hosted editing interface, not the JavaFX scene graph itself. Native GUI tests should use TestFX. No production files are modified by this module.
