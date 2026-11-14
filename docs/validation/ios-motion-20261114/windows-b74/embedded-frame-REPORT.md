# Embedded workspace tables: redundant square frame removal

Frozen baseline: `f3e98725ee2892abf225cb66978ed9302fd49489`.

The embedded Table/List/Tree/TreeTable rule already zeros border width and corner radii. It inherited the standalone two-fill background at insets 0 and 1, leaving an exposed gradient at the outer pixel. Native Finance and FBO screenshots showed a separate rectangular outline inside the rounded workspace sheet. The correction sets only the embedded background to `-glass-content` and its inset to 0. Header divider, focus, selection, geometry, standalone controls and caller clip ownership remain unchanged.

The additive regression loads the actual production Finance and FBO FXML in both themes, focuses the actual table, waits for its shared ImagePattern, and compares body pixels along its left/right/bottom edges. It also requires opaque Nodes, preserved wallpaper material and unchanged focus ownership. No shop is set, no handlers are invoked and no seller API is called.

RED with unmodified baseline CSS: 5 tests pass; the new test fails with 10 distinct edge pixel assertions across both themes. GREEN with exactly the approved two CSS declarations: all 6 tests pass. All five pre-existing methods and assertions were verified byte-for-byte unchanged after removing the additive method and record.

Actual native PNGs in `red/` and `green/` were visually compared for Finance light and FBO dark. The new paint removes the extra closed rectangular outline while keeping the table header bottom rule and rounded owning sheet. These are Linux JavaFX native pixel tests, not a substitute for the parent's Windows integration/release checks.

Direct `javac` plus a temporary JUnit runner and copied immutable baseline runtime were used. No Maven, repository commit, target writes, seller/API writes or business changes. Source is frozen at the hashes in `receipt.json`.
