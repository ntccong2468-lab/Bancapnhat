---
name: VN Code — Desktop Preview Proposal
description: PROPOSAL awaiting user screenshot approval; synthetic desktop sections and purposeful motion extracted on 2026-11-04.
colors:
  bg: "#f4f6f9"
  surface: "#fff"
  nav: "#f9fafc"
  ink: "#172537"
  muted: "#5d6a7b"
  line: "#dce2ea"
  soft: "#eef2f6"
  accent: "#175bc5"
  selected: "#e8f0ff"
  selected-ink: "#174b9a"
  success: "#176749"
  warn: "#865600"
  danger: "#b52b3c"
  dark-bg: "#141414"
  dark-surface: "#1b1b1b"
  dark-nav: "#171717"
  dark-ink: "#edf2f7"
  dark-muted: "#a8b5c6"
  dark-line: "#383b40"
  dark-soft: "#25272b"
  dark-accent: "#285fc7"
  dark-selected: "#233c5b"
  dark-selected-ink: "#bedaff"
  dark-success: "#8fdbbb"
  dark-warn: "#f2c579"
  dark-danger: "#ffb0b9"
  on-accent: "#fff"
  focus-ring: "#3575d3"
  checkbox-outline: "#8190a2"
typography:
  headline:
    fontFamily: "Inter, Arial, sans-serif"
    fontSize: "25px"
    fontWeight: 700
    lineHeight: 1.3
    letterSpacing: "-0.03em"
  title:
    fontFamily: "Inter, Arial, sans-serif"
    fontSize: "16px"
    fontWeight: 700
    lineHeight: 1.4
  body:
    fontFamily: "Inter, Arial, sans-serif"
    fontSize: "14px"
    fontWeight: 400
  label:
    fontFamily: "Inter, Arial, sans-serif"
    fontSize: "12px"
    fontWeight: 400
  control:
    fontFamily: "Inter, Arial, sans-serif"
    fontSize: "14px"
    fontWeight: 600
  row-title:
    fontFamily: "Inter, Arial, sans-serif"
    fontSize: "13px"
    fontWeight: 600
  navigation:
    fontFamily: "Inter, Arial, sans-serif"
    fontSize: "13px"
    fontWeight: 500
  form:
    fontFamily: "Inter, Arial, sans-serif"
    fontSize: "13px"
    fontWeight: 400
  reading:
    fontFamily: "Inter, Arial, sans-serif"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.8
  drawer-title:
    fontFamily: "Inter, Arial, sans-serif"
    fontSize: "18px"
    fontWeight: 700
    lineHeight: 1.4
  summary-number:
    fontFamily: "Inter, Arial, sans-serif"
    fontSize: "24px"
    fontWeight: 500
rounded:
  detail: "4px"
  control: "6px"
  surface: "8px"
  dialog: "10px"
spacing:
  tight: "4px"
  detail: "6px"
  control: "8px"
  field: "10px"
  cell: "12px"
  section: "16px"
  workspace: "22px"
  group: "24px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.on-accent}"
    typography: "{typography.control}"
    rounded: "{rounded.control}"
    padding: "8px 12px"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.control}"
    rounded: "{rounded.control}"
    padding: "8px 12px"
  button-secondary-hover:
    backgroundColor: "{colors.soft}"
  button-quiet:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "8px 12px"
  button-danger:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.danger}"
    typography: "{typography.control}"
    rounded: "{rounded.control}"
    padding: "8px 12px"
  input-search:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.control}"
  navigation:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    typography: "{typography.navigation}"
    rounded: "{rounded.control}"
    padding: "10px 12px"
  navigation-active:
    backgroundColor: "{colors.selected}"
    textColor: "{colors.selected-ink}"
  marketplace-tag:
    backgroundColor: "{colors.selected}"
    textColor: "{colors.selected-ink}"
    rounded: "3px"
    padding: "3px 5px"
  data-sheet:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.surface}"
  store-popover:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.surface}"
    padding: "8px"
    width: "280px"
  selection-checkbox:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.detail}"
    padding: "0"
    width: "17px"
    height: "17px"
  selection-checkbox-checked:
    backgroundColor: "{colors.accent}"
  detail-panel:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    width: "400px"
  draft-footer:
    textColor: "{colors.ink}"
    typography: "{typography.form}"
    padding: "12px 0 0"
  workspace-tabs:
    backgroundColor: "transparent"
    textColor: "{colors.muted}"
    typography: "{typography.control}"
    rounded: "0"
    padding: "0 2px 13px"
  workspace-tabs-active:
    textColor: "{colors.ink}"
  template-workspace:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.surface}"
