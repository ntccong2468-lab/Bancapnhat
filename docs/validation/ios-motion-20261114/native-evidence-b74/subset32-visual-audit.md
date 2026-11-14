# Final b74 native visual audit — assigned 32 originals

Candidate: `b74b5edfd46636e8779c94f467eef01c03c50a14`.

Final evidence root: `/tmp/vncode-ios-floating-20261114/captures/b74b5edfd46636e8779c94f467eef01c03c50a14/all-pages/pages`.

**Personally viewed all 32 assigned final PNG originals through `view_image`. No actionable material, legibility, clipping or extra enclosing-wrapper blocker found in this subset. Finance and both FBO pages no longer show the old closed 1-pixel empty-table gradient frame in either theme.**

## Coverage

Both dark and light originals were viewed for these 16 routes: designer, fbo-orders, fbo-packing, finance, gs1, gtin-sync, guides, kiz-mapping, kiz-warehouse, license, listing-copy, news, ozon, packing, print-history and product-edit.

Each exact filename is `page-{route}-{dark|light}.png`; all 32 are 1440×900. `final-b74-subset32-reviewed-sha256.json` contains the plain filename → SHA-256 mapping for these 32 reviewed final originals. This report does not claim review of the remaining 18 page PNGs or the final video/keyframes owned by other reviewers.

Additionally viewed six historical f3 originals only for before/after comparison: both themes of finance, fbo-orders and fbo-packing under `captures/f3e98725ee2892abf225cb66978ed9302fd49489/all-pages-native-pixel-successor/pages`. Those are not counted as final evidence and were not overwritten.

Read-only audit: no source changes, API calls, Maven, Git operations or application launches.

## Finance/FBO frame comparison

In f3, Finance's empty table has a thin enclosing left/right/bottom gradient edge approximately x241–1411/y479–831. FBO-orders shows the same extra closing frame around the empty split tables, approximately x237–811/y187–836 and x816–1415/y216–836. FBO-packing has it around x237–1415/y182–836. In b74, the empty working content fills these edges without that extra closing frame. Intended table headers and horizontal header separators remain. The FBO-orders center split divider remains intentional; the rounded enclosing working-page card remains intact.

Read-only pixel checks support the visual comparison. Each b74 sample at the former left frame equals its immediately adjacent interior pixel; each historical f3 frame sample differs from its interior:

| Page/theme | Sample | f3 frame RGB | f3 inside RGB | b74 former-frame and inside RGB |
|---|---|---|---|---|
| Finance dark | (241,600) | (56,60,67) | (34,38,47) | (34,38,47) |
| Finance light | (241,600) | (244,245,247) | (245,248,252) | (245,248,252) |
| FBO-orders dark | (237,600) | (45,48,57) | (34,38,47) | (34,38,47) |
| FBO-orders light | (237,600) | (237,239,241) | (245,248,252) | (245,248,252) |
| FBO-packing dark | (237,600) | (45,49,57) | (34,38,47) | (34,38,47) |
| FBO-packing light | (237,600) | (237,239,241) | (245,248,252) | (245,248,252) |

The same cleanup is visible in GTIN-sync and product-edit empty internal results surfaces. Populated Ozon/FBS lists retain row boundaries, selected-row painting and header separators. No new enclosing square surface appears.

## Material, text and intended surfaces

Visible blue wallpaper and its tint/variation through working surfaces are consistent across all 32 final originals. Root-gutter pixel (215,450) independently reads RGB (27,40,63) in all 16 dark images and RGB (227,234,247) in all 16 light images. This corroborates visible composition at capture time rather than relying only on the parent's reported capture gates.

The selected store ComboBox around x232–452/y16–52 remains neutral and readable with a thin focus outline in both themes. Primary text, toolbar controls and enclosing rounded cards remain legible and contained. No unexplained failed blank page was observed; empty tables/history have appropriate empty-state text.

Designer preserves the intended white print sheet and caller-painted label elements. Its right canvas edge remains outside the current editor viewport near x941 with a visible horizontal scrollbar; that is a scroll viewport, not a new wrapper/clipping failure. Missing product thumbnails remain fixture data. Intended paper, caller-painted content, row/header separators and selected-row paint are preserved in the inspected images.

## Bounded existing observations and limits

FBO-orders' final filter pill is partly beyond the horizontal strip near x1375–1415/y140–167, with its scrollbar at y168. GTIN-sync's last table header is partly beyond the right table edge near x1395–1410/y283–295. Partial final order rows are bounded by scrolling viewports. These static captures do not test interactive reachability or scrolling.

The parent reports all final video/page capture gates passed; this review does not independently attest to the harness's pulse timing or per-node ownership. The conclusion is based on these actual final PNGs plus the stated read-only pixel comparisons. Evidence is native Linux JavaFX with synthetic fixtures. It does not establish Windows rendering, seller/API acceptance, every hidden dialog or all interactive workflows. Within the assigned static visual scope, b74 passes this subset review.
