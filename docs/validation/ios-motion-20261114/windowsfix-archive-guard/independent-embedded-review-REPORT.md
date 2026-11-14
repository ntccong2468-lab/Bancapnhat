# Independent static review — embedded data-control frame successor

Source b74b5edfd46636e8779c94f467eef01c03c50a14 differs from f3e98725ee2892abf225cb66978ed9302fd49489 in exactly the two paths pinned in receipt.json. Review used Git object reads only; no app/test/Maven, Git mutation, network or Windows execution.

The single added CSS line replaces the inherited two-layer edge gradient/insets with one existing glass-content tint at inset0, only for TableView/ListView/TreeTableView/TreeView beneath a workspace-sheet ancestor. The previously declared embedded border0/padding0/radius0 are unchanged; background insets are paint geometry, not layout padding. The standalone18/17 rounded layered rule is byte-unchanged. Caller inline/bound ownership logic, Java handlers, FXML, API, row/selection/hover states, paper content and all layout declarations remain unchanged.

The additive38-line test loads the real Finance and FBO FXML and their actual tables in both themes. It requires one shared ImagePattern, opacity1, the table focus owner and rendered RGB continuity between outer edge and nearby body pixels below the header and on left/right/bottom edges. Maximum channel difference must remain below.015; this measures the painted frame rather than asserting a CSS string. The old five test methods and all helpers remain byte-exact after removing the additive block. The new direct pixel coverage is two TableView routes; the other three control kinds share the same existing selector and retain their existing tests.

No concrete assertion weakening or unrelated change found. This static review does not certify the separate local/Windows test run, full app behavior, API actions or release readiness.
