---
name: VN Code — Native Liquid Glass Workbench
description: Native JavaFX workbench with floating glass throughout internal surfaces and clear store ownership.
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

Liquid Glass Workbench is VN Code’s user-approved Apple-inspired direction adapted to its Java 25 / JavaFX Windows application. Pearl and charcoal grounds support floating navigation and a compact toolbar; large floating glass work sheets keep order, product and registration data as the visual center. Bundled Inter, one blue action family, fine rules and the supplied black VN logo retain the product’s identity without proprietary Apple assets.

Material now combines bounded JavaFX paints with a shared app wallpaper and background-only blur, inset highlights and small chrome shadows. It samples the data-free app wallpaper, never seller content or the operating-system backdrop. Light and dark themes share geometry; the saved material amount changes chrome and internal panels, fields, buttons, working controls and overlays without fading text or the window. Contextual, interruptible native motion connects an action to its result while preserving the underlying working context.

This root record merges the approved 2026-11-08 visual direction with the incumbent product and ownership constraints. [PRODUCT.md](PRODUCT.md) remains the product authority: existing handlers, licensing, confirmations, shop scope, label output and remote-readback requirements remain binding. The earlier [native direction record](docs/design/2026-11-04-native-implementation.md), [preview design](docs/design/previews/2026-11-04/DESIGN.md) and [native capture archive](docs/design/native/2026-11-04/README.md) remain historical evidence for the preceding implementation.

The approved external proposal is recorded in [DESIGN_ARCHITECTURE.md](../vncode-apple-preview-20261108/proposal/docs/DESIGN_ARCHITECTURE.md), [MOTION.md](../vncode-apple-preview-20261108/proposal/docs/MOTION.md) and its [direction contract](../vncode-apple-preview-20261108/reports/direction-contract.md). The contract was captured after initial preview code; its approval-pending language describes that historical preview stage. The Apple direction was user-pinned, not chosen by a random seed. The preview’s recorded seed (`a04f3def2f017b05`) describes that exploration only; the older seed (`4d76fcce170bad27`) remains attached to the older approved direction with its original provenance limitation.

The current token source is [theme.css](src/main/resources/com/vncode/app/styles/theme.css), then [workspace-controls.css](src/main/resources/com/vncode/app/styles/workspace-controls.css), then the material paints installed by [ThemeService](src/main/java/com/vncode/app/shared/ThemeService.java) and cached wallpaper samples from [WorkspaceBackdropMaterial](src/main/java/com/vncode/app/shared/WorkspaceBackdropMaterial.java). Native geometry and behavior come from FXML and the workspace controllers. The external HTML preview and these documentation swatches do not certify current JavaFX behavior. Current native capture and interaction validation is recorded in [the source handoff](docs/validation/ios-motion-20261114/REPORT.md); no Windows performance, installer or marketplace acceptance claim follows from this source snapshot.

**Key Characteristics:**

- Pearl and charcoal grounds, blue action and focus, and written semantic states.
- Floating navigation, toolbar and large glass work sheets with virtualized data.
- Bundled Inter, shared line icons and stable control bounds in both themes.
- Visible store identity, Add inside Manage, and the existing named delete confirmation.
- Native settings with Light, Dark and System, material control and reduced motion.
- Relationship-based JavaFX motion with cancellation and retained list context.

## Colors

Pearl and white working surfaces pair with charcoal and pale ink in the dark theme. Blue identifies action, focus and selection; semantic green, amber and red accompany written operational states.

The frontmatter records the shared base palette and component geometry. Solid `ui-*` and sheet swatches are fallback paints; enabled glass adds wallpaper samples and the dedicated translucent tints supplied by ThemeService. The current runtime CSS, ThemeService and latest all-surface contract below are authoritative for those variants. Unprefixed names describe dark-theme paints without their JavaFX leading hyphen; `light-` names record light-theme differences. Material swatches describe the saved default amount (55).

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
| Charcoal Sheet / White Sheet | `bg-secondary`, `ui-panel`, `ui-field`, `ui-table-header` | Readable translucent data, fields, cards and detail content; solid fallback when glass is disabled. |
| Quiet Control | `ui-control`, `ui-control-hover`, `ui-control-pressed` | Stable ordinary action states. |
| Pale Ink / Carbon Ink | `text-primary` | Main labels and working content. |
| Secondary / Muted Ink | `text-secondary`, `text-muted` | Supporting text, metadata and prompts. |
| Fine Rule / Strong Boundary | `border-color`, `border-color-hover`, `row-rule`, `ui-input-border` | Dividers, row rules and interactive boundaries. |
| Floating Material | `glass-surface`, `glass-elevated`, `glass-highlight` | Cached app-background blur beneath alpha paints and inset highlights throughout internal surfaces. |

