# Native floating-glass and motion evidence

Runtime source: `43b82cca683b29e783a33df995afd675a76aa0dd`. Actual production JavaFX components run from a byte-verified copied runtime. Previous captures, including3ce0a0ab with its dynamic material-discovery defect, are retained as checkpoints and do not certify this successor.

## Recorded behavior

The original H.264 video is1440×900,44.133333 seconds; Java exit0, ffmpeg exit0. Recording30fps is not an application FPS benchmark. All trace gates: `True`. Exact checks and hashes are in capture-receipt.json.

Three real report components—GS1, registration with Search, readonly error—are opened in both themes. All six report windows use their production TRANSPARENT StageStyle and transparent Scene fill, the same DialogPane/root/callbacks, and the new app-owned titlebar. The native fixture never substitutes a StageStyle or Scene fill. Trace records titlebar dragging, titlebar Close, real pointer resizing, sparse/full GS1 tables and rounded control clips. A representative16-control native diagnostic dialog is distinct from an application business page.

The main video exercises normal navigation, selected rows/tabs, management popup, detail drawer, reports, theme changes, Settings, and Reduced Motion. The16-control diagnostic temporarily uses Reduced Motion to provide stable native resize geometry, then restores motion before Settings.

## Page coverage and material readiness

The static sweep uses actual Robot desktop capture of25 major routes in both themes. Each new page must first have its working material targets ready in the current theme WITHOUT toggling themes; this is recorded in mount-material-observations.json. Before each of50 screenshots, every eligible visible logical surface must have a real960×600 ImagePattern, with unchanged caller-owned backgrounds excluded explicitly. The material state must remain stable for five real post-Scene CSS/layout/synchronization pulses, with actual Robot wallpaper pixels matching the source ImagePattern sample (maximum channel delta3). This waits for native/compositor completion rather than assuming a pre-CSS AnimationTimer callback has painted. No theme, background, snapshot or layout mutation is used to satisfy the acceptance barrier. Per-node IDs, classes, paint origin, image dimensions/identity/anchors, tint alpha and region opacity are recorded in page-material-observations.json. Global Pattern counts alone cannot pass.

Manual review is complete: the page team inspected50/50 originals (32primary+18delegated), all25routes in both themes; the capture reviewer separately inspected15/15 video keyframes, including all six report windows. No concrete material, enclosing-wrapper, layout or legibility blocker was found in this bounded set. Details and exceptions are in inspection.json and visual-audit.md. Screenshot count does not imply every hidden report/dialog, popup or control state was visited. Source factory inventory is lexical coverage only.

## Material boundaries and provenance

The material engine uses four cached generated images at most: original and blurred app wallpaper for each theme. ImagePattern fills sit behind the existing alpha tint; text and live content are not blurred. The wallpaper contains no OS desktop or seller pixels. Representative panels, fields, tables and buttons expose real material fills; selected/disabled/semantic states and caller-owned paint can intentionally remain opaque.

Settings has a transparent layout root and separate glass sections. Table/List controls retain caller clips where owned; sparse/full and empty tables use rounded clips. Print paper and signature/document content retain their intended colors.

No MainApplication, license activation, marketplace/Catalog operation, signing or purchase action is run. Synthetic SQLite data and rejecting license fixtures are used; measured licenseCalls=0. This is not a packet-level audit of all network traffic, Windows graphics-driver verification or seller/API acceptance. Business navigation callbacks use fixture routing; production views, shared motion/material and dialog implementations are exercised.

## Evidence

The gallery contains untouched original video frames, native page PNGs, and selected unchanged b00d577a checkpoint comparisons. Scene/client-frame changes are real production changes, not image retouching. Frozen-runtime-provenance.json pins actual used video classes/resources/dependencies; static-replay-provenance.json pins the successor static helper and byte-verifies the SAME copied runtime with no overlay and runtime-manifest.json pins the copied runtime. Maven validation is reported separately by the parent for the final source pin.

Video SHA256: `bd5243d0eed098fdb1d95be3168b62dc7eae8acad5be322c88e0ae3bab3a1b02`.

## Final receipts

All50 public page PNG hashes equal the corrected native-pixel capture receipt AND the manual review hash list. All25 dynamic mounts passed before any theme toggle; all50 captures passed every eligible working-surface Pattern check and five post-Scene/native-pixel frames. Actual wallpaper sample channel delta is0 for all50 captures. Static capture uses the same byte-reverified frozen runtime as the unchanged video, with its own helper/provenance; no class overlay was used. The earlier pre-paint static attempts remain preserved outside this final gallery.

The parent focused verification for this source is PASS:197tests,20suites,0failures/errors/skips, actual process exit0. See focused-verification.json. Full Windows/releaseCI and seller/API acceptance are separate.
