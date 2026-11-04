# VN Code workspace preview — awaiting seller approval

Scope: a separate HTML visual prototype, not application FXML/CSS. All values are synthetic. The existing JavaFX application remains the authority for capabilities. The user specifically requires screenshots and approval before any visual implementation.

The previous interview and current request establish Windows desktop, Vietnamese sellers, two themes, full-size order/product lists, all existing functions retained, no overview KPI trio or forced empty space, and visible add/edit/delete store actions in the header.

## Direction contract

THESIS: A compact warehouse workspace: open the current store, move directly to its orders, and keep list selection and batch actions in view.

OWN-WORLD: Neutral white and graphite surfaces, ink typography, one restrained blue accent, fine rules and flat navigation. Inter from the application bundle; small line icons and tabular order numbers. Sellers work in both bright and dim packing spaces, so the same structure has light and dark themes.

STORY: Find the store, open the work list, select the batch, review its explicit outcome. Store editing stays beside the selector; deletion requires a named confirmation.

FIRST VIEWPORT: 196px navigation, 56px store header, compact title/filter rows; the remaining workspace belongs to the list. Overview begins with existing workflow links on the left and news on the right. No metric-card row. Batch actions occupy a dedicated bottom strip.

FORM: Fourth grounded direction, a dispatch workbench with an inset data sheet. Ordered candidate families: neutral ledger, compact command rail, split operations desk, dispatch workbench, document index, inventory browser, monochrome work queue. seed:4d76fcce170bad27. The signature interaction is a compact store-management popover beside the active store, with a separate small delete control.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

## Preview acceptance

- Capture overview light/dark, Ozon order list, product list and store controls.
- 1440×900 and the user's approximately 1340px-wide viewport, plus a maximized list.
- All preview actions are local simulations; no API calls or real store changes.
- Application UI changes remain pending user approval of these screenshots.
- Documentation of this proposal is scoped to the preview directory and does not replace the application design system before approval.

## Existing implementation audit

TASTE and Impeccable review found the overview blank area is explicitly allocated by `WelcomePane`: min height 420, preferred 480. Removing the three cards alone cannot fix it. Header store actions currently move into overflow under 1320px, while the stale FBS counter remains visible. The preview reverses that priority.
