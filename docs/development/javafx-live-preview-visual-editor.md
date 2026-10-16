# JavaFX Live Preview + Visual Editor — integration plan

Status: **design only; not implemented or runtime-tested**. This file deliberately makes no changes to business logic, seller APIs, SQLite, installer, or release configuration.

## Architecture decision

This repository uses Java 25, JavaFX 25, FXML, MaterialFX, Maven and SQLite, **not React**. Do not install React Rewrite, Vibedit, or Onlook into the JavaFX desktop app. Use Gluon Scene Builder / Scene Builder Kit to edit FXML; add a separate JavaFX development-only preview harness with a selectable node inspector. JavaFX CSS for styling and FXML for layout should remain the source of truth.

Do not convert desktop screens into HTML merely to use Playwright. Playwright is for browser-facing preview/editor UI and web-based supporting tools; use TestFX or equivalent JavaFX-native UI automation for desktop controls.

## Required development architecture

1. Discover all `.fxml`, JavaFX programmatic views, `.css`, UI resources, custom MaterialFX controls, and controller bindings. Produce a UI inventory before coding.
2. Keep the editor entirely in dev-only code or a separate Maven module. Never ship editor endpoints or source-writing capability in the production Windows installer.
3. Create a **detached preview scene** from an FXML source, using mock services and a test SQLite database. Never initialize production seller credentials, print hardware, network calls, live KIZ purchases or signing.
4. Detect changes to FXML/CSS with Java NIO WatchService; debounce and reload preview on the JavaFX Application Thread. Preserve selection when feasible, and display FXML parse errors without killing the preview.
5. Clicking a JavaFX Node selects it, outlines it, and shows its `fx:id`, FXML resource path, text, JavaFX CSS style/class, preferred width/height and layout constraints.
6. Apply text/size/color/position edits **in memory** first. Support Confirm / Cancel and undo. Use layout-container-specific constraints instead of indiscriminately setting `layoutX`/`layoutY` in managed panes.
7. On Confirm, update only allowable attributes in the original FXML/CSS, preserving `fx:controller`, event handlers, imports, resource references and existing business method bindings. Save atomically after producing a diff, then reparse and reload the screen. Reject edits that cannot be mapped unambiguously to source.
8. Browser-based editor pane (optional) may expose localhost-only preview and metadata via a dev-only bridge. This is a separate tool, not the production UI. Limit access to loopback, use per-session auth and restrict all writes to explicitly allowed FXML/CSS resources.
9. TestFX / native JavaFX UI tests verify desktop controls. Playwright verifies any browser editor UI, screenshots and error consoles. Playwright cannot directly inspect the native JavaFX scene graph.

## Acceptance tests

- Selecting a node highlights the **correct** control and identifies its real file.
- Changing text/color/size in preview updates visually with no disk modification.
- Cancel restores exact original scene and leaves Git working tree unchanged.
- Confirm produces a restricted FXML/CSS diff; reload matches the preview.
- Invalid FXML shows a recoverable error; no source loss.
- A WatchService code update reloads preview.
- Existing Java/JUnit tests and build pass on Windows.
- Existing seller APIs, DB schema/migrations, credential storage, label printing and signing are unchanged.
- Headless browser editor tests (if present) capture screenshots, console errors and trace on failure; native JavaFX tests cover desktop views.
- Editor is absent from release artifacts.

## Suggested execution sequence

- Phase 1: inventory UI files and build an isolated demo/fixture screen.
- Phase 2: JavaFX FXML/CSS reloadable preview; read-only first.
- Phase 3: editable scene inspector and staged changes.
- Phase 4: safe, atomic FXML/CSS source persistence with undo/cancel.
- Phase 5: TestFX + Playwright (for browser editor, if built), screenshots, CI.
- Phase 6: connect to Codex development workflow and document preview access; use split pane only when the host supports it.

## Environment limitations

Codex Web's ability to embed JavaFX desktop processes must not be assumed. A native JavaFX window on a Windows development machine, a separate Scene Builder window, or an explicitly built localhost browser editor are valid alternatives. Do not claim to have a working preview URL until the server is actually started and reachable.
