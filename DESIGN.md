---
name: VN Code — Native Dispatch Workbench
description: Compact JavaFX workbench with white and graphite surfaces, readable work lists and visible store ownership.
colors:
  bg-primary: '#141414'
  nav-surface: '#171717'
  bg-secondary: '#1b1b1b'
  bg-tertiary: '#25272b'
  border-color: '#383b40'
  border-color-hover: '#7c879a'
  text-primary: '#edf2f7'
  text-secondary: '#c4cdd8'
  text-muted: '#a8b5c6'
  accent: '#285fc7'
  accent-hover: '#2153b0'
  accent-active: '#19448f'
  accent-text: '#bedaff'
  focus: '#bedaff'
  focus-primary: '#eef5ff'
  ui-panel: '#1b1b1b'
  ui-control: '#25272b'
  ui-control-hover: '#383b40'
  ui-field: '#1b1b1b'
  ui-input-border: '#7c879a'
  ui-row-hover: '#222b36'
  ui-selected: '#233c5b'
  ui-table-header: '#171717'
  danger: '#ff8598'
  success: '#70d5b0'
  warning: '#f2c46d'
  danger-fill: '#a52840'
  success-surface: '#163b30'
  danger-surface: '#432530'
  warning-surface: '#3c321e'
  light-bg-primary: '#f4f6f9'
  light-nav-surface: '#f9fafc'
  light-bg-secondary: '#ffffff'
  light-bg-tertiary: '#eef2f6'
  light-border-color: '#dce2ea'
  light-border-color-hover: '#748197'
  light-text-primary: rgb(23,37,55)
  light-text-secondary: '#3b4d63'
  light-text-muted: '#5d6a7b'
  light-accent: '#175bc5'
  light-accent-hover: '#114eae'
  light-accent-active: '#0c4093'
  light-accent-text: '#174b9a'
  light-focus: '#175bc5'
  light-focus-primary: '#ffffff'
  light-ui-panel: rgba(255,255,255,0.552)
  light-ui-control: rgba(255,255,255,0.572)
  light-ui-control-hover: '#e7edf5'
  light-ui-field: '#ffffff'
  light-ui-input-border: '#7e899a'
  light-ui-row-hover: '#f0f4fa'
  light-ui-selected: '#e8f0ff'
  light-ui-table-header: '#f9fafc'
  light-danger: '#b3243e'
  light-success: '#136749'
  light-warning: '#825200'
  light-danger-fill: '#b3243e'
  light-success-surface: '#e1f4eb'
  light-danger-surface: '#fce8ed'
  light-warning-surface: '#fbf0d8'
  on-accent: '#ffffff'
  success-fill: '#14694f'
  warning-fill: '#f2c46d'
  on-warning: '#18202b'
typography:
  headline:
    fontFamily: Inter
    fontSize: 25px
    fontWeight: 700
  welcome-title:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: 700
  title:
    fontFamily: Inter
    fontSize: 15px
    fontWeight: 700
  body:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: 400
  control:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: 400
  record-title:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: 700
  metadata:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: 400
  column-label:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: 700
  badge:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: 700
rounded:
  detail: 4px
  control: 6px
  surface: 8px
  icon: 10px
spacing:
  tight: 4px
  compact: 6px
  group: 8px
  control-gap: 10px
  panel: 12px
  page: 16px
  detail: 18px
  header-inline: 22px
