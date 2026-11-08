---
name: VN Code — Native Liquid Glass Workbench
description: Native JavaFX workbench with bounded navigation material, opaque work sheets and clear store ownership.
colors:
  bg-primary: '#17181b'
  nav-surface: '#292a2e'
  bg-secondary: '#232428'
  bg-tertiary: '#303136'
  border-color: '#3a3b41'
  border-color-hover: '#898b95'
  row-rule: '#33343a'
  text-primary: '#f1f1f4'
  text-secondary: '#c1c2cb'
  text-muted: '#adafb9'
  accent: '#9ac6ff'
  accent-hover: '#b3d5ff'
  accent-active: '#83b7fb'
  accent-text: '#9ac6ff'
  on-accent: '#142c4a'
  on-semantic: '#ffffff'
  on-warning: '#30230c'
  focus: '#9ac6ff'
  focus-primary: '#142c4a'
  ui-panel: '#232428'
  ui-control: '#2c2d32'
  ui-control-hover: '#3b3d44'
  ui-control-pressed: '#454750'
  ui-field: '#232428'
  ui-input-border: '#858792'
  ui-row-hover: '#2c2e34'
  ui-selected: '#323d4b'
  ui-table-header: '#232428'
  nav-selected: '#353e49'
  danger: '#ff9aa9'
  danger-hover: '#bc3c55'
  danger-fill: '#a92d46'
  success: '#87d7b8'
  success-hover: '#237757'
  success-fill: '#176747'
  warning: '#f3c570'
  warning-hover: '#e7b556'
  warning-fill: '#f3c570'
  success-surface: '#243e33'
  danger-surface: '#482b34'
  warning-surface: '#443920'
  light-bg-primary: '#e9ebf0'
  light-nav-surface: '#f7f8fa'
  light-bg-secondary: '#ffffff'
  light-bg-tertiary: '#f0f1f5'
  light-border-color: '#e0e2e9'
  light-border-color-hover: '#818493'
  light-row-rule: '#eceef3'
  light-text-primary: '#24252a'
  light-text-secondary: '#555966'
  light-text-muted: '#606472'
  light-accent: '#0762cf'
  light-accent-hover: '#0656b8'
  light-accent-active: '#06499a'
  light-accent-text: '#0762cf'
  light-on-accent: '#ffffff'
  light-focus: '#0762cf'
  light-focus-primary: '#ffffff'
  light-ui-panel: '#ffffff'
  light-ui-control: '#ffffff'
  light-ui-control-hover: '#f0f3f9'
  light-ui-control-pressed: '#e5eaf2'
  light-ui-field: '#ffffff'
  light-ui-input-border: '#85899a'
  light-ui-row-hover: '#f4f6fa'
  light-ui-selected: '#e8eef7'
  light-ui-table-header: '#ffffff'
  light-nav-selected: '#e7edf7'
  light-danger: '#b62440'
  light-danger-hover: '#9f1d36'
  light-danger-fill: '#b62440'
  light-success: '#116b4c'
  light-success-hover: '#0d5d40'
  light-success-fill: '#116b4c'
  light-warning: '#865000'
  light-warning-hover: '#e5b34f'
  light-success-surface: '#e3f3eb'
  light-danger-surface: '#fce9ed'
  light-warning-surface: '#fff2d8'
  glass-surface: rgba(38,38,42,0.552)
  glass-elevated: rgba(48,48,53,0.577)
  glass-highlight: rgba(255,255,255,0.092)
  light-glass-surface: rgba(255,255,255,0.552)
  light-glass-elevated: rgba(255,255,255,0.577)
  light-glass-highlight: rgba(255,255,255,0.213)
typography:
  headline:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: 700
  welcome-title:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: 700
  section-title:
    fontFamily: Inter
    fontSize: 20px
    fontWeight: 700
  title:
    fontFamily: Inter
    fontSize: 16px
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
    fontSize: 14px
    fontWeight: 700
  metadata:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: 400
  column-label:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: 400
  badge:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: 400
  control-strong:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: 700
rounded:
  detail: 6px
  control: 10px
  surface: 18px
  icon: 10px
  popup: 14px
  compact: 8px
  selection: 5px
spacing:
  tight: 4px
  compact: 6px
  group: 8px
  control-gap: 10px
  panel: 12px
  page: 16px
  detail: 18px
  sheet: 20px
  header-inline: 12px
