---
name: VN Code — Desktop Preview Proposal
description: PROPOSAL awaiting user screenshot approval; extracted from the separate synthetic HTML preview on 2026-11-04.
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
---

# Design System: VN Code — Desktop Preview Proposal

## Overview

**Creative North Star: "Dispatch Workbench"**

Dispatch Workbench is the proposed visual direction for VN Code's separate HTML desktop preview. It is a **PROPOSAL awaiting user screenshot approval**, with synthetic store, order and product data. The calm white and graphite surfaces, restrained blue selection, fine rules and bundled Inter typography support a compact, readable workspace for Vietnamese-speaking sellers and warehouse staff.

The component character is precise and restrained: flat controls, clear text states, inset data sheets and a small store-management popover. Light and dark themes share the same structure. This document governs only the preview directory; [the incumbent workspace system](../../vncode-workspace-system.md) remains the production design authority. The current [index.html](index.html) is a code-led desktop visual mock, not the JavaFX application runtime or an implementation approval.

Tokens were extracted from [preview.css](preview.css), behaviors from [preview.js](preview.js), and checked against the [captured screens](screenshots/). Product facts and the chosen direction come from [PRODUCT.md](../../../../PRODUCT.md) and the [preview brief](../../2026-11-04-preview-brief.md). The finish review's `ship` disposition means the reviewer scored all seven listed material fixes resolved for preview readiness; see [REVIEW.md](REVIEW.md). It does not certify native integration or replace the pending user decision.

**Key Characteristics:**

- Neutral white and graphite grounds with one blue action and selection family.
- Dense, legible lists with tabular numbers and persistent batch controls.
- Flat navigation and fine dividers; elevation reserved for temporary overlays.
- Visible store identity and management controls, with a named delete confirmation.
- Locally served Inter, line icons, and labelled synthetic content in both themes.

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

**The Number Column Rule.** Use tabular numerals throughout the preview and right-align prices so batch values can be scanned vertically.

## Layout

The desktop shell fills the viewport (`100vh`) with a fixed navigation column (196px), a store header (58px), and a remaining workspace sized with `minmax(0, 1fr)`. The workspace is a flex column with surrounding inset (22px top and sides, 14px bottom) and separation between regions (16px). The title, filters, tabs and footer keep their height while the table sheet takes the remaining area and scrolls internally. The page itself does not scroll.

Tables use fixed column layout and sticky headings. The declared header height is a floor (42px); wrapped headings may grow. Rows declare height (71px), with cell padding (9px vertical, 12px horizontal); their content and borders determine the rendered row extent. Product images occupy a fixed thumbnail box (42 × 53px). Names and metadata truncate within the flexible product column. The batch footer has a minimum height (42px), selection information at the left and actions at the right; it remains outside the table's scrolling region.

The overview uses a flexible workflow list and a news column (335px), separated by a gap (28px) and a fine vertical rule. This composition and the absence of the three overview counters belong to this preview surface, as recorded in the brief. No empty metric-card band is reserved above the workflows.

At the only compact-width breakpoint (`max-width: 1200px`), the rail narrows (176px), workspace insets become 18px top / 16px sides / 12px bottom, the news column narrows (290px), and its gap shrinks (22px). The header's preview badge and the sorting affordance hide; title and footer actions tighten. This is observed CSS behavior, not evidence of a mobile layout. The review captures cover desktop sizes only: 1340 × 865, 1440 × 900 and 1920 × 1080. [capture-report.json](screenshots/capture-report.json) reports no document overflow, missing images or browser errors in its seven captured views.

Spacing tokens capture reused intervals, not an enforced mathematical scale. Keep small icon/text gaps, clear separation between controls, and the largest available area for the list.

## Elevation & Depth

Navigation, data sheets and ordinary controls are flat at rest. Solid tonal surfaces and fine borders establish hierarchy. Shadows identify temporary layers: the store popover, modal dialog and toast. The dialog dims its owner with a translucent backdrop (`#07101e77`); its content surface stays opaque.

### Shadow Vocabulary

- **Popover:** `0 10px 30px #0003`, separating a compact action menu from the workspace.
- **Dialog:** `0 16px 60px #0004`, concentrating attention on a confirmation or local edit form.
- **Toast:** `0 8px 20px #0002`, separating short-lived local feedback from the page.

**The Temporary Layer Rule.** Reserve shadows for overlays; use tonal surfaces and rules for persistent workspace structure.