components:
  button-secondary:
    backgroundColor: '{colors.ui-control}'
    textColor: '{colors.text-primary}'
    typography: '{typography.control}'
    rounded: '{rounded.control}'
    padding: 7px 11px
  button-secondary-hover:
    backgroundColor: '{colors.ui-control-hover}'
  button-primary:
    backgroundColor: '{colors.accent}'
    textColor: '{colors.on-accent}'
    typography: '{typography.record-title}'
    rounded: '{rounded.control}'
    padding: 7px 11px
  button-primary-hover:
    backgroundColor: '{colors.accent-hover}'
  button-primary-active:
    backgroundColor: '{colors.accent-active}'
  button-danger:
    backgroundColor: '{colors.danger-fill}'
    textColor: '{colors.on-accent}'
    rounded: '{rounded.control}'
    padding: 7px 11px
  button-success:
    backgroundColor: '{colors.success-fill}'
    textColor: '{colors.on-accent}'
    rounded: '{rounded.control}'
    padding: 7px 11px
  button-warning:
    backgroundColor: '{colors.warning-fill}'
    textColor: '{colors.on-warning}'
    rounded: '{rounded.control}'
    padding: 7px 11px
  header-action:
    backgroundColor: transparent
    textColor: '{colors.text-primary}'
    typography: '{typography.control}'
    rounded: '{rounded.control}'
    padding: 7px 11px
  input:
    backgroundColor: '{colors.ui-field}'
    textColor: '{colors.text-primary}'
    typography: '{typography.control}'
    rounded: '{rounded.control}'
    padding: 6px 10px
  navigation-item:
    backgroundColor: transparent
    textColor: '{colors.text-secondary}'
    typography: '{typography.control}'
    rounded: '{rounded.control}'
    padding: 6px 10px
    height: 38px
  navigation-item-selected:
    backgroundColor: '{colors.ui-selected}'
    textColor: '{colors.accent-text}'
    typography: '{typography.record-title}'
  panel:
    backgroundColor: '{colors.ui-panel}'
    textColor: '{colors.text-primary}'
    rounded: '{rounded.surface}'
    padding: 12px
  data-sheet:
    backgroundColor: '{colors.bg-secondary}'
    textColor: '{colors.text-primary}'
    rounded: '{rounded.surface}'
    padding: 1px
  warning-chip:
    backgroundColor: '{colors.warning-surface}'
    textColor: '{colors.warning}'
    typography: '{typography.control}'
    rounded: '{rounded.control}'
    padding: 4px 10px
  status-badge:
    backgroundColor: '{colors.bg-tertiary}'
    textColor: '{colors.text-secondary}'
    typography: '{typography.badge}'
    rounded: '{rounded.detail}'
    padding: 2px 6px
  workspace-tab:
    backgroundColor: transparent
    textColor: '{colors.text-secondary}'
    typography: '{typography.control}'
    rounded: 0px
    padding: 8px 12px
  workspace-tab-selected:
    textColor: '{colors.text-primary}'
    typography: '{typography.record-title}'
  detail-drawer:
    backgroundColor: '{colors.bg-secondary}'
    textColor: '{colors.text-primary}'
    padding: 18px
    width: 420px
  selection-bar:
    backgroundColor: transparent
    textColor: '{colors.text-primary}'
    padding: 10px 0 0 0
---

# Design System: VN Code — Native Dispatch Workbench

## Overview

**Creative North Star: "Dispatch Workbench"**

Dispatch Workbench is VN Code’s implemented native workspace: neutral white and graphite surfaces, bundled Inter, a restrained blue action family and fine dividing rules. Its precise, restrained controls serve Vietnamese-speaking sellers and warehouse staff working through large lists with a mouse and keyboard. The VN black logo remains the identity anchor.

The visual center is an inset work list. Store identity and management sit above it, navigation remains quiet beside it, and contextual details retain the underlying list. Both themes share the same geometry. Light-theme material opacity remains a user preference applied to background paints; it never fades the text or the whole window. Short, interruptible motion connects local actions with their visible result.

This is the root design record for the JavaFX application. [PRODUCT.md](PRODUCT.md) supplies durable product constraints; the [native direction contract](docs/design/2026-11-04-native-implementation.md) records the user-approved application of the preview. The historical [preview design record](docs/design/previews/2026-11-04/DESIGN.md) is preserved at its original scope. Its `seed:4d76fcce170bad27` carries into this implementation; the local registry cannot corroborate the original roll. The user’s approval supplies authority, and no replacement seed or inferred provenance is recorded.

Tokens follow the effective native source: [workspace-controls.css](src/main/resources/com/vncode/app/styles/workspace-controls.css), loaded after [theme.css](src/main/resources/com/vncode/app/styles/theme.css) by [ThemeService](src/main/java/com/vncode/app/shared/ThemeService.java), plus the header/sidebar FXML and sampled list/drawer controllers. The [30 native captures and manifest](docs/design/native/2026-11-04/README.md) use an isolated synthetic profile. They show the application on Linux in both themes; they are not live marketplace evidence or Windows installer validation. Older base declarations overridden by the final workspace stylesheet are not promoted to tokens.

**Key Characteristics:**