components:
  button-secondary:
    backgroundColor: '{colors.ui-control}'
    textColor: '{colors.text-primary}'
    typography: '{typography.control}'
    rounded: '{rounded.control}'
    padding: 6px 11px
  button-secondary-hover:
    backgroundColor: '{colors.ui-control-hover}'
  button-primary:
    backgroundColor: '{colors.accent}'
    textColor: '{colors.on-accent}'
    typography: '{typography.control-strong}'
    rounded: '{rounded.control}'
    padding: 6px 11px
  button-primary-hover:
    backgroundColor: '{colors.accent-hover}'
  button-primary-active:
    backgroundColor: '{colors.accent-active}'
  button-danger:
    backgroundColor: '{colors.danger-fill}'
    textColor: '{colors.on-semantic}'
    rounded: '{rounded.control}'
    padding: 6px 11px
  button-success:
    backgroundColor: '{colors.success-fill}'
    textColor: '{colors.on-semantic}'
    rounded: '{rounded.control}'
    padding: 6px 11px
  button-warning:
    backgroundColor: '{colors.warning-fill}'
    textColor: '{colors.on-warning}'
    rounded: '{rounded.control}'
    padding: 6px 11px
  header-action:
    backgroundColor: transparent
    textColor: '{colors.text-primary}'
    typography: '{typography.control}'
    rounded: '{rounded.control}'
    padding: 6px 11px
  input:
    backgroundColor: '{colors.ui-field}'
    textColor: '{colors.text-primary}'
    typography: '{typography.body}'
    rounded: '{rounded.control}'
    padding: 6px 10px
  navigation-item:
    backgroundColor: transparent
    textColor: '{colors.text-secondary}'
    typography: '{typography.control}'
    rounded: '{rounded.control}'
    padding: 6px 10px
    height: 36px
  navigation-item-selected:
    backgroundColor: '{colors.nav-selected}'
    textColor: '{colors.text-primary}'
    typography: '{typography.control-strong}'
  panel:
    backgroundColor: '{colors.ui-panel}'
    textColor: '{colors.text-primary}'
    rounded: '{rounded.surface}'
    padding: 16px
  data-sheet:
    backgroundColor: '{colors.bg-secondary}'
    textColor: '{colors.text-primary}'
    rounded: '{rounded.surface}'
    padding: 1px
  warning-chip:
    backgroundColor: '{colors.warning-surface}'
    textColor: '{colors.warning}'
    typography: '{typography.control}'
    rounded: '{rounded.compact}'
    padding: 4px 9px
  status-badge:
    backgroundColor: '{colors.bg-tertiary}'
    textColor: '{colors.text-secondary}'
    typography: '{typography.badge}'
    rounded: '{rounded.detail}'
    padding: 3px 6px
  workspace-tab:
    backgroundColor: transparent
    textColor: '{colors.text-secondary}'
    typography: '{typography.control}'
    rounded: 0px
    padding: 8px 12px
  workspace-tab-selected:
    textColor: '{colors.text-primary}'
    typography: '{typography.control-strong}'
  detail-drawer:
    backgroundColor: '{colors.bg-secondary}'
    textColor: '{colors.text-primary}'
    padding: 18px
    width: 420px
    rounded: '{rounded.surface}'
  selection-bar:
    backgroundColor: '{colors.bg-secondary}'
    textColor: '{colors.text-primary}'
    padding: 9px 0 0 0
  button-secondary-active:
    backgroundColor: '{colors.ui-control-pressed}'
  workspace-sheet:
    backgroundColor: '{colors.bg-secondary}'
    textColor: '{colors.text-primary}'
    rounded: '{rounded.surface}'
    padding: 20px
  glass-sidebar:
    backgroundColor: '{colors.glass-surface}'
    textColor: '{colors.text-primary}'
    rounded: '{rounded.surface}'
    padding: 18px 10px 10px 10px
    width: 202px
  glass-header:
    backgroundColor: '{colors.glass-surface}'
    textColor: '{colors.text-primary}'
    rounded: '{rounded.surface}'
    padding: 7px 12px
    height: 52px
---

# Design System: VN Code — Native Liquid Glass Workbench

## Overview

**Creative North Star: "Liquid Glass Workbench"**

