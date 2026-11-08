# Independent review — KIZ, Znack registration and WB GTIN

Reviewed the uncommitted patch against `4cea038f5abd2c437460ba661125a99b7f59214b` on `fix/kiz-znack-wb-gtin-audit-20261108`. Source hashes and the exact 21 reviewed files are recorded in `independent-review-source-sha256.json`. This is an independent read-only code review using the repository Code Doctor workflow, AGENTS, security, testing and current memory instructions.

## Findings

No additional material, confirmed patch-specific correctness or security finding remains in the reviewed snapshot. This conclusion is limited to the changed business paths and their surrounding callers; it is not a claim that the application is bug-free or that a production transaction was accepted.

## Business paths checked

- Paid KIZ validation now reads known rich permit evidence at both print proof and coordinator prerequisite boundaries. The public identity resolver is unchanged. Existing stock reservation/printing does not invoke the new paid-only guard; accepted unpublished registration identities retain their existing validation. `advance` still resolves an existing paid/ambiguous purchase before new-order prerequisites.
- Catalog projection filters invalid documents and clears flattened permit/certificate fields only after an explicit document/status/count observation. The rich Catalog cache remains the source of expiry/status diagnostics. Absent observations preserve saved legacy documents. Mixed active and expired permits retain the active permit.
- WB mapping now requires the same nonempty normalized native article, plus existing owner, age, publication, trade-unit, full colour, size and duplicate constraints. A variant without a usable GTIN produces an issue. Ambiguity counts only fully eligible candidates; unrelated or expired cards cannot create a false ambiguity.
- Explicit WB review refreshes marketplace data before matching when a paired Catalog receipt is already ready. An expired or absent paired receipt follows its existing paired refresh. Ozon refresh and matching behavior are unchanged by this branch.
- WB cached-barcode completion is display reconciliation. It updates only an unsubmitted `znack_marketplace` report for the current WB owner, with original native keys/GTIN/state/timestamp and `child_job=''` checked in the same transaction as current native barcode presence. Existing child journals and terminal rows are excluded. Previous candidate Catalog/source/feed/publication/approval metadata is removed. No Catalog mapping, registration pointer or paid proof is created from `046` presence.
- An explicitly foreign `feed_id` is rejected before progress, rejection or card fallback can affect the saved request. Matching IDs and legacy responses with no explicit ID retain their prior readback behavior.
- Exact final GTIN reasons now use their existing diagnostic translations. The shared `GTIN_ON_ANOTHER_PRODUCT` advice no longer directs a WB seller to Ozon; the underlying conflict is retained rather than relabeled as success.

## Verification and limits

`git diff --check`: PASS during this review. The seven changed/new regression test files were inspected for meaningful behavior and preservation of existing assertions; no test was disabled. I ran no Maven, JUnit, API request, purchase, signing, license, release or commit. Root owns the serial Maven/build results and final acceptance evidence; the other agents' focused test counts are not independently rerun or added together here.

The root's fresh API/parser summaries show 1,854 WB variants with an existing `046` barcode and 128 without, with no observed cross-variant shared GTIN or ambiguous union in those credential datasets. Those reads were not bound to the installed seller/certificate or local registration journals. They cannot identify or clear the screenshot's 110 unmatched and 18 action-required rows. HTTP 200 and display completion do not authorize a paid purchase or prove Catalog/SUZ/marketplace transaction completion. Windows, OMS/CryptoPro and real seller journals remain outside this review.

Only this report and its source-hash manifest were written outside Git. No application or test source was edited by the reviewer.
