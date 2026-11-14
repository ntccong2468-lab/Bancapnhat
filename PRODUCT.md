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

The 2026-11-08 approved Liquid Glass direction supersedes the 37 historical 1.6.3 captures. DESIGN.md and the current native styles describe the implementation; .impeccable/design.json retains design metadata and preview translations. The current direction uses light/dark/system themes, floating glass on internal panels, large work lists, fields, buttons and overlays, with solid readable foreground text, contextual interruptible motion and reduced motion. Final 43b native evidence covers 25 major routes in both themes, reports and shared controls using synthetic Linux/JavaFX profiles. It does not certify Windows DPI, marketplace APIs, installed-app performance or release readiness. Ambient lighting varies; both themes remain required. The 2026-11-14 follow-up adds shared cached app-wallpaper blur beneath chrome and overlays, with the solid fallback retained; the latest follow-up extends this material to internal working surfaces and adds explicitly owned rounded app dialogs. It does not sample the OS desktop or blur foreground product/order text. No new backend capability is implied by the UI approval.


The current runtime/test source is `43b82cca683b29e783a33df995afd675a76aa0dd`. Its focused verification is 197 cases in 20 suites; Windows full verification and the requested 1.6.10 release are tracked separately in the handoff. Glass must appear on newly mounted pages even with Reduced Motion and without changing themes. Official print-paper/signature content, foreground data, caller-owned paints and semantic selection/disabled states retain their intended appearance. Current evidence does not claim pixel-identical iOS rendering or live API acceptance.