Liquid Glass Workbench is VN Code’s user-approved Apple-inspired direction adapted to its Java 25 / JavaFX Windows application. Pearl and charcoal grounds support floating navigation and a compact toolbar; opaque work sheets keep order, product and registration data as the visual center. Bundled Inter, one blue action family, fine rules and the supplied black VN logo retain the product’s identity without proprietary Apple assets.

Material is bounded JavaFX background paint with inset highlights and small chrome shadows. It does not sample or blur the operating-system backdrop. Light and dark themes share geometry; the saved material amount changes chrome and eligible overlays without fading text, table rows or the window. Contextual, interruptible native motion connects an action to its result while preserving the underlying working context.

This root record merges the approved 2026-11-08 visual direction with the incumbent product and ownership constraints. [PRODUCT.md](PRODUCT.md) remains the product authority: existing handlers, licensing, confirmations, shop scope, label output and remote-readback requirements remain binding. The earlier [native direction record](docs/design/2026-11-04-native-implementation.md), [preview design](docs/design/previews/2026-11-04/DESIGN.md) and [native capture archive](docs/design/native/2026-11-04/README.md) remain historical evidence for the preceding implementation.

The approved external proposal is recorded in [DESIGN_ARCHITECTURE.md](../vncode-apple-preview-20261108/proposal/docs/DESIGN_ARCHITECTURE.md), [MOTION.md](../vncode-apple-preview-20261108/proposal/docs/MOTION.md) and its [direction contract](../vncode-apple-preview-20261108/reports/direction-contract.md). The contract was captured after initial preview code; its approval-pending language describes that historical preview stage. The Apple direction was user-pinned, not chosen by a random seed. The preview’s recorded seed (`a04f3def2f017b05`) describes that exploration only; the older seed (`4d76fcce170bad27`) remains attached to the older approved direction with its original provenance limitation.

The current token source is [theme.css](src/main/resources/com/vncode/app/styles/theme.css), then [workspace-controls.css](src/main/resources/com/vncode/app/styles/workspace-controls.css), then the material paints installed by [ThemeService](src/main/java/com/vncode/app/shared/ThemeService.java). Native geometry and behavior come from FXML and the workspace controllers. The external HTML preview, its videos and these documentation swatches do not certify current JavaFX behavior. Current native capture and interaction validation is pending at [the native evidence workspace](../vncode-native-glass-20261108/); no Windows performance, installer or marketplace acceptance claim follows from this source snapshot.

**Key Characteristics:**

- Pearl and charcoal grounds, blue action and focus, and written semantic states.
- Floating navigation and toolbar around large opaque, virtualized work sheets.
- Bundled Inter, shared line icons and stable control bounds in both themes.
- Visible store identity, Add inside Manage, and the existing named delete confirmation.
- Native settings with Light, Dark and System, material control and reduced motion.
- Relationship-based JavaFX motion with cancellation and retained list context.

## Colors

Pearl and white working surfaces pair with charcoal and pale ink in the dark theme. Blue identifies action, focus and selection; semantic green, amber and red accompany written operational states.

The frontmatter is normative. Unprefixed names record dark-theme looked-up paints without their JavaFX leading hyphen; `light-` names record differences in the light theme. Shared colors keep the unprefixed value. Component tokens describe the dark variant; light controls substitute the corresponding light paint. The material colors record the effective runtime default amount (55), not the stylesheet-only fallback.

### Primary

| Character | Token family | Application |
| --- | --- | --- |
| Action Blue / Pale Action Blue | `accent`, `accent-hover`, `accent-active` | Primary controls and checked selection controls. |
| Action Ink | `on-accent` | White on light-theme blue; dark ink on dark-theme pale blue. |
| Blue Detail | `accent-text` | Navigation icons, selected counts and native tab underlines. |
| Blue Wash | `ui-selected`, `nav-selected` | Data selection and the sidebar indicator. |
| Focus Boundary | `focus`, `focus-primary` | Keyboard boundary, including contrasting primary-button focus. |

### Neutral