- White and graphite grounds, one blue interaction family, and written semantic states.
- Compact native controls and virtualized data sheets with room for complete order contents.
- Flat navigation, fine borders and limited popup elevation.
- Visible store identity, management access and the existing named delete confirmation.
- Bundled Inter and consistent line icons; shared structure in both themes.
- Read-only detail motion and draft feedback that respect system and manual reduced motion.

## Colors

Cool white material and blue-black ink pair with nearly neutral graphite and pale text; restrained blue identifies action, selection and focus. Semantic green, amber and red accompany written operational states.

The frontmatter is the normative snapshot. Unprefixed paint names match the dark theme’s JavaFX looked-up colors without their leading hyphen; `light-` names record the corresponding light paints. Shared `on-accent`, `success-fill`, `warning-fill` and `on-warning` values apply to filled controls. Component tokens describe the dark variant; light controls substitute the matching `light-` paint where present. Typography and geometry are shared.

### Primary

| Character | Token family | Application |
| --- | --- | --- |
| Workbench Blue | `accent`, `accent-hover`, `accent-active` | Primary action fill and checked selection controls. |
| Pale / Deep Blue Ink | `accent-text` | Active navigation text, selected counts and native tab underlines. |
| Slate Blue / Blue Wash | `ui-selected` | Selected navigation, rows and popup entries. |
| Focus Blue | `focus`, `focus-primary` | Keyboard focus boundaries, with a light boundary on filled primary/success/danger buttons. |

### Neutral

| Character | Token family | Application |
| --- | --- | --- |
| Graphite Ground / Cool Workbench | `bg-primary` | Workspace and header background. |
| Graphite Rail / Pale Navigation | `nav-surface`, `ui-table-header` | Sidebar and column headers. |
| Raised Graphite / White Sheet | `bg-secondary`, `ui-field` | Data sheets, input fields and read-only drawer. |
| Quiet Material | `ui-panel`, `ui-control` | Panels and ordinary controls; adjustable white material in the light theme. |
| Paper Ink / Blue-black Ink | `text-primary` | Main labels and working content. |
| Mist / Slate Copy | `text-secondary`, `text-muted` | Secondary labels, metadata and placeholders. |
| Fine Rule / Strong Boundary | `border-color`, `border-color-hover`, `ui-input-border` | Surface dividers, stronger interactive boundaries and input outlines. |
| Neutral Hover | `ui-row-hover`, `ui-control-hover` | Row and control hover feedback. |

Success, danger and warning use their named text and surface pairs. Filled action variants use their separate fill paints so the darker button treatment is not confused with state text. The selected state is a fill plus text/control state, rather than color alone.

Light panel/control frontmatter values reflect `ThemeService` at the saved default material amount (55). Runtime replaces the stylesheet’s fallback tints with white paints: surface alpha is `.005 + amount × .00995`; button alpha adds `.035 × amount / 100`, clamped to one and formatted to three decimals. The amount is bounded from zero to one hundred. Solid fields, the table sheet, drawer, text and selection retain their own paints. This preference does not enable a blur effect. Its formula and fallback distinction live in the sidecar.

**The Shared Palette Rule.** Use the active theme’s semantic paints for common controls and data states; marketplace identity does not introduce a competing page palette.

**The Text Stays Solid Rule.** Apply the light material preference to background paints only. Text, control boundaries, selection and the application window remain fully legible.

## Typography

**Body and interface font:** bundled Inter, loaded from the project’s regular and bold font assets by `UiFonts`. There is no separate native display face or authored line-height/tracking scale.

The typography is compact and direct. Page headings and record names carry emphasis; metadata stays regular. The frontmatter records the shared roles actually used, without inheriting the preview’s web-only numeral or text-layout properties.

| Role | Use |
| --- | --- |
| `headline` | Main workspace page heading. |
| `welcome-title` | Overview heading. |
| `title` | Detail drawer heading and small section hierarchy. |
| `body` | Root and general descriptive text. |
| `control` | Buttons, tabs, navigation and standard fields. |
| `record-title` | Product names and emphasized order data. |
| `metadata` | Product/Catalog/Ozon supporting row data. |
| `column-label` | Table column headings. |
| `badge` | Compact semantic state badge. |

