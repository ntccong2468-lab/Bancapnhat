# Shared circulation completion — implemented

Code Doctor / ECC review and security workflow used through repository SKILL.md. Existing ECC/UI/Ozon feature changes preserved. No dependency, schema, WB adapter or Ozon acceptance-rule change.

## Confirmed defect and fix

Normal newly purchased batches have autoIntroduction=true and used unowned CIS inspection. Exact CIS/GTIN/status INTRODUCED with a foreign, missing or malformed owner could therefore set codes IN_CIRCULATION and orders/pipeline INTRODUCED. The same problem existed after CHECKED_OK document polling. Both WB and Ozon share these services. The first public release v1.1.33 also lacked owner verification; copying the old module would retain this defect.

Two production files changed: ZnackIntroductionReadinessService now requires owned exact readback before returning allIntroduced, on the same response already fetched. APPLIED/card-readiness behavior is unchanged. ZnackIntroductionService.confirm uses inspectOwned and a valid original expected owner before terminal marking. Expected owner comes from per-purchase Settings.ownerInn, falling back to original authenticated participant/certificate; never the remote CIS or current shop profile. Full code/GS/crypto remains unchanged. The legacy ZnackCisReadback.inspect helper is byte-identical.

Parent separately fixed one Coordinator pending branch to retain ConfirmResult.message. Without that line, the new owner failure remained safely pending but its report lost the explanation.

## Executed evidence

- Initial meaningful RED: new30cases,22assertionfailures/0errors/0skip. Both WB and Ozon falsely completed auto-introduction and CHECKED_OK polling for unverified owners.
- After two service fixes, report-reason RED:30cases,10assertionfailures/0errors/0skip; only pending diagnostic was still empty. Parent's Coordinator line preserves that reason.
- Existing7suite regression before completing fixtures:265cases,20assertionfailures/0errors. Failures were exactly three existing fixtures that had no valid expected owner/healthy owner response. Their assertions and invalid response cases were retained.
- Final GREEN: **295/295 in8suites**,0failure/error/skip, actual Maven exit0. Includes all new30cases, existing Module76/Gtin53/Identity29/Signing26/Circulation27/Cis28/Owned26. Owner paths cover foreign/missing/numeric/mixed/conflicting outer owner, matching owner, missing expected owner, participant/certificate fallback, no fresh API read/POST/purchase/signing and full downloaded payload preservation.

Commands, XML hashes, logs and before/after source hashes are in green-command.json, green-result.json and green-junit. All seven feature-related source/test inputs in that run remained identical to the current target after completion.

## Existing fixture adjustments

Only ZnackIntroductionIdentityTest, ZnackGtinWorkflowTest and ZnackModuleTest were extended. They now provide legitimate synthetic original owner and healthy INTRODUCED ownerInn. The negative parent/child-status shape case also gets a valid expected owner so it cannot pass merely because owner configuration is missing. All assertion call kinds/order remain unchanged (37,239,386 respectively); no assertion, expected outcome or parameterized case removed. fixture-adjustments.json records the exact before/after hashes and reasoning. SigningRecovery and all low-level legacy inspect fixtures remain unchanged.

## Limits

Tests use isolated SQLite and synthetic responses with no real Catalog/SUZ/Ozon writes, paid purchase, digital signing, license activation or release. This is a concrete reproducible completion defect; not a claim that it alone explains a seller's unsupplied live failure. Parent's final whole-change review/build remains separate.