| Character | Token family | Application |
| --- | --- | --- |
| Charcoal / Pearl Ground | `bg-primary` | Ground behind the floating shell. |
| Opaque Navigation | `nav-surface` | Solid fallback when material is disabled. |
| Charcoal Sheet / White Sheet | `bg-secondary`, `ui-panel`, `ui-field`, `ui-table-header` | Opaque data, fields, cards and detail content. |
| Quiet Control | `ui-control`, `ui-control-hover`, `ui-control-pressed` | Stable ordinary action states. |
| Pale Ink / Carbon Ink | `text-primary` | Main labels and working content. |
| Secondary / Muted Ink | `text-secondary`, `text-muted` | Supporting text, metadata and prompts. |
| Fine Rule / Strong Boundary | `border-color`, `border-color-hover`, `row-rule`, `ui-input-border` | Dividers, row rules and interactive boundaries. |
| Navigation Material | `glass-surface`, `glass-elevated`, `glass-highlight` | Background paints and inset highlights for chrome and eligible overlays. |

Success, danger and warning retain their named text/surface pairs. Filled variants use separate fills and contrasting `on-semantic` or `on-warning` ink. Selection combines fill, written state and native control state rather than color alone.

`ThemeService` preserves existing `app_theme`, `glass_transparency` and `ui_reduced_motion` preferences and adds the saved `glass_enabled` switch. The amount is bounded from zero to one hundred. Both themes apply it to consumed material paints only; fields, lists, tables, dialogs and drawer content retain opaque semantic paints. Turning material off uses solid navigation and overlay surfaces with the same geometry. This is a paint fallback, not native OS backdrop blur. Exact formulas and stylesheet fallback values live in the sidecar.

**The Shared Palette Rule.** Use the active theme’s semantic paints for common controls and data states; marketplace identity does not introduce a competing page palette.

**The Text Stays Solid Rule.** Apply material preference to chrome and eligible overlay background paints only. Text, working rows, control boundaries, selection and the application window retain their own paints and opacity.

## Typography

**Body and interface font:** bundled Inter, loaded from the existing regular and bold assets by `UiFonts`. There is no separate display face, proprietary Apple font, authored tracking scale or native tabular-number feature.

The compact hierarchy separates page titles, section titles, record names and supporting data. The frontmatter records the roles actually reused in native styles, including ordinary and bold control text.

| Role | Use |
| --- | --- |
| `headline`, `welcome-title` | Main page and overview headings. |
| `section-title` | Secondary sections and native dialog headings. |
| `title` | Drawer heading and smaller section hierarchy. |
| `body` | General copy and fields. |
| `control`, `control-strong` | Buttons, tabs and navigation; bold for primary/selected state. |
| `record-title` | Product names and emphasized order data. |
| `metadata`, `column-label` | Supporting row data and quiet column headings. |
| `badge` | Compact written semantic states. |

Local exceptions remain visible: the sidebar wordmark is bold (20px), navigation group labels are regular (12px), and the edition footer is (11px). Group labels organize destinations rather than acting as an eyebrow above page headings. Current source may retain domain-specific table typography; the shared scale does not erase meaning or full identifiers.

**The Working Hierarchy Rule.** Use Inter weight and size to separate page titles, record titles and supporting data. Keep metadata readable without giving every cell heading weight.

## Layout

The desktop shell has outer inset (8px), a sidebar-to-content gap (10px) and header-to-sheet gap (8px). Expanded navigation is (202px), narrows to (194px) below scene width (1350px), and automatically becomes an icon rail (68px) below (1100px). Manual collapse can override automatic layout; returning to automatic layout clears that override. Collapsed controls keep accessible names and tooltips. These are native scene measurements, not mobile web breakpoints.

The floating header is (52px) with inset (7px / 12px). Below header content width (800px), the store selector changes preferred width from (220px) to (180px), Manage and Find Action show their named icons, and secondary diagnostics remain in overflow. The theme shortcut stays accessible in the header. Manage sits beside the store name and contains Edit and Add; the small delete action remains separate and confirmed. Add is not a sidebar destination.

The recurring work sheet uses the shared sheet inset (20px), with shared cards at the smaller inset (16px). The final workspace stylesheet supplies the effective sheet treatment over older constructor/FXML defaults. Heading/filter groups are compact and the list takes available height. Ordinary navigation has minimum/preferred height (36px); buttons have minimum height (34px), shared fields (36px) and header icons (34 × 34px). Hover, press and focus do not change bounds.

Dense list CSS establishes a minimum row height (70px), while source-owned content sizing remains authoritative: product cells are fixed (76px), Ozon rows use (76px) plus (68px) for each additional item, and registration rows may retain their existing content calculation. Do not compress a multi-item order to the minimum. Shared table headers request (36px); domain-specific layouts may need their own widths. Virtualized lists keep internal scrolling. The shared viewport can scroll the whole page in a short window while retaining room for list content and actions.