Success, danger and warning retain their named text/surface pairs. Filled variants use separate fills and contrasting `on-semantic` or `on-warning` ink. Selection combines fill, written state and native control state rather than color alone.

`ThemeService` preserves existing `app_theme`, `glass_transparency` and `ui_reduced_motion` preferences and adds the saved `glass_enabled` switch. The amount is bounded from zero to one hundred. Both themes apply it to consumed material paints; fields, lists, tables, dialogs and drawer surfaces use dedicated readable translucent tints; foreground text and semantic state colours remain solid. Turning material off uses solid surfaces with the same geometry. Enabled material samples the cached app wallpaper below the final alpha tint; it is not native OS desktop capture or UIKit optical refraction. Four shared 960×600 rasters hold the two themes and their preblurred variants (8.79 MiB raw pixels; not total process memory). Content, field, control and header tint formulas were updated for the latest all-surface brief; the navigation formula is unchanged.

**The Shared Palette Rule.** Use the active theme’s semantic paints for common controls and data states; marketplace identity does not introduce a competing page palette.

**The Text Stays Solid Rule.** Apply material preference to background paints. Text, control boundaries, selection and the application window retain their own paints and opacity. Normal data rows are transparent over a single shared material surface.

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

The recurring work sheet uses the shared sheet inset (20px), with shared cards at the smaller inset (16px). The final workspace stylesheet supplies the effective sheet treatment over older constructor/FXML defaults. Heading/filter groups are compact and the list takes available height. Ordinary navigation has minimum/preferred height (36px); buttons have minimum height (34px), shared fields (36px) and header icons (34 × 34px). Hover, press and focus do not change allocated layout bounds; transient visual transforms remain within their motion context.

Dense list CSS establishes a minimum row height (70px), while source-owned content sizing remains authoritative: current product cells are fixed (72px), Ozon rows use (76px) plus (68px) for each additional item, and registration rows may retain their existing content calculation. Do not compress a multi-item order to the minimum. Shared table headers request (36px); domain-specific layouts may need their own widths. Virtualized lists keep internal scrolling. The shared viewport can scroll the whole page in a short window while retaining room for list content and actions.

Batch controls use their own footer space below the list, with a fine top rule and shared minimum height (52px); action groups wrap instead of covering the last row. The overview retains task links/news and the removal of its old three-counter band.

## Elevation & Depth

Navigation material floats above the ground through layered paints, an inset highlight and a small downward shadow. Working sheets, controls and read-only surfaces now share the floating glass material. Normal rows are transparent over one cached table/list backdrop; selection, focus and semantic states remain distinct. The updated native treatment supplies an unblurred wallpaper ground and samples genuinely blurred wallpaper pixels under chrome/dialog/popup materials using rounded BackgroundFill ImagePatterns. It never wraps the scene root, adds decorative children, blurs foreground text or alters window opacity. Sampling tracks owner geometry and is coalesced on layout/resize/theme events, not animation frames. JavaFX has no Chromium `backdrop-filter`; no OS desktop capture or JNI/FFM dependency is introduced.

| Native effect | Purpose |
| --- | --- |
| `dropshadow(gaussian, rgba(0,0,0,.05), 8, 0, 0, 2)` | Sidebar chrome. |
| `dropshadow(gaussian, rgba(0,0,0,.06), 8, 0, 0, 2)` | Toolbar chrome. |
| `dropshadow(gaussian, rgba(0,0,0,.14), 18, 0, 0, 5)` | Menu, choice and date popup. |
| `dropshadow(gaussian, rgba(0,0,0,.12), 10, 0, 0, 3)` | Tooltip. |
| Theme focus border with the ordinary field shadow retained | Input keyboard focus boundary; no InnerShadow that floods a translucent field during material refresh. |

Material-disabled chrome removes its wallpaper samples and highlight/shadow treatment and keeps solid surfaces. Popup and dialog contents use the current theme; no per-row blur or effect is part of the shared system.