The popover alone has entrance motion: a short reveal (`0.16s`, `cubic-bezier(.16,1,.3,1)`) clips its lower edge and translates it upward by a small amount at the start (4px). That animation runs only under `prefers-reduced-motion: no-preference`. Buttons have no authored motion transition; primary hover applies a brightness filter (1.08) without changing geometry.

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
- **Focus / disabled:** Keyboard focus uses the shared blue outline (2px) with an offset (3px). Disabled buttons use reduced opacity (0.55) and the default cursor. No bespoke pressed animation is implemented.

### Chips

Marketplace tags pair the selected background with selected ink; their uppercase content is data such as WB or OZON, not a new accent per marketplace. Tab counts use the soft neutral background and compact label text. Status tags remain unboxed text with a small dot, always carrying their written meaning. Product repair actions are small bordered buttons with warning-colored text.

### Cards / Containers

The recurring container is an inset data sheet, not a floating dashboard card. It uses the solid surface token, a fine border and `surface` corners. Its table owns row padding and scroll behavior. A selected row uses the selected fill; selection continues to win over ordinary row hover. Overview workflows are divided by horizontal rules rather than independent cards.

### Inputs / Fields

Text inputs and selects have solid surfaces, a fine border, `control` corners and inset padding (9px vertical, 10px horizontal). Search adds an inline icon and left inset (11px), allows the field to flex, and caps the whole group (440px). Placeholders use muted text. Focus is the same keyboard outline as buttons; there is no authored field-error or disabled-field treatment to extract.

Checkboxes have unchecked, checked and indeterminate states. Checked and mixed states share the accent fill and carry a white check or horizontal mark. In the preview, select-all applies to visible filtered results and its mixed state reflects that subset; the footer count includes selected rows even when filtered out. Search is a local text filter, while the category and status dropdowns are visual samples without business filtering.

### Navigation

The rail groups existing destinations under small labels, with bottom utilities separated by a rule. Each row combines a line icon and text, uses compact padding and a minimum height (38px), and retains a visible active fill with stronger type. Hover uses the soft neutral background, including on the active item. Four destinations render complete preview surfaces: overview, FBS, products and Znack. Other destinations show local explanatory feedback; their presence does not prove native feature implementation. Tabs use text, a count and an active underline without pill containers. No mobile navigation pattern is implemented.

### Store management

The active store selector, **Quản lý** action and separate small delete control remain visible in the header at the captured desktop sizes. Management opens an opaque popover (`store-popover`) with the store name, edit action, divider and add action. It is positioned beneath the header (top 52px, left 430px; left 390px in the compact-width rule). This is the signature pattern of the proposal.

Deletion opens a centered dialog (440px wide, 26px padding) naming **Cửa hàng minh họa**, with **Hủy** and **Xóa cửa hàng** actions. The local modal initially focuses Cancel; Escape closes dialogs and popovers. Confirmation only shows a synthetic toast. This preview does not establish production focus trapping, focus return, persistence, API calls or real store mutations.

### Batch controls and feedback

The footer keeps selection, pagination samples, KIZ source access, label/picking-list actions and the main batch action adjacent to the current sheet. FBS and Znack tabs update local synthetic states. Confirmations and unimplemented actions use a `role="status"` toast (4.5 seconds). Existing Ozon carrier labels and combined KIZ output are product constraints, not generated artifacts of this mock.

The [sidecar](.impeccable/design.json) contains ten self-contained representative HTML/CSS snippets with inline icons. These are visual documentation components; their controls do not execute the preview's application logic. Stock thumbnail provenance is recorded in [assets/PROVENANCE.json](assets/PROVENANCE.json).

## Do's and Don'ts

### Do:

- **Do** use the shared semantic roles in both preview themes.
- **Do** keep tabular numbers, right-aligned prices and written status labels.
- **Do** keep table scrolling inside the sheet and batch actions in the separate footer.
- **Do** retain visible store identity, management access and a named delete confirmation.
- **Do** use locally served Inter and labelled synthetic examples with recorded image provenance.

### Don't:

- **Don't** treat this proposal or the finish-review verdict as user screenshot approval or native implementation readiness.
- **Don't** restore the three overview counters or reserve their empty card band in this preview.
- **Don't** displace the header's store controls with the former FBS delay text.
- **Don't** claim local simulations, tab counts or dropdown samples are live marketplace behavior.
- **Don't** infer mobile support from the compact desktop breakpoint.
