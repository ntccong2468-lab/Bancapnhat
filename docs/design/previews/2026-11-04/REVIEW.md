# Preview review — ready for user approval, not applied to VN Code

Reviewed on 2026-11-04 using TASTE and Impeccable. The user requires screenshots and explicit approval before replacing the application interface. This directory is a separate HTML proposal using synthetic data; its controls do not call seller APIs.

## Evidence and authority

- Product requirements: [PRODUCT.md](../../../../PRODUCT.md).
- Committed direction: [preview brief](../../2026-11-04-preview-brief.md).
- Rendered source: `index.html`, `preview.css`, `preview.js` in this directory.
- Captures: Chromium through a localhost HTTP server; 1340×865, 1440×900 and 1920×1080. See [capture report](screenshots/capture-report.json).
- No approved visual comp or external QUALITY BAR card was supplied. The reviewer assessed the user's requirements, product truth, direction contract and the two skills. No native JavaFX or API behavior is certified by these pictures.

## Incumbent findings

The released overview reserves 420px minimum / 480px preferred height in `WelcomePane`; removing the three KPI cards alone would leave the empty region. The current header moves shop actions into overflow below 1320px while retaining the stale FBS counter. The proposal removes both composition problems and keeps management next to the shop selector.

## Finish review

A separate reviewer initially requested seven corrections. They were applied together and reviewed against the new captures and source:

| Finding | Final outcome |
| --- | --- |
| Existing functions must remain discoverable | Tools and overflow expose TN VED, FBO orders, listing copying, jobs, account/balance, news, support and update. |
| Order workflow controls were incomplete | Delivery deadline, sort, KIZ/GTIN, filtered selection/clear, paging and both label/picking output have compact placements. |
| Navigation changed the shop implicitly | Shop choice is independent of route; explicit synthetic WB/Ozon choices are available. |
| Selection indication was incomplete | Mixed selection is shown; filtering and select-all preserve selected hidden rows. |
| Tab counts/status did not change | Preview tabs update their illustrative statuses and counts. |
| Finish drifted from the contract | Dark surfaces are neutral graphite, overview icons plain and prices right aligned. |
| News categories preceded their titles | Categories now follow titles. |

Final reviewer disposition: **ship**, meaning ready to show the separate preview for user approval. All seven scored issues resolved; no material regression observed. The two open-menu captures were taken after the opening animation settled. The single detector run returned `[]`; this is not an accessibility or functional certification.

Capture assertions found no horizontal overflow, missing images or page errors. The order/product layouts show seven complete data rows at 1440×900; the maximized order layout shows nine at 1920×1080, with bulk actions visible below the list. Search, filtered selection, mixed checkboxes, tab changes and cancellation of the named deletion dialog were exercised locally.

## Approval boundary

No production FXML, CSS, store record or dashboard layout was changed. The separate Java controller change in this branch concerns receipt readback only. User approval of these screenshots is still required before native UI implementation. Native keyboard/focus, localization, scaling, virtualized lists and preserved app handlers must be checked during that implementation.

## Additional screens and motion requested later in the same session

The user requested the other sections, the Lucian UX/UI reference and screenshots for approval. Added separate FBO, KIZ mapping, GS1, Znack configuration/stock, finance, history, template designer, settings, guides and tools views; see [motion contract](MOTION.md). Twenty-seven additional captures cover both themes at 1340×865, plus the order detail panel at 1440×900. The seven primary captures were refreshed as well. No clipping, missing images or script errors were observed in [the additional capture report](screenshots/extended-capture-report.json).

The extension received its own scoped finish review. Three material findings were fixed in one batch: detail identity now follows the chosen row/marketplace; configuration drafts and saved values are scoped to the selected store, with a save/cancel guard on switching; the template inspector edits its selected Name/Article component and explicitly disables unsupported content edits. Spacing was tightened so the signature-check action fits, and Article/size values have readable separators.

Final extension disposition: **ship**, for proposal readiness only. The reviewer found all scored fixes resolved and no material regression in the correction pass. [26 browser assertions](screenshots/motion-checks.json) cover retained selection/scroll/focus, per-tab state, draft save/cancel and store scope, selected KIZ identity, template target, FBO quantities, OS/manual reduced motion, script errors and absence of external/API requests. The source uses local fixtures; no native or live API result is claimed by these checks.

The motion recording is captured from this same HTML preview. Browser recording initially lacked its packaged encoder; the existing system ffmpeg was used through an isolated `/tmp` Playwright encoder path. No dependency or runtime setting in the application was changed.
