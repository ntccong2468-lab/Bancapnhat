# Motion proposal — Lucian UX/UI + TASTE + Impeccable

User-requested reference: <https://lucianlabs.dev/uxui-prompt/>, read on 2026-11-04 (HTTP 200). The gallery and its structured public catalog were read as design material, not executed or installed. This extension remains a separate preview awaiting user approval.

## Motion thesis

The focal interaction is the packing workbench: select a row, open its details beside the still-visible list, and return to the same selection and scroll position. Motion explains where the detail belongs. Saved changes, bulk selection and tabs give immediate state feedback without making the seller wait for decorative page entrances.

- Continuity: a right-hand detail panel enters in 220 ms and exits in 140 ms; no list reload. Tab indication moves in 160 ms. Shop scope remains independent of navigation.
- Feedback: selection and buttons acknowledge in 100–140 ms. A 180 ms save bar appears only after a local draft changes. A field error focuses its field and stays readable; no shaking or flashing.
- Budget: animate opacity, short transforms and the tab underline only; no per-row stagger, layout animation over a thousand orders, artificial processing countdown, perpetual loops, backdrop blur or new animation library. Event handlers remain available during transitions; repeated commands cancel the previous visual transition.
- Reduced motion: respect the OS preference and a preview setting; remove spatial motion and keep immediate text/color feedback. Opening/closing details preserves keyboard focus. Preview state is local and does not represent live API progress.

## Reference mapping

| Lucian element | VN Code adaptation |
| --- | --- |
| 273 — Detail side panel from a list row | Order and KIZ details beside the list, selected row stays visible. |
| 039 — Sliding pill tabs | A restrained underline, using VN Code's existing flat tabs. |
| 178 — Bulk selection dock | Dedicated bottom action strip, selection count changes in place. |
| 250 — Unsaved changes bar | Configuration draft exposes Save / Cancel immediately. |
| 244 — Form validation that takes you to the error | Native integration proposal: field-specific errors and focus. This preview focuses the edited field and protects unsaved drafts; it does not validate real API credentials. |
| 269 — Type-the-name delete confirmation | Named store deletion remains explicit; no deletion of a real shop in the preview. |
| 075 — Upload progress panel | Native integration reference for import results. The current preview shows the import entry point only, without reading a file or simulating an API percentage. |
| 227 — Real-time table with incoming orders | Retain selection/scroll and indicate arrivals; no autoplay insertion of fake orders. |

Implementation is authored for this preview; gallery components, colors, photos and scripts are not copied into the app. TASTE supplies the compact flat workspace; Impeccable supplies interaction/state and reduced-motion review. Approval of the screenshots is still pending. JavaFX implementation must later use its own transitions and runtime checks.
