# Approved VN Code workspace — native implementation

The user approved the preview and motion on 2026-11-04: “tôi duyệt hãy áp dụng vào vncode và chỉnh sửa nhận đơn FBS ozon vào bản mới luôn”. This supersedes the earlier preview-only approval boundary. The next immutable release is 1.6.3; Latest was verified as 1.6.2 before work began.

## Direction contract

THESIS: A compact warehouse workspace: open the current store, move directly to its orders, and keep list selection and batch actions in view.

OWN-WORLD: Neutral white and graphite surfaces, Inter typography, one restrained blue accent, fine rules and flat navigation. Both light and dark themes use the same structure. Preserve the VN black logo.

STORY: Find the store, open the work list, select the batch, review the explicit outcome. Store editing stays beside the selector; deletion retains its existing named confirmation.

FIRST VIEWPORT: 196px navigation, 58px header, compact title/filter rows and the rest devoted to the list. Overview has task links and news, with no metric trio or reserved blank region. Batch actions have their own layout space.

FORM: Dispatch workbench with an inset data sheet; direction seed:4d76fcce170bad27, carried from the approved preview. The native detail drawer preserves the list and focus, and settings drafts remain bound to their complete store identity. No per-row entrance animations or artificial task delays.

Seed provenance: the approved preview brief carries `seed:4d76fcce170bad27`. The local concept registry cannot corroborate its original roll in this worktree. Approval of the supplied preview is the authority; no replacement seed was rolled or provenance inferred.

## Authority and adaptation

- Approved reference: `previews/2026-11-04/` (37 captures, DESIGN.md, MOTION.md and local interactive prototype). This is an Operate, code-led build; the HTML is a composition/interaction reference, not the app runtime.
- Keep production handlers, API guards, license, data model, confirmations and virtualized controls. Never copy synthetic amounts, orders, API results or product imagery into production data.
- Existing Ozon barcode/GTIN columns, multi-item orders, KIZ controls, source tab and batch reports stay available. A single-item Ozon row uses 76px; multi-item orders grow to show every item. Product rows use 76px and Catalog rows 71px.
- Store diagnostics remain accessible in the header overflow; store management and deletion stay beside the selector. Theme changes update existing nodes in place.
- Read-only details slide for 220ms and close for 140ms, selection feedback takes 120ms, settings edit bar takes 180ms. JavaFX system reduced-motion preference or the manual setting removes spatial animation. Signing and destructive operations retain their protected dialogs.

## Verification boundary

Native JavaFX screenshots use isolated synthetic profiles, no seller credentials or remote writes. The full shell is captured at 1440×900, Ozon additionally at 1366×768 and 1920×1080, in both themes. Empty account/financial screens preserve truthful empty states instead of invented data. FXML and layout tests cover four languages, two themes and constrained windows. Native Linux renders do not substitute for the required Windows installer/migration tests.

No HTML/CSS detector is run against native JavaFX; the finish review evaluates the native captures. Ozon API evidence is separate in `docs/validation/2026-11-04-ozon-accept-picking-verified.json`: 22 receipts verified, one KIZ sample rejected, 19 carrier labels exported from one batch. Those counts are snapshots, not claims about new orders arriving later.