---

# Design System: VN Code — Desktop Preview Proposal

## Overview

**Creative North Star: "Dispatch Workbench"**

Dispatch Workbench is the proposed visual direction for VN Code's separate HTML desktop preview. It is a **PROPOSAL awaiting user screenshot approval**, with synthetic store, order and product data. The calm white and graphite surfaces, restrained blue selection, fine rules and bundled Inter typography support a compact, readable workspace for Vietnamese-speaking sellers and warehouse staff.

The component character is precise and restrained: flat controls, clear text states, inset data sheets, a small store-management popover and a detail panel beside the working list. Short motion connects the selected item to its detail and makes local changes visible without staging a page entrance. Light and dark themes share the same structure. This document governs only the preview directory; [the incumbent workspace system](../../vncode-workspace-system.md) remains the production design authority. The current [index.html](index.html) is a code-led desktop visual mock, not the JavaFX application runtime or an implementation approval.

Tokens were extracted from [preview.css](preview.css) and [more-screens.css](more-screens.css), behaviors from [preview.js](preview.js) and [more-screens.js](more-screens.js), and checked against representative [captured screens](screenshots/). Product facts and the chosen direction come from [PRODUCT.md](../../../../PRODUCT.md) and the [preview brief](../../2026-11-04-preview-brief.md). Motion references and adaptation decisions are recorded in [MOTION.md](MOTION.md), including the user-requested [Lucian UX/UI catalog](https://lucianlabs.dev/uxui-prompt/). The catalog informs interaction purpose; this document records the authored preview implementation. Review outcomes remain in [REVIEW.md](REVIEW.md) at their stated scope and never replace user screenshot approval or native runtime verification.

The [screenshot gallery](gallery.html) and [motion recording](motion-demo.mp4) form the user-review evidence. The extension's final reviewer disposition is `ship` for proposal readiness only, with its three scored material fixes resolved. The proposal still awaits the user's approval before any production CSS or FXML work.

**Key Characteristics:**

- Neutral white and graphite grounds with one blue action and selection family.
- Dense, legible lists with tabular numbers and persistent batch controls.
- Flat navigation and fine dividers; elevation reserved for temporary overlays.
- Visible store identity and management controls, with a named delete confirmation.
- Locally served Inter, line icons, and labelled synthetic content in both themes.
- Short detail, tab and save feedback, with OS and local reduced-motion controls.

## Colors

The palette combines cool paper and ink in the light theme with nearly neutral graphite in the dark theme; blue carries action and selection, while semantic colors communicate written states.

The frontmatter is the normative token snapshot. Unprefixed tokens come from `body`; `dark-` tokens come from `body.dark`. Both sets bind to the same CSS custom-property names at runtime, for example `accent` and `dark-accent` both supply `--accent`. Component frontmatter expresses the default light theme; the sidecar snippets inherit the active CSS variables. `on-accent`, `focus-ring` and `checkbox-outline` are shared literal colors.

### Primary

| Color character | Light token | Dark token | Use |
| --- | --- | --- | --- |
| Workbench Blue | `accent` | `dark-accent` | Primary actions, checked boxes and active tab underlines. |
| Blue Wash / Slate Blue | `selected` | `dark-selected` | Selected table rows, current navigation and marketplace tags. |
| Deep Blue Ink / Pale Blue Ink | `selected-ink` | `dark-selected-ink` | Selected navigation and marketplace text; dark overview links. |
| White Mark | `on-accent` | Shared | Primary-button text and checkbox marks. |
| Focus Blue | `focus-ring` | Shared | Keyboard focus on buttons, inputs and selects. |

### Neutral

| Color character | Light token | Dark token | Use |
| --- | --- | --- | --- |
| Cool Workbench / Graphite Ground | `bg` | `dark-bg` | Workspace ground. |
| White Sheet / Raised Graphite | `surface` | `dark-surface` | Header, fields, table sheet and solid overlays. |
| Pale Navigation / Graphite Rail | `nav` | `dark-nav` | Sidebar and sticky table headers. |
| Blue-black Ink / Paper Ink | `ink` | `dark-ink` | Primary content and labels. |
| Slate Copy / Mist Copy | `muted` | `dark-muted` | Metadata, descriptions, table headings and secondary icons. |
| Fine Grey Rule / Graphite Rule | `line` | `dark-line` | Borders and dividers. |
| Cool Hover / Graphite Hover | `soft` | `dark-soft` | Neutral hover fill and count tags. |
| Checkbox Slate | `checkbox-outline` | Shared | Unchecked checkbox outline. |

### Semantic states

Forest Green / Mint (`success` / `dark-success`) marks positive written states. Ochre / Warm Sand (`warn` / `dark-warn`) marks waiting or missing information. Cranberry / Rose (`danger` / `dark-danger`) marks errors and destructive actions. These are state colors, not additional decorative accents.

**The Written State Rule.** Every status dot appears with a readable status label; color alone does not name the state.

The sidecar's eight-step OKLCH ramps are synthesized swatch aids, not additional implemented colors or approved palette extensions.

The added sections reuse this palette. The template canvas is a local artifact exception: white label paper and dark printed content stay constant in both UI themes. It is explicitly a non-printable example, not a new application accent.

## Typography

**Body and heading font:** Inter, with Arial and sans-serif fallbacks. The preview serves regular and bold font files from `assets/`; it does not fetch fonts from a hosted service. There is no separate display or monospace face.

**Character:** Compact sans-serif text makes Vietnamese controls and Cyrillic product examples readable in the same list. Emphasis comes from weight, spacing and alignment rather than a second font family.

### Hierarchy

- **Headline:** `typography.headline` identifies each workspace view.
- **Title:** `typography.title` separates workflow and news regions. Dialog headings use a local enlargement (20px) with the same heading line height.
- **Body:** `typography.body` supplies the default UI text. The source leaves body line height at the browser's `normal`; no fixed body leading or universal text measure is claimed.
- **Label:** `typography.label` supplies secondary metadata. Status text uses explicit leading (1.4); supporting prose varies by context (1.5–1.7).
- **Control:** `typography.control` is the standard button voice. Quiet actions use lighter weight (500), and compact actions use smaller text (12px).
- **Row title:** `typography.row-title` distinguishes product names; secondary details sit on their own line and truncate when required.
- **Navigation:** `typography.navigation` supplies the rail; the selected destination increases weight (700). Group labels use smaller capitals (11px) with slight tracking (0.02em).
- **Form:** `typography.form` supplies aligned settings labels, editable fields and the unsaved-draft strip.
- **Reading:** `typography.reading` supplies the guide detail column; paragraph and list-item measures are capped (65ch).
- **Drawer title:** `typography.drawer-title` identifies the order or KIZ detail panel, with the selected record's identifier immediately below it.
- **Summary number:** `typography.summary-number` presents synthetic finance totals. This local finance summary does not restore overview KPI cards.

**The Number Column Rule.** Use tabular numerals throughout the preview and right-align prices so batch values can be scanned vertically.

## Layout

The desktop shell fills the viewport (`100vh`) with a fixed navigation column (196px), a store header (58px), and a remaining workspace sized with `minmax(0, 1fr)`. The workspace is a flex column with surrounding inset (22px top and sides, 14px bottom) and separation between regions (16px). The title, filters, tabs and footer keep their height while the table sheet takes the remaining area and scrolls internally. The page itself does not scroll.

Tables use fixed column layout and sticky headings. The declared header height is a floor (42px); wrapped headings may grow. Rows declare height (71px), with cell padding (9px vertical, 12px horizontal); their content and borders determine the rendered row extent. Product images occupy a fixed thumbnail box (42 × 53px). Names and metadata truncate within the flexible product column. The batch footer has a minimum height (42px), selection information at the left and actions at the right; it remains outside the table's scrolling region.

The overview uses a flexible workflow list and a news column (335px), separated by a gap (28px) and a fine vertical rule. This composition and the absence of the three overview counters belong to this preview surface, as recorded in the brief. No empty metric-card band is reserved above the workflows.

At the only compact-width breakpoint (`max-width: 1200px`), the rail narrows (176px), workspace insets become 18px top / 16px sides / 12px bottom, the news column narrows (290px), and its gap shrinks (22px). The header's preview badge and the sorting affordance hide; title and footer actions tighten. This is observed CSS behavior, not evidence of a mobile layout. The review captures cover desktop sizes only: 1340 × 865, 1440 × 900 and 1920 × 1080. [capture-report.json](screenshots/capture-report.json) reports no document overflow, missing images or browser errors in its seven captured views.

Spacing tokens capture reused intervals, not an enforced mathematical scale. Keep small icon/text gaps, clear separation between controls, and the largest available area for the list.

### Added section layouts

| Pattern | Implemented geometry | Where it is used |
| --- | --- | --- |
| Split working view | Flexible main column plus 305px side column; 24px gap. At the compact breakpoint, 270px side column and 18px gap. | GS1 membership, readiness and mailbox. |
| Aligned form | Content width capped at 1000px; 185px label column, flexible field column, 20px gap; field content capped at 650px. Compact labels narrow to 155px. | Znack configuration, account information, settings and listing-copy setup. |
| Three-part editor | 180px layer pane, flexible canvas and 235px properties pane. Compact widths become 155px and 210px. | Template designer, within the existing bordered sheet language. |
| Guide reader | 340px index, flexible detail, 28px gap; compact index is 290px. Both columns scroll internally. | Guides and procedural reading. |
| Detail overlay | Fixed to the right edge below the 58px header; 400px wide, 370px at the compact breakpoint. | Order details and KIZ matching. The underlying sheet is retained and partly covered, not relaid out. |

Form sections use the final tightened spacing from the extension (18px bottom padding and margin; 16px heading separation; form rows have 14px vertical margin). Readiness items use two columns, while imported-code, finance and history views reuse the table sheet. The label paper uses a 58:40 aspect ratio and a maximum width (360px); it is a visual size reference rather than a physical print calibration.

The [extended capture report](screenshots/extended-capture-report.json) records 27 additional captures: section examples at 1340 × 865 and an order drawer at 1440 × 900, with no reported document overflow, missing images or browser errors. It covers light and dark examples of eleven added section views, plus configuration subviews, FBO orders, listing copy and the drawer. This does not imply that every state or native scaling configuration has been verified.

## Elevation & Depth

Navigation, data sheets and ordinary controls are flat at rest. Solid tonal surfaces and fine borders establish hierarchy. Shadows identify temporary layers: the store popover, modal dialog and toast. The detail panel uses an opaque surface and a left rule without a drop shadow or backdrop. Its source row receives a thin inset accent outline. The dialog dims its owner with a translucent backdrop (`#07101e77`); its content surface stays opaque.

### Shadow Vocabulary

- **Popover:** `0 10px 30px #0003`, separating a compact action menu from the workspace.
- **Dialog:** `0 16px 60px #0004`, concentrating attention on a confirmation or local edit form.
- **Toast:** `0 8px 20px #0002`, separating short-lived local feedback from the page.

**The Temporary Layer Rule.** Reserve elevation shadows for overlays; use tonal surfaces and rules for persistent workspace structure.

### Purposeful motion

The interaction reference is adapted to a packing workspace: identify an item, inspect it beside its list, then return to the same selection and scroll position. The existing flat tabs receive a moving underline rather than the catalog's pill container. No page-wide entrance, row stagger, processing countdown, automatic incoming-order animation or animation library is added.

| Trigger | Observed timing and easing | Visible response |
| --- | --- | --- |
| Open details | 220ms; `cubic-bezier(.16,1,.3,1)` | Opacity rises while the panel moves from 28px to its right-edge resting position. |
| Close details | 140ms; `ease-in` | Panel moves 20px right and fades; removal returns focus to its connected opener. |
| Change a tab within a view | 160ms; `cubic-bezier(.16,1,.3,1)` | Underline translates and scales from the previous tab to the active tab. |
| First unsaved change | 180ms; `cubic-bezier(.16,1,.3,1)` | Save/Cancel strip rises 8px and fades in, only when values differ from the local saved snapshot. |
| Change selection | 120ms; linear count fade | The count updates in place; table backgrounds transition separately. |
| Hover controls or rows | 120ms; CSS default `ease` | Button background, text and border colors, or row background, transition. An enabled press has an immediate 1px downward transform. |
| Show local feedback | 160ms; `cubic-bezier(.16,1,.3,1)` | Toast rises 6px and fades in; its text remains the actual local result. |
| Open a popover | 160ms; `cubic-bezier(.16,1,.3,1)` | Existing clip reveal with a 4px initial upward offset. |
| Change theme | 160ms; CSS default `ease` | Body background and text colors transition; theme classes update in place. |

OS `prefers-reduced-motion: reduce` and the preview's **Giảm chuyển động trong bản mẫu** control remove spatial interaction motion. CSS suppresses animated descendants and pressed movement; JavaScript checks the combined preference before drawer, tab, selection and draft animations. Reduced-motion open/close still updates content and returns focus immediately. Repeated drawer commands cancel the prior drawer animation; handlers remain available during transitions.

**The Continuity Rule.** Motion must preserve the selected record, list context and local draft; state feedback remains available when spatial motion is reduced.

The [motion checks](screenshots/motion-checks.json) record 26 passing checks over local state, selected-row identity, template field targeting, focus restoration and both reduced-motion paths, with no recorded script errors or external requests. These are synthetic browser checks, not JavaFX or marketplace acceptance. Reference ideas such as field-error focus, typed-name deletion, true file-import progress and incoming-order streams are not implemented merely because they appear in MOTION.md's catalog mapping.

## Shapes

Controls use the gentle `control` radius, table sheets and popovers the slightly larger `surface` radius, and dialogs the `dialog` radius. The `detail` radius is shared by checkboxes, thumbnails and count tags. Borders are generally fine (1px); unchecked boxes have a stronger outline (1.5px). Marketplace tags use a small local corner (3px). Tabs are square-edged with an active underline (2px), and status dots are circular (6px diameter).

Icons are small inline line drawings (18px square, stroke width 1.6) with round caps and joins. The final overview workflow symbols sit in an unfilled narrow slot (24 × 32px); the earlier filled icon-tile declaration is overridden in the finished stylesheet and is not the current visual language.

## Components

### Buttons

Restrained controls distinguish an action's importance without changing the row geometry. Standard buttons use `button-secondary`, a fine neutral border and a minimum height (36px). Icon buttons keep the same width and minimum height (36px). Compact buttons reduce the minimum height (33px) and use tighter padding (7px vertical, 10px horizontal).

- **Primary:** The theme's action blue with white text; hover brightens the whole control.
- **Secondary:** Solid surface with ink text; hover uses the soft neutral fill.
- **Quiet:** Transparent surface and border with lighter weight. The source's quiet rule keeps the background transparent on hover; navigation and popover entries have their own hover treatment.
- **Destructive:** Danger-colored text on a neutral control. The header delete action combines the quiet, icon and danger variants; the confirmation action retains the bordered form.
- **Focus / disabled / press:** Keyboard focus uses the shared blue outline (2px) with an offset (3px). Disabled buttons use reduced opacity (0.55) and the default cursor. The extension adds the short color transitions and enabled-button press offset documented under Purposeful motion; reduced motion removes the spatial press response.

### Chips

Marketplace tags pair the selected background with selected ink; their uppercase content is data such as WB or OZON, not a new accent per marketplace. Tab counts use the soft neutral background and compact label text. Status tags remain unboxed text with a small dot, always carrying their written meaning. Product repair actions are small bordered buttons with warning-colored text.

### Cards / Containers

The recurring container is an inset data sheet, not a floating dashboard card. It uses the solid surface token, a fine border and `surface` corners. Its table owns row padding and scroll behavior. A selected row uses the selected fill; selection continues to win over ordinary row hover. Overview workflows are divided by horizontal rules rather than independent cards.

### Inputs / Fields

Text inputs and selects have solid surfaces, a fine border, `control` corners and inset padding (9px vertical, 10px horizontal). Search adds an inline icon and left inset (11px), allows the field to flex, and caps the whole group (440px). Placeholders use muted text. Focus is the same keyboard outline as buttons. Aligned settings fields use the smaller form text and full column width; disabled form inputs and selects use reduced opacity (0.65). No authored field-error treatment or validation flow is implemented.

Checkboxes have unchecked, checked and indeterminate states. Checked and mixed states share the accent fill and carry a white check or horizontal mark. In the preview, select-all applies to visible filtered results and its mixed state reflects that subset; the footer count includes selected rows even when filtered out. Search is a local text filter, while the category and status dropdowns are visual samples without business filtering.

### Navigation

The rail groups existing destinations under small labels, with bottom utilities separated by a rule. Each row combines a line icon and text, uses compact padding and a minimum height (38px), and retains a visible active fill with stronger type. Hover uses the soft neutral background, including on the active item. The original overview, FBS, products and Znack views now connect to the additional sections listed below. Store selection stays independent of navigation, and WB/Ozon FBS tabs use their respective written states. Presence of a route does not prove native feature implementation.

Tabs retain flat text, optional counts and a thin accent underline. The extension moves a separate underline element and leaves the active button's bottom border transparent. Tab groups expose `tablist`, `tab` and `aria-selected`; Left/Right activates the adjacent tab and focuses the new active item. This documents the present keyboard behavior, not a full accessibility certification. No mobile navigation pattern is implemented.

### Added preview surfaces

| Surface | Local content and behavior | Boundary |
| --- | --- | --- |
| FBO packing (`fbo`) | Product, variant and synthetic GTIN rows; editable quantities update a batch label total. | Print actions are local demonstrations. In-place theme changes preserve quantities. |
| KIZ links (`kiz`) | Link readiness, available quantities and a row-specific matching drawer. | The selected row supplies both the drawer identifier and GTIN; no real codes are read or assigned. |
| GS1 RUS (`gs1`) | Membership example, readiness checks, prefix table and empty mailbox pane. | Member and prefix values are synthetic; signature, account and mailbox checks are not performed. |
| Znack configuration (`config`) | Information, purchase history, deleted items, settings and seller-warehouse tabs. | Local draft editing is described below; no credentials or certificates are verified. |
| Seller KIZ warehouse (`config`, `tab=warehouse`) | Import framing, category actions, available/used quantities and link access. | Import explains a future step; it does not read or upload a real file. |
| Finance (`finance`) | WB summary and penalty-layout examples, date inputs and tab changes. | Ozon displays the unsupported-source state; there are no real finance or penalty results. |
| Print history (`history`) | Synthetic batch, shipment, result and output-file rows. | Reprint does not create or send a real print job. |
| Templates (`templates`) | Layer list, label canvas and properties pane. | Only product-name and Article text are locally wired; the preview cannot generate a usable label. |
| Settings (`settings`) | Immediate light/dark theme control and local reduced-motion preference. | Language, background-slider and account/update controls illustrate placement rather than a released settings implementation. |
| Guides (`guides`) | Topic index and reading column; choosing a topic updates its title. | Only the first topic carries the illustrated steps; other topic selections hide that step list. |
| TN VED (`tnved`) | Classification examples with filter and copy affordances. | Example codes are not automatically applied to a product; copy remains a demonstration. |
| FBO orders (`fboorders`) | Synthetic shipment, warehouse, date and status rows. | No warehouse shipment is created. |
| Listing copy (`copy`) | Source/destination layout and an empty chosen-product section. | No listing is copied or marketplace data fetched. |

### Detail panel and continuity

Order and KIZ details open beside the retained list. Order content follows the clicked row's identifier, image, title and variant; KIZ matching follows that row's GTIN and product context. The panel focuses its close button, and closing or Escape returns focus to the opener when it still exists. It is a labelled non-modal aside with an internal scroll region and a separate action footer.

The preview caches checkbox state, search text and sheet scroll by view, selected marketplace and applicable tab. Switching theme changes the body class in place, preserving fields, quantities and the current list. This is in-memory synthetic state for the current page; it does not prove database recovery, cross-session persistence or native virtualization.

### Configuration draft feedback

Configuration fields start disabled. **Chỉnh sửa cấu hình** enables them and focuses the first field. Comparing the local draft with the last local saved snapshot controls the unsaved strip; merely entering edit mode does not imply a change. Returning fields to the saved values hides the strip. Dirty values block tab, section and store changes until Save or Cancel.

**Lưu cấu hình** copies the draft into the preview's in-memory saved values and explicitly reports that no API call occurred. **Hủy thay đổi** restores the last local saved values. Saved configuration is kept separately for the synthetic WB and Ozon stores. Theme changes retain the current fields, and editing a different store never substitutes another store's saved values. There is no disk write, credential validation or backend save.

### Template canvas

The editor separates layers, paper and properties with fine rules. Selecting **Tên sản phẩm** or **Article** targets that exact local text element; changing Article leaves the product title intact. **Màu · Size** and **Barcode** expose their layout but disable the text editor and show its limitation. Other geometric/property controls are visual samples. The paper bears **KHÔNG DÙNG ĐỂ IN**, and the canvas identifies itself as something other than an Ozon waybill. Save, print and template-management actions do not persist or emit production labels.

### Store management

The active store selector, **Quản lý** action and separate small delete control remain visible in the header at the captured desktop sizes. Management opens an opaque popover (`store-popover`) with the store name, edit action, divider and add action. It is positioned beneath the header (top 52px, left 430px; left 390px in the compact-width rule). This is the signature pattern of the proposal.

Deletion opens a centered dialog (440px wide, 26px padding) naming **Cửa hàng minh họa**, with **Hủy** and **Xóa cửa hàng** actions. The local modal initially focuses Cancel; Escape closes dialogs and popovers. Confirmation only shows a synthetic toast. This preview does not establish production focus trapping, focus return, persistence, API calls or real store mutations.

### Batch controls and feedback

The footer keeps selection, pagination samples, KIZ source access, label/picking-list actions and the main batch action adjacent to the current sheet. FBS and Znack tabs update local synthetic states. Confirmations and unimplemented actions use a `role="status"` toast (4.5 seconds). Existing Ozon carrier labels and combined KIZ output are product constraints, not generated artifacts of this mock.

The [sidecar](.impeccable/design.json) contains thirteen self-contained representative HTML/CSS snippets with inline icons, including the added detail panel, draft strip and flat tabs. These are visual documentation components; their controls do not execute the preview's application logic or Web Animations handlers. Their ordinary hover, focus and reduced-motion styling is included. Stock thumbnail provenance is recorded in [assets/PROVENANCE.json](assets/PROVENANCE.json).

## Do's and Don'ts

### Do:

- **Do** use the shared semantic roles in both preview themes.
- **Do** keep tabular numbers, right-aligned prices and written status labels.
- **Do** keep table scrolling inside the sheet and batch actions in the separate footer.
- **Do** retain visible store identity, management access and a named delete confirmation.
- **Do** use locally served Inter and labelled synthetic examples with recorded image provenance.
- **Do** preserve selected-row identity, store-specific drafts and list context through short interaction motion.
- **Do** retain immediate text and color feedback when spatial motion is reduced.

### Don't:

- **Don't** treat this proposal or the finish-review verdict as user screenshot approval or native implementation readiness.
- **Don't** restore the three overview counters or reserve their empty card band in this preview.
- **Don't** displace the header's store controls with the former FBS delay text.
- **Don't** claim local simulations, tab counts or dropdown samples are live marketplace behavior.
- **Don't** infer mobile support from the compact desktop breakpoint.
- **Don't** turn the motion reference into decorative page entrances, row staggers, fake progress or a new animation dependency.
- **Don't** present local configuration saves, template edits or import prompts as persisted app operations.
