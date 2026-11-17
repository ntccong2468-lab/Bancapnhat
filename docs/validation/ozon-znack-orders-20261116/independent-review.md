# Final independent Ozon contract review — 16.11.2026

Bounded pass: no additional concrete blocker found in the reviewed and executed scope. This is not a statement that the entire app or every future remote state is bug-free. Reviewer made no changes to the target repository.

## Snapshot and preservation

Built an independent complete source snapshot of 3,750 tracked/nonignored files. Compared against the verified 3,740-file backup manifest taken before feature edits: no prior file deleted, all existing test files unchanged, and no WB-specific production file changed. Prior ECC files, AGENTS, existing Znack controller/FXML and UiLayoutProbe changes are byte-for-byte preserved from that backup. New shared classes use optional overloads; default/WB paths retain the prior counter and consent/budget logic. All original snapshot inputs stayed identical in the target during the independent run. Purchase-owner18 hashes and registration-owner6 hashes match their final receipts. See source-manifest.json and final-independent-receipt.json.

## Reviewed behavior

- Existing Ozon registration engine is reused through owner-aware dashboard/sidebar navigation. WB availability remains enabled. Opening the page does not allocate Catalog GTIN, send a feed, purchase or sign. Existing configured documents and frozen native identity continue through the same workflow. Registration owner's actual JavaFX100-case receipt was checked against source hashes; I did not relabel those as my own execution.
- Actual paid origin includes a non-imported owned SUZ external order, block or pipeline, including manual null-block downloads. MIXED permission alone does not reclassify unknown legacy stock; explicit legacy imported orders preserve imported policy even without a newer import entry. Purchased payload retains full internalGS/crypto; AI01 comparison extracts only evidence and never rewrites the string.
- Stock counter, initial strict allocator and pending wait use the same opt-in paid eligibility. Malformed rawGTIN, unknown origin, imported category, foreign owner, reserved or not-circulated rows cannot masquerade as new downloaded stock. Legacy held paid-intent replacement can consume legacy stock first, preserving downloaded stock for strict slots. Imported/category allocation remains GTIN-independent and uses its existing proof.
- Demand counts only exact definitively rejected nonterminal paid slots. Accepted siblings, unknown sends, active replacement tickets, historical printed jobs and source-conflicting split children do not authorize another fee. Both preflights subtract eligible available stock and carry the same provider into pending waits. Purchase identity/demand is rechecked before enqueue; consent, quota/spent accounting, original pipeline request and default WB behavior are unchanged.
- Normal receipt and PREPARED replacement now require current paid nativeSKU/offer/mapping/rawGTIN/owner/circulation evidence before new writes and again after validation I/O. Validation/set HTTP200 never counts as assignment success. Exact posting/product/exemplar/full code and every mark passed plus the existing global readiness gate are required. Missing/foreign/pending/failed-no-errors remain unresolved. No broad parser or ship_available relaxation was introduced.
- Paid terminal ACCEPTED/CONSUMED is checked before the active-ticket path. Historical local print without a remote receipt can only be read back, never reopened/resubmitted or given another candidate. New independent negative fixture proves this with PREPARED, SENDING and AWAITING tickets while exact remote readback is pending.
- Every actual dashboard combined-export route, including standalone, batch-prefetch cache, reprint and repair, reaches production verifyPurchased before compose. Paid durable acceptance proof binds owner/job/full bindings and current action fingerprint. A subsequent exact rejection/pending/unavailable invalidates it. Carrier-only output remains distinguishable and does not assert KIZ accepted. Public legacy local preparation methods remain for compatibility; rg found no separate app combined-output path bypassing the production hook.

## Independently executed

Offline cached Maven/JDK25 in a separate source tree, fresh compilation, SQLite temp profiles and localhost HTTP mocks, no dependencies installed and no real seller/Catalog/SUZ calls.

- OzonPurchasedPrintAssignmentTest: 18/18.
- OzonPurchasedKizReplacementTest: 13/13.
- OzonRejectedPurchasedDemandTest: 10/10.
- OzonPurchasedStockPolicyTest: 6/6.
- KizPurchaseAvailabilityPolicyTest: 7/7.
- External-only OzonIndependentTerminalTicketContractTest: 1/1, covering three ticket states. It exists only in the independent snapshot and is not a target edit.

Total55/55, zero failures/errors/skips. Initial non-executable Maven launcher attempt failed before compilation/tests; retained in launcher-permission-receipt.json, fixed by invoking the cached launcher through sh, not counted as test evidence. Main suite46.941seconds; external fixture25.761seconds including test compilation. Commands/logs/XML hashes are in the final receipt and neighboring artifacts.

## Limits and remaining work

No remaining concrete blocker in this bounded review. Parent's broader final regression, current-app run and final packaging decision remain separate gates. No real Catalog publication, paid SUZ circulation or Ozon seller acceptance was performed; cloud fixtures cannot establish those live outcomes. Windows/CryptoPro/native signing was not executed. Official Ozon docs previously had redirect-access limitations; cached contract/mirror provenance was described in contract-audit.md, and no fresh official-contract verification is claimed.
