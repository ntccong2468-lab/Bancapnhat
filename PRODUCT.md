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

Preserve existing handlers, data, licensing, confirmations and store scope. Retain both light and dark themes. Prioritize the working area for order and product lists. Keep official Ozon carrier labels unchanged and retain combined KIZ labels. The earlier 2026-11-04 interface approval is historical. On 2026-11-08 the user approved the Apple-inspired Liquid Glass desktop proposal (164 screenshots and a motion preview) and requested applying it natively, with Add shop integrated into Manage immediately beside the shop selector. This approval covers UI implementation, not an automatic publication of a new Latest release.

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

The 2026-11-08 approved Liquid Glass direction supersedes the 37 historical 1.6.3 captures. DESIGN.md and .impeccable/design.json describe the native implementation: light/dark/system themes, bounded glass paints on chrome and overlays, opaque large work lists, contextual interruptible motion and reduced motion. Current native evidence uses synthetic profiles on Linux/JavaFX; it does not certify Windows DPI, marketplace APIs, installed-app performance or release readiness. Ambient lighting varies; both themes remain required. Native JavaFX uses a painted material fallback rather than promising web backdrop blur. No new backend capability is implied by the UI approval.
