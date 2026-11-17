# Signing recovery fixture update

Only `ZnackSigningRecoveryTest.java` changed. No production edit, assertion removal, test skip or remote API call.

The root broader snapshot preserved the initial 26-case run with seven assertion failures (`root-initial-failure.xml`). Three expected exactly one order status read but did not implement the new available-code adapter, causing the coordinator to refresh twice. Four intended to corrupt or delete the original signing profile but changed only the legacy `znack_pipeline_connections` row; the new canonical `znack_purchase_contexts` still contained the valid original profile. Correctly using that retained original is not a permission bypass or product failure.

Fixture corrections:

- The missing-profile case deletes both shop-scoped snapshots and asserts both rows were removed. It keeps the exact ORIGINAL_PROFILE_MISSING, unchanged pipeline/codes, no-signature and no-consent assertions.
- Invalid certificate, verification and OMS cases now update both stored profile representations. The canonical typed context receives a coherent identity hash; signedness is still rejected before granting consent. All original assertions remain unchanged.
- Synthetic order availability and capped-download seams are explicit, so accepted-order recovery uses one refresh while still retrieving only from the same paid order.
- A new `advanceBatch` trap increments the existing buy counter and throws on any attempted new batch creation. The old direct-buy trap remains. This strengthens no-rebuy coverage under the replacement engine.

Final focused execution: **26/26 PASS, 0 failure/error/skip**; exact command `command.json`, process `result.json`, Maven output `verification.log`, XML aggregate `test-summary.json`. File SHA256 **225c266393a6c9697528f8494edb61c0f4ca2502823e60fefac03c2e1d143d0c** (`source-hash.json`). All pause/cancel, no automatic retry, same owner/profile, full raw CIS, consumed-stock, no-document and no-duplicate-purchase assertions remain active. This is synthetic JVM verification and not seller CryptoPro/SUZ acceptance.