**The Floating Sheet Rule.** Use a shared rounded glass background, fine rules and bounded elevation for working sheets and temporary popups. Avoid a second square enclosing wrapper. Virtualized row controls use rounded containment; the surrounding sheet supplies their shadow.

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

A floating, inset glass work sheet is the recurring container. Shared cards use the same material and fine border with their smaller internal inset (16px). Overview task entries/news keep their existing divider-led presentation. Hover uses the quieter row paint; selection uses its own fill and native state; keyboard focus remains visible. Scrolling data shares one backdrop beneath transparent normal rows, without per-row blur or image copies.

### Inputs / Fields

Fields, combo boxes and choices use a readable glass field paint, strong boundary and the control corner, with solid fallback when glass is disabled. Prompt text is muted. Focus uses the theme boundary while retaining the ordinary field shadow; errors use a danger boundary. Checked and mixed checkbox states use action fill and `on-accent` mark, including dark-theme dark ink. Choice/date popups inherit current theme paints and native keyboard behavior.

### Navigation

The sidebar pairs native line icons and readable text with quiet default/hover states. A separate rounded indicator moves between independent destinations while selected text becomes stronger. The scrollable destination area retains utilities. Store selection is independent of route navigation. Manage beside the store selector contains Edit and Add; deleting stays a separate small action. The palette and onboarding reach the same Manage menu; they do not expose a separate Add action.

Native tabs keep the quiet bottom rule. Their selected underline changes through JavaFX state; selected content crossfades through the motion boundary. No web View Transition or sliding page direction inferred from tab/menu order is part of the native runtime.

### Preferences and command palette

The real native settings route presents Light, Dark and System, a material enable switch, the saved material amount and reduced motion, alongside existing integration/account utilities. Following System tracks JavaFX platform color preference; a saved explicit theme remains explicit. A header toggle deliberately selects the other explicit theme. Existing saved choices are loaded rather than reset by the redesign.

Find Action opens the native palette, also available with Ctrl+K on Windows. It filters existing actions, Enter invokes the first visible result and actions retain their existing access/lifecycle guards. The palette does not create new business permissions. Add remains inside header Manage.

### Read-only detail drawer

The rounded glass drawer overlays the right edge of a retained list with preferred width (420px), bounded by the smaller of (480px) or ninety percent of its host, and inset (18px). The heading/close action stay above a vertically scrolling body of labelled, wrapping values. Copy actions retain full supplied identifiers. Opening focuses Close; Escape closes; closing restores the earlier focus owner if still in the same scene. Product editing remains a protected modal; no separate product-detail route or shared-image transition is claimed.

### Motion and draft feedback

[WorkspaceMotionTokens](src/main/java/com/vncode/app/shared/WorkspaceMotionTokens.java), [WorkspaceMotion](src/main/java/com/vncode/app/shared/WorkspaceMotion.java), [MotionBoundary](src/main/java/com/vncode/app/shared/MotionBoundary.java) and [WorkspaceInteractionMotion](src/main/java/com/vncode/app/shared/WorkspaceInteractionMotion.java) are the current native contract. Independent routes use Sibling (240ms, 6px entry offset); Forward/Back use 300ms with 22px/−18px offsets; tabs use 180ms. A bounded bitmap of the departing viewport can exit while the new page is already live. Rapid navigation retains at most one departure per boundary; no old controller or business completion is retained. Shared header/sidebar remain in place.

Dialogs and popups use contextual scale with a bounded local pivot. Modal open/close tokens are 240/150ms and popover open/close tokens 170/120ms. Drawer open/close is 220/130ms; draft feedback is 180ms. Transform springs are bounded for their context; whole-window roots use a critically damped envelope that never scales beyond the viewport. Foreground/window opacity is preserved. Native close, result and cleanup are immediate; only an input-transparent visual ghost can perform the exit effect.

A report with text input, passwords, canvas or WebView uses a silhouette for exit, preserving entered content from snapshots. Read-only reports may use a bounded snapshot. Store changes invalidate old report pixels. Local press/release and keyboard feedback do not consume, refire, delay or duplicate business actions; text entry, scrollbar and canvas geometry remain unchanged.

System or saved reduced-motion preference removes spatial movement and scale; eligible state feedback may retain the 40ms opacity response. Cancellation restores owned transforms and removes temporary layers. No per-row entrance, blur or artificial API delay is introduced. A bounded viewport departure snapshot can include visible rendered rows, but does not retain the old controller. Newly mounted logical ScrollPane/Tab content keeps its material attachment listener until its native skin joins the Scene; reduced motion needs no theme toggle to receive glass.

