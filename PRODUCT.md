# VN Code

<!-- impeccable:product-schema 1 -->

## Platform

Windows desktop, JavaFX. HTML is used only for a separate design preview; it is not the application runtime.

## Users

Vietnamese-speaking sellers and warehouse staff operating Wildberries and Ozon stores. They receive orders, pick goods, attach KIZ and print carrier labels in batches.

## Product Purpose

Manage marketplace products, FBS/FBO fulfilment, Catalog registration, GTIN links and KIZ stock in one desktop application. A completed operation requires marketplace readback, not merely an accepted HTTP request.

## Operating Context

Large order and product lists, mouse/keyboard use, normal and maximized Windows windows. Work must remain recoverable after interruption. Store identity and selection must remain clear during every batch.

## Capabilities and Constraints

Preserve existing handlers, data, licensing, confirmations and store scope. Retain both light and dark themes. Prioritize the working area for order and product lists. Keep official Ozon carrier labels unchanged and retain combined KIZ labels. The earlier 2026-11-04 interface approval is historical. On 2026-11-08 the user approved the Apple-inspired Liquid Glass desktop proposal (164 screenshots and a motion preview) and requested applying it natively, with Add shop integrated into Manage immediately beside the shop selector. That UI approval covered implementation. On 2026-11-14 the user separately requested packaging and publishing the new 1.6.10 release to Latest; publication must follow the actual build, package and release checks.

## Brand Commitments

Keep VN Code identity and Vietnamese interface. The user requests a visibly different, minimal, modern interface; remove the three overview counters and their empty space. Replace the header's FBS delay text with accessible store management and a small delete button.

## Evidence on Hand

User screenshots and repository FXML/controllers. `docs/design/vncode-workspace-system.md` records the previous implementation. Design preview values must be synthetic and labelled; no customer data or API credentials may enter screenshots.

## Product Principles

- Show the full work list and make batch outcomes explicit.
- Preserve data and task ownership across refresh and restart.
- Never claim external acceptance without readback.
- Keep store management visible and destructive actions confirmed.

## Open Decisions

The 2026-11-08 approved Liquid Glass direction supersedes the 37 historical 1.6.3 captures. DESIGN.md and the current native styles describe the implementation; .impeccable/design.json retains design metadata and preview translations. The current direction uses light/dark/system themes, floating glass on internal panels, large work lists, fields, buttons and overlays, with solid readable foreground text, contextual interruptible motion and reduced motion. Historical 43b native evidence covers 25 major routes in both themes, reports and shared controls using synthetic Linux/JavaFX profiles. It does not certify Windows DPI, marketplace APIs, installed-app performance or release readiness. Ambient lighting varies; both themes remain required. The 2026-11-14 follow-up adds shared cached app-wallpaper blur beneath chrome and overlays, with the solid fallback retained; the latest follow-up extends this material to internal working surfaces and adds explicitly owned rounded app dialogs. It does not sample the OS desktop or blur foreground product/order text. No new backend capability is implied by the UI approval.


The 43b historical checkpoint has 197 local focused cases in 20 suites and a separately reviewed Linux gallery; its later Windows full run failed. Glass must appear on newly mounted pages even with Reduced Motion and without changing themes. Official print-paper/signature content, foreground data, caller-owned paints and semantic selection/disabled states retain their intended appearance. Current evidence does not claim pixel-identical iOS rendering or live API acceptance.


The superseded f3e candidate is `f3e98725ee2892abf225cb66978ed9302fd49489`. It preserves caller-owned background history across theme rebuilds and retains the approved glass/motion behavior. Native test geometry and screen-capture fixtures were corrected without lowering their thresholds; CI verifies actual normal/reduced OS motion on an isolated worker without overriding accessibility policy in the shipped app. A real 200-case/21-suite Windows focused run has matching Java/test/build inputs; that full Windows run was later canceled after early preflight passed, before packaging, for the confirmed embedded-frame defect. Fresh security 43639388384 has 8 unchanged Java findings, including one severity 7.5 high-gate failure, and zero JavaScript findings. Finding records match 1.6.9/43b with zero new/resolved; unchanged GS1 extraction errors/warnings still limit coverage, while informational/note notifications differ. Security is not all green. The authorized Latest publication requires separate package/public/updater acceptance. The old 43b source ZIP and gallery are preserved checkpoints, not proof for this successor or live seller operations.


The current candidate is `b74b5edfd46636e8779c94f467eef01c03c50a14`. Its only delta after f3e removes an inherited painted frame from data controls embedded in a rounded working sheet and adds a real Finance/FBO pixel regression. Standalone control geometry and all business/caller policies remain unchanged. Exact-source local Maven verify passes 201 tests/21 suites with 0 failure/error/skip; the six-case material check is overlapping bounded evidence, not an added total. Fresh exact-source native capture has complete manual review of all50page originals and15video keyframes, with no concrete blocker in that bounded coverage. All25same-theme mounts and50native pixel/material gates pass; [frozen receipt](docs/validation/ios-motion-20261114/native-evidence-b74/FINAL-RECEIPT.json). Full Windows 43641520595 is still pending. Security 43641523097 retains eight Java findings/one severity 7.5 high-gate failure with zero new/resolved, zero JavaScript findings and passing lint. Its 107 changed numbered references resolve to the same full URIs/rule IDs as 1.6.9/f3e/43b; remaining finding fields, lines, codeflows and fingerprints are intact, not a raw byte-equality claim. Unchanged GS1 extraction errors/warnings still limit coverage, and informational/note notifications differ. Security is not all green; [the report](docs/validation/ios-motion-20261114/windows-b74/security/REPORT.md) preserves that limit. F3e full was canceled for this confirmed visual defect before publication; its earlier focused/preflight results are not a b74 full certificate. The authorized Latest release still needs actual package/public/updater evidence, and old source ZIPs are retained unchanged.