Batch controls use their own footer space below the list, with a fine top rule and shared minimum height (52px); action groups wrap instead of covering the last row. The overview retains task links/news and the removal of its old three-counter band.

## Elevation & Depth

Navigation material floats above the ground through layered paints, an inset highlight and a small downward shadow. Opaque work sheets, rows, ordinary controls and read-only content remain flat, separated by tone and fine rules. This bounded native treatment is the implementation of the approved glass direction; JavaFX has no Chromium `backdrop-filter`, and no OS backdrop capture, transparent-window effect or JNI/FFM dependency is implied.

| Native effect | Purpose |
| --- | --- |
| `dropshadow(gaussian, rgba(0,0,0,.05), 8, 0, 0, 2)` | Sidebar chrome. |
| `dropshadow(gaussian, rgba(0,0,0,.06), 8, 0, 0, 2)` | Toolbar chrome. |
| `dropshadow(gaussian, rgba(0,0,0,.14), 18, 0, 0, 5)` | Menu, choice and date popup. |
| `dropshadow(gaussian, rgba(0,0,0,.12), 10, 0, 0, 3)` | Tooltip. |
| `innershadow(gaussian,-focus,1,1,0,0)` | Input keyboard focus boundary. |

Material-disabled chrome removes its highlight/shadow treatment and keeps solid surfaces. Popup and dialog contents use the current theme; no per-row blur or effect is part of the shared system.

**The Bordered Sheet Rule.** Keep opaque working sheets flat and separate them with tone and fine rules. Use the bounded native shadows for floating chrome and temporary popups.

## Shapes

Sheets, shell chrome, dialogs and read-only drawers use the surface corner. Controls, fields, navigation selection and icon buttons share the control corner. Popups use the smaller popup corner; chips, menu entries and tooltips use the compact corner. Written badges and checkboxes retain their own smaller corners. Ordinary checkbox/radio boxes have a minimum dimension (18px); product checkboxes retain their existing larger local box (20px).

Working boundaries use fine strokes (1px). Tabs are square-edged with a selected bottom rule (2px). Radios and status dots keep their native circular shape. Feather line icons come through Ikonli; keep consistent library artwork rather than extending existing isolated glyph substitutions. The supplied VN black logo remains the identity asset.

## Components

### Buttons

Compact, gently curved controls retain stable geometry. Ordinary actions use neutral fill and a visible input boundary. Primary actions use the theme action fill with bold control text and contrasting `on-accent` ink; the dark primary is pale blue with dark ink. Success, danger and warning fills use their separate contrasting ink. Hover and press resolve through the current theme. Shared disabled controls retain full opacity; the final native validation must verify variant-specific disabled appearance.

Header management and utilities use transparent default backgrounds and neutral hover feedback. The separate delete icon retains danger color, its existing handler and the named confirmation. Product bulk-edit controls remain neutral outlined actions despite their native primary class; they do not turn the whole footer blue.

### Chips

Product repair filters use warning surface/text and a compact corner. Selected repair filters add a warning boundary. Written badges retain semantic surface/text pairs. Keep the state name and count visible rather than relying on color.

### Cards / Containers

An opaque, inset work sheet is the recurring container. Shared cards use the same sheet fill and fine border with their smaller internal inset (16px). Overview task entries/news keep their existing divider-led presentation. Hover uses the quieter row paint; selection uses its own fill and native state; keyboard focus remains visible. Material belongs to surrounding chrome and eligible overlays, not the scrolling data.

### Inputs / Fields

Fields, combo boxes and choices use a solid field paint, strong boundary and the control corner. Prompt text is muted. Focus adds the theme boundary and inner focus effect; errors use a danger boundary. Checked and mixed checkbox states use action fill and `on-accent` mark, including dark-theme dark ink. Choice/date popups inherit current theme paints and native keyboard behavior.

### Navigation

The sidebar pairs native line icons and readable text with quiet default/hover states. A separate rounded indicator moves between independent destinations while selected text becomes stronger. The scrollable destination area retains utilities. Store selection is independent of route navigation. Manage beside the store selector contains Edit and Add; deleting stays a separate small action. The palette and onboarding reach the same Manage menu; they do not expose a separate Add action.