**The Continuity Rule.** Keep the selected record, list and store identity intact through local feedback. Reduced motion completes the same state and focus updates without spatial movement.

The [.impeccable/design.json](.impeccable/design.json) sidecar holds effects, motion, layout thresholds, material behavior and self-contained HTML/CSS translations for documentation. Its color ramps are generated display aids, not additional runtime paints. The current source handoff records the final 43b native capture and focused tests. Windows build/release and seller acceptance are separate; preview measurements are not native performance results.

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


### Historical material follow-up — 2026-11-14

The user rejected the flat ground/paint-only appearance and the square wrappers around rounded report content. This request supersedes the earlier paint-fallback limit, while retaining opaque work lists and all product behavior. Source/test checkpoint `b00d577a85bc82720fa40f93151ccfcc66622f56` adds the cached app-background blur and removes app-owned square DialogPane content backgrounds and standalone table-header corner overpaint. Native OS captions remain native. [Current implementation/evidence](docs/validation/ios-motion-20261114/REPORT.md) distinguishes this source from the earlier audit and 1ed video checkpoint; it does not claim pixel-identical iOS or Windows seller acceptance.


### All internal surfaces and app-dialog frames — latest user steering

The user then explicitly required floating glass inside every page, table, field, button, menu, Settings group and report; keeping working areas opaque is no longer the approved appearance contract. Foreground opacity, contrast thresholds, data, actions and virtualization remain binding. The shared content/field/control/header alpha at amount55 is .719/.779/.699/.779, with solid fallback when glass is disabled. Four cached wallpaper rasters are still shared; no per-row captures or blur/effects are added. Table/List/Tree controls get caller-safe rounded containment; their floating sheet/dialog supplies elevation because a whole-Control clip also clips that Control's shadow.

Owned Dialogs explicitly opt into transparent rounded presentation before first show; the original DialogPane remains the Scene root and retains header, content, buttons, results, close veto, owner and modality. A small unmanaged titlebar supplies close/drag/conditional resize; ordinary app Stages and native OS pickers/signature windows keep their existing window policy. Settings loses the full opaque enclosing card and uses padded floating sections. Print-designer outer workspace is glass while the white printable paper subtree stays byte-identical. UI hint copy now describes the full scope in all four locales. Earlier opaque-table test expectations are archived and superseded explicitly rather than treated as business failures. Source and final validation are recorded in the current handoff; the older b00 gallery is a historical checkpoint rejected for incomplete internal coverage.


### Historical 43b verification and lifecycle corrections

The source of this historical checkpoint is `43b82cca683b29e783a33df995afd675a76aa0dd`. The [handoff](docs/validation/ios-motion-20261114/REPORT.md) records 197 focused cases in 20 suites and final native evidence: 25 routes mounted without a theme toggle, 50 page PNGs, 15 original video keyframes and six transparent report windows. The final static barrier checks every eligible logical material target and actual native wallpaper pixels after post-Scene synchronization; Pattern presence or a fixed pre-CSS pulse count alone is not acceptance. Earlier 1ed/b00/3c/3ce and pre-paint 43b captures remain historical.

Two verified corrections preserve the design contract: a focused field keeps its border and ordinary shadow without the InnerShadow that briefly washed its translucent fill blue; logical content that has not yet joined a Scene retains attachment listeners instead of losing material until a theme rebuild. True logical removal and foreign-Scene cleanup retain their previous behavior. Native evidence uses synthetic Linux JavaFX components, not Windows driver, live seller, signing or paid-purchase acceptance. The separately requested 1.6.10 publication still requires its release checks.


### f3e successor — caller ownership and truthful Windows verification

The source of this superseded checkpoint is `f3e98725ee2892abf225cb66978ed9302fd49489`. The 43b Windows full run failed; the earlier Linux gallery and source backup remain historical. A Region-local Boolean now retains the caller-background exclusion across internal theme rescans even after native CSS changes its USER origin to AUTHOR. The marker stores no Scene/controller and leaves binding, inline paint, logical attachment and foreign-Scene cleanup policy intact.

Header adjacency tests measure transformed layout bodies rather than DropShadow illumination; the 0–20px condition is retained with negative overlap/gap controls. The native contrast fixture alone is topmost/on-screen and must own real focus before Robot sampling; text 4.5:1, focus 3:1 and opacity1 checks remain. These are measurement corrections, not changes to application window behavior.

