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

Preserve existing handlers, data, licensing, confirmations and store scope. Retain both light and dark themes. Prioritize the working area for order and product lists. Keep official Ozon carrier labels unchanged and retain combined KIZ labels. User approval of screenshots is required before applying the new UI to application code.

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

The proposed visual design remains unapproved. Ambient lighting varies; both themes are required by the user. No new backend capability is implied by a preview.