Native tabs keep the quiet bottom rule. Their selected underline changes through JavaFX state; selected content crossfades through the motion boundary. No web View Transition or sliding page direction inferred from tab/menu order is part of the native runtime.

### Preferences and command palette

The real native settings route presents Light, Dark and System, a material enable switch, the saved material amount and reduced motion, alongside existing integration/account utilities. Following System tracks JavaFX platform color preference; a saved explicit theme remains explicit. A header toggle deliberately selects the other explicit theme. Existing saved choices are loaded rather than reset by the redesign.

Find Action opens the native palette, also available with Ctrl+K on Windows. It filters existing actions, Enter invokes the first visible result and actions retain their existing access/lifecycle guards. The palette does not create new business permissions. Add remains inside header Manage.

### Read-only detail drawer

The solid rounded drawer overlays the right edge of a retained list with preferred width (420px), bounded by the smaller of (480px) or ninety percent of its host, and inset (18px). The heading/close action stay above a vertically scrolling body of labelled, wrapping values. Copy actions retain full supplied identifiers. Opening focuses Close; Escape closes; closing restores the earlier focus owner if still in the same scene. Product editing remains a protected modal; no separate product-detail route or shared-image transition is claimed.

### Motion and draft feedback

[WorkspaceMotionTokens](src/main/java/com/vncode/app/shared/WorkspaceMotionTokens.java), [WorkspaceMotion](src/main/java/com/vncode/app/shared/WorkspaceMotion.java) and native navigation boundaries implement relationship-based motion. Independent route content enters with a small vertical offset (6px) over (200ms); shared header/sidebar remain in place. Native tabs/selected-count feedback use (150ms). Opening a WB supply for printing uses Forward and returning from its detail uses Back, with offsets (22px / -18px) over (260ms). Other independent destinations use Sibling; product editing remains modal. The sidebar indicator and manual-collapse content adjustment use (220ms).

Dialogs enter with local scale (.96 → 1) and fade over (240ms); popups use (.97 → 1) over (170ms), with a bounded local pivot. Drawers enter from (30px) over (220ms), close over (130ms), and restore focus. Draft feedback enters from (8px) over (180ms). These use the native arrival spline `(.16, 1, .3, 1)`. Closing native dialogs is not recorded as the preview’s separate close animation.

The system reduced-motion preference or saved manual preference removes spatial movement and scale; the shared helper retains a short opacity response (.85 → 1 over 40ms). Sidebar movement updates directly. The helper cancels previous motion, restores original transforms/opacity and cleans up when detached or hidden. No animation snapshots the table, adds row entrances, delays API work or proves a remote save. Settings drafts remain bound to complete store identity, and signing/destructive operations retain their guarded dialogs.

**The Continuity Rule.** Keep the selected record, list and store identity intact through local feedback. Reduced motion completes the same state and focus updates without spatial movement.

The [.impeccable/design.json](.impeccable/design.json) sidecar holds effects, motion, layout thresholds, material behavior and self-contained HTML/CSS translations for documentation. Its color ramps are generated display aids, not additional runtime paints. Current native/Windows validation remains pending; preview measurements are not native performance results.

## Do's and Don'ts

### Do:

- **Do** reuse the shared semantic paints and bundled Inter in both themes.
- **Do** keep the active store and Manage visible, place Add inside Manage, and retain the named confirmation for deletion.
- **Do** retain list virtualization, barcode/GTIN data and complete multi-item orders when adapting a surface.
- **Do** allocate layout space for batch actions and let their controls wrap at constrained widths.
- **Do** preserve readable focus, written status, truthful empty states and accessible names for icon-only actions.
- **Do** retain existing appearance choices and respect both system and manual reduced motion.

### Don't:

- **Don’t** restore the overview’s removed metric trio or its reserved empty band.
- **Don’t** replace the header’s store controls with the former FBS delay text.
- **Don’t** apply material opacity to a parent node or window, or blur/fade the scrolling working rows.
- **Don’t** add per-row entrances, artificial task delays or decorative animation to the working lists.
- **Don’t** promise OS backdrop blur, shared-product-image routing or browser View Transitions from this native implementation.
- **Don’t** treat synthetic screenshots, HTML swatches or source inspection as marketplace readback, Windows performance or installer verification.