Local native exceptions remain visible: the sidebar wordmark is bold (20px); navigation group labels are bold (10px); GS1 uses a smaller section title (20px), and overview news headings use (16px). Navigation group labels organize destinations; they are not a reusable eyebrow above page headings. The table records do not establish a monospace family or an explicit tabular-number feature.

**The Working Hierarchy Rule.** Use Inter weight and size to separate page titles, record titles and supporting data. Keep metadata readable without giving every cell heading weight.

## Layout

The expanded shell has a fixed navigation rail (196px) and header (58px). The header has horizontal inset (22px) and vertical inset (10px). Main Ozon and product pages use page inset (16px), tight heading/filter groups and a list that takes the available height. Regular navigation entries have a minimum and preferred height (38px); secondary utilities may retain their native preferred height.

The sidebar automatically contracts to an icon rail (64px) when the workspace viewport is below (1240px), with a manual collapse override. It retains tooltips and accessible names. Below a header content width of (800px), the store selector’s preferred width changes from (220px) to (180px), the management label yields to its named icon, and the header theme shortcut hides. Secondary diagnostics already live in the overflow at all widths. These are separate native measurements, not web viewport breakpoints or a mobile navigation claim.

Controls keep stable bounds during hover and focus. Normal buttons have a minimum height (36px), fields (34px), and header icon actions a minimum size (32 × 34px). The shared button inset is vertical/horizontal (7px / 11px), field inset (6px / 10px), and panel inset (12px). Domain overrides remain intentional where present; frontmatter padding does not imply a fixed height.

Product list rows are fixed (76px). Ozon single-item orders start at (76px) and add (68px) per additional item: an item block (60px) plus gap (8px). Catalog registration rows use (71px). Ozon column headers use (44px); the shared table header requests (42px). Preserve computed order height and internal list scrolling. Batch bars use their own layout space below the list and wrap their actions; they do not cover the last row. Overview uses task links and news without the removed counter trio.

## Elevation & Depth

The workbench is flat at rest: shared cards, controls and data containers use tone and fine borders, and the shared workspace stylesheet clears card/button effects. The light material setting changes background paint alpha while preserving opaque content. The drawer uses a solid sheet with a left divider, not a cast shadow. Native context menus and combo-box choice popups retain their existing soft drop shadows.

| Native effect | Purpose |
| --- | --- |
| `dropshadow(three-pass-box, rgba(0, 0, 0, 0.08), 8, 0, 0, 2)` | Context-menu elevation. |
| `dropshadow(three-pass-box, rgba(0, 0, 0, 0.1), 10, 0, 0, 4)` | Combo-box popup elevation. |
| `innershadow(gaussian, -focus, 1, 1, 0, 0)` | Input focus boundary, not surface elevation. |

Checkbox/radio focus also uses a small native focus effect. These focus treatments signal keyboard interaction; they do not create a decorative halo vocabulary for panels.

**The Bordered Sheet Rule.** Keep working surfaces flat and separate them with tone and fine rules. Reserve the existing soft shadows for temporary menus and choice popups.

## Shapes

Controls and fields use the `control` corner; shared panels, lists, menus and dialogs use the `surface` corner. Badges and ordinary checkbox boxes use the smaller `detail` corner. General icon buttons use the `icon` corner, while compact header icons return to the control background corner. Product checkboxes retain their local slightly larger box (20px) and corner (5px); ordinary checkbox/radio boxes have a minimum dimension (19px).

Data and chrome boundaries are fine strokes (1px). Tabs are square-edged with a bottom border (2px) for the selected state. The read-only drawer has square outer edges and only a left divider. Radios and status dots use their native circular silhouette. Existing Feather line icons come through Ikonli; use consistent library artwork rather than Unicode substitutes. The black VN logo remains the supplied image asset.

## Components

### Buttons

Precise and restrained, with stable geometry. Ordinary controls use `button-secondary`; primary actions use `button-primary` with white text and bold weight. Hover and press resolve through the current theme. Standard focus changes the border; primary, success and danger fills use their dedicated contrasting focus paint. Disabled buttons and menu buttons use opacity (.52).

The header’s management and utility actions have transparent default backgrounds and neutral hover fills. Its separate delete icon uses danger color and the existing deletion handler/confirmation. Product bulk-edit actions deliberately use neutral outlined controls even though their native action class is primary. They do not turn the whole batch bar blue. Success, danger and warning filled variants remain for actions whose meaning warrants them.