CI separately controls transient motion settings on its isolated Windows worker, records/restores the original state, and uses fresh JVM public-preference probes for both normal and OS Reduced Motion. Production accessibility authority is unchanged. Existing preflight tests are retained and expanded; the unfiltered full verification and native/upgrade gates remain. The 200-case/21-suite Windows focused run on 86b has Java/test/build inputs byte-identical to f3e, but does not certify the new full run. That full Windows run was later canceled after its early preflight passed, before packaging, for the confirmed embedded-frame defect. Fresh security 43639388384 has 8 unchanged Java findings, including one severity 7.5 high-gate failure, and zero JavaScript findings; raw finding/codeflow/location/fingerprint records match 1.6.9/43b with zero new/resolved. GS1 extraction error/warning notifications remain unchanged and coverage limited; informational/note notifications differ, so this is not an all-notification-equivalence or all-security-green claim. No release or seller/API acceptance is inferred.


### b74 successor — one painted frame per working sheet

Current source is `b74b5edfd46636e8779c94f467eef01c03c50a14`. Final native review of f3e identified a thin closed rectangular frame around embedded Finance/FBO tables. The inherited edge-gradient remained visible at insets 0,1 even with zero border/radius. The embedded selector now uses a single existing glass-content fill at inset 0; the enclosing rounded sheet supplies its frame/elevation. Standalone controls keep their existing rounded layered treatment. No layout, row state, FXML, API or caller-ownership change is introduced.

A 38-line additive test loads the real Finance/FBO tables in both themes and compares rendered edge/interior pixels; original five material tests/helpers are byte-retained. Native RED 5/6 with 10 edge-assertion failures becomes GREEN 6/6. This is bounded evidence, not a final full-run count. Exact-source local Maven verify now passes 201 tests/21 suites with 0 failure/error/skip and 1424 Git-matched inputs; JAR SHA-256 is `4e44fe53f9b9976c5b719eee556c860ec7fe02fa02d400a9ecdc0e192231f620`. This focused result does not replace full Windows acceptance. The fresh exact-source native capture now has completed manual review of all50page originals and15video keyframes; the combined32+18page hashmaps match capture/public bytes. All25same-theme mounts/50native pixel/material gates pass with delta0; no concrete blocker was found in that bounded coverage. [Frozen native receipt](docs/validation/ios-motion-20261114/native-evidence-b74/FINAL-RECEIPT.json). Full Windows 43641520595 is still pending. Security 43641523097 has eight unchanged Java findings, one severity 7.5 high-gate failure, zero new/resolved, zero JavaScript findings and passing lint. Serialized raw JSON differs in 107 numbered references (99 artifact/eight rule); full URI/rule-ID resolution proves equivalent references against 1.6.9/f3e/43b, with all other finding fields, lines, codeflows and fingerprints retained. This is not raw byte equality. GS1 extraction error/warning coverage limits remain; informational/note notifications differ. See [security evidence](docs/validation/ios-motion-20261114/windows-b74/security/REPORT.md); no gate waiver or all-security-green claim is made. F3e's running full was canceled after its preflight passed; repeated tests are not summed as distinct cases. Prior gallery/source archives remain historical and immutable. No Latest or seller acceptance is claimed here.


### Published 1.6.10 — 2026-11-15

Version1.6.10 is now immutable GitHub Latest release445977967, published10:40:34UTC (13:40:34Moscow). App/test/build source is b74b5edfd46636e8779c94f467eef01c03c50a14; later changes are documentation only. FullWindows43641520595 passed4700cases/585suites (4698passed,0failures/errors,2OSskips); native/DPAPI/install/upgrade gates passed. Publisher43705139132 and200/200Node checks passed. All16public files were downloaded and compared to independent CI hashes; original1.6.9 updater reportedAVAILABLE1.6.10 with all75published JARs unchanged. Old19releases/306assets/48rawtags remain unchanged. [Final handoff](docs/validation/2026-11-15-vncode-1610-release-handoff.md).

Earlier pending statements above are historical checkpoints, not current release status. Security still has8existing Java findings including one severity7.5high-gate failure and the GS1 extraction coverage limit; no waiver/all-green claim. Native screenshots use synthetic Linux/JavaFX data; this release work did not purchase/sign KIZ, activate a license or write seller APIs.
