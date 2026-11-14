# Final 43b native pixel-confirmed major-page visual audit

Candidate: `43b82cca683b29e783a33df995afd675a76aa0dd`.

Evidence root: `/tmp/vncode-ios-floating-20261114/captures/43b82cca683b29e783a33df995afd675a76aa0dd/all-pages-native-pixel/pages`.

**The audit team manually inspected all 50 originals, covering all 25 routes in both themes. No actionable major-page material, layout, legibility or enclosing-wrapper blocker was found in this corrected capture set.** The earlier flat neutral material mismatch is absent here. This result applies to the new `all-pages-native-pixel/pages` evidence; the earlier rejected capture set, report and hash list remain unchanged.

## Exact coverage and method

The primary reviewer personally viewed 32 original PNGs through `view_image`: both themes of designer, fbo-orders, fbo-packing, finance, gs1, gtin-sync, guides, kiz-mapping, kiz-warehouse, license, listing-copy, news, ozon, packing, print-history and product-edit.

The delegated reviewer personally viewed the remaining 18 originals through `view_image`: both themes of products-ozon, products-wb, settings, supplies, tnved, welcome, znack, znack-registration and znack-registration-ozon.

Together these are 50/50 originals, 25/25 routes, both themes. Filenames follow `page-{route}-{dark|light}.png`, all 1440×900. This is team coverage, not a claim that one person manually viewed every PNG.

The parent reports that source/runtime copies stayed pinned to 43b without overlays and that this corrected capture waits for eligible surface Patterns after CSS/layout/sync, plus actual Robot root-wallpaper pixels matching the source for five real pulses (observed delta 0), including each of the 25 mounts before theme toggling. This reviewer did not run the harness and does not independently attest to its pulse timing; the visual conclusion is based on examining every resulting PNG and read-only pixel crosschecks.

No source changes, launches, API calls or Maven runs were performed for this audit.

## Material and readability confirmation

Visible blue wallpaper and the associated tint/variation within working surfaces are consistent across all 50 reviewed images. In particular, Products-WB dark now shows blue composition in the shell/gutter and the working pane/ListView, matching the Ozon appearance rather than the rejected neutral-gray result. Designer, finance, supplies and settings also visibly retain blue composition in both themes.

Read-only pixel checks of the new originals support the manual review:

| Sample | Corrected new set | Earlier rejected set, where recorded |
|---|---|---|
| Root gutter (215,450), all 25 dark PNGs | RGB (27,40,63), 25/25 | Products-WB dark RGB (22,23,26) |
| Root gutter (215,450), all 25 light PNGs | RGB (227,234,247), 25/25 | Not used as a universal prior comparison |
| Products-WB dark workpane/ListView (1100,600) | RGB (34,38,47) | RGB (39,39,43) |

These screenshot checks confirm visible composition at capture time. They do not prove continuous stability during arbitrary future interactions or establish complete material ownership for every node from pixels alone.

The top store selector, approximately x232–452/y16–52, remains readable with a neutral fill and a thin focus outline in both themes. The old selected blue wash is absent. Main page/sidebar/header cards retain rounded enclosing boundaries. Settings controls, product toolbars, registration headers/actions, Ozon footer and finance filters/KPIs fit within the window without obscuring primary content.

## Bounded observations retained, not new blockers

- FBO-orders final filter pill is partly outside the horizontal strip near x1375–1415/y140–167; a scrollbar is visible at y168. The capture establishes strip overflow, not interactive reachability.
- GTIN-sync final table header is partly cut at x1395–1410/y283–295. No data is present to assess. This remains a table-width/scroll usability observation, not demonstrated lost data.
- Designer print canvas extends beyond its viewport at x941 with a horizontal scrollbar at x481–840/y826. White paper is intentional and must remain white in dark mode.
- Product-edit empty internal results surface x242–1397/y234–344 has square top corners inside a rounded enclosing page. It does not obscure controls.
- Welcome's square highlighted FBO region is a list row, not an enclosing wrapper.
- Partly visible final product/order rows are bounded by scrolling viewports. Missing thumbnails are synthetic fixture data. Explained empty states and intentional white print-paper/signature canvases are excluded from defects.

## Evidence records and limits

`final-43b-native-pixel-reviewed-sha256.json` records all 50 reviewed PNG hashes; `final-43b-native-pixel-reviewed-root-pixels.json` records the 50 root-gutter samples. Earlier audit/hash artifacts were preserved.

This is static native Linux JavaFX appearance evidence using synthetic fixtures. It does not establish Windows rendering, seller-data acceptance, live API behavior or interactive workflow correctness. Within that bounded scope, the corrected gallery passes the final visual review.