### Chips

Product repair filters use a warning surface, warning text and the control corner. A selected repair filter adds a warning boundary. Compact state badges use the smaller badge geometry and semantic surface/text pairs. Preserve the written state and count; do not use the color as the only indication.

### Cards / Containers

The recurring container is an inset data sheet, with a solid sheet fill, fine border and the surface corner. Ordinary panels use the panel paint and padding. Overview workflow entries and news use horizontal dividers and transparent backgrounds instead of inheriting a floating-card treatment. Selection fills a row; hover uses the quieter row paint, and keyboard focus remains visible.

### Inputs / Fields

Fields, combo boxes and choices use the solid field paint, strong input boundary and control corner. Placeholder text uses the muted paint. Focus adds the theme focus boundary and inner focus effect; invalid text input uses danger at the boundary. Native checkbox checked and mixed states share the action fill and white mark. Popup options inherit the selected theme, native keyboard behavior and the existing popup shadows.

### Navigation

The sidebar pairs the native line icon with readable text, quiet default/hover states and a selected wash with stronger text. It scrolls its destination area while retaining utility access. Collapsed navigation preserves labels through tooltips and accessible names. The store selector remains independent of route navigation; edit/add management sits beside it and deletion remains a separate small control.

Native tabs use a transparent body and a selected bottom rule. The underline switches immediately through the JavaFX selected state. No moving underline node, web keyboard handler or 220ms prototype tab animation is part of this implementation.

### Read-only detail drawer

The drawer overlays the right edge of the retained list with preferred width (420px), maximum width bounded by the smaller of (480px) or ninety percent of its host, and content padding (18px). Its heading and close control remain above a vertically scrolling body. Content is grouped into labelled sections and fields, including all supplied order items and identifiers, with wrapping values. Focusable values support the copy context menu and the platform copy shortcut. Opening focuses Close; Escape closes; closing restores the earlier focus owner when it is still in the same scene.

### Motion and draft feedback

[WorkspaceMotion](src/main/java/com/vncode/app/shared/WorkspaceMotion.java) owns interruptible local feedback. The drawer opens over (220ms) from a horizontal offset (28px) and opacity (.5); it closes over (140ms) toward offset (20px) and zero opacity. Its spline is `(.16, 1, .3, 1)`. Selection-count feedback fades over (120ms), and the settings edit bar enters over (180ms) from a vertical offset (8px) and opacity (.6). The helper cancels prior animation and restores transform/opacity before replay; native fade defaults are not relabelled as the drawer spline.

The JavaFX system reduced-motion preference or saved manual preference bypasses these transitions. Theme changes update existing roots in place. Znack settings drafts remain bound to complete store identity; the bar is edit feedback, not proof of a remote save. Signing and destructive operations retain their protected dialogs. There are no per-row entrances or deliberate task delays in this motion system.

**The Continuity Rule.** Keep the selected record, list and store identity intact through local feedback. Reduced motion updates the same state immediately.

The [.impeccable/design.json](.impeccable/design.json) sidecar carries native effects, motion, measured layout thresholds, material behavior and representative self-contained HTML/CSS swatches. Those swatches translate the observed controls for documentation; they do not execute JavaFX handlers. Their eight-step color ramps are generated display aids, not additional application tokens.

## Do's and Don'ts

### Do:

- **Do** reuse the shared semantic paints and bundled Inter in both themes.
- **Do** keep the active store and management access visible, with the existing named confirmation for deletion.
- **Do** retain list virtualization, barcode/GTIN data and complete multi-item orders when adapting a surface.
- **Do** allocate layout space for batch actions and let their controls wrap at constrained widths.
- **Do** preserve readable focus, written status, truthful empty states and accessible names for icon-only actions.
- **Do** retain background-only light opacity and immediate feedback when motion is reduced.

### Don't:

- **Don’t** restore the overview’s removed metric trio or its reserved empty band.
- **Don’t** replace the header’s store controls with the former FBS delay text.
- **Don’t** apply opacity to a parent node or window to implement the light material setting.
- **Don’t** add per-row entrances, artificial task delays or decorative animation to the working lists.
- **Don’t** describe the native tab underline as animated or import prototype-only behavior as an implemented rule.
- **Don’t** treat synthetic screenshots or HTML component swatches as marketplace readback or Windows release verification.
