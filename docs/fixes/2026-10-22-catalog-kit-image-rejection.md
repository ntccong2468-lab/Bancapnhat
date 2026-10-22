# Catalog rejection: sports suits and inaccessible WB photos

The seller's VNCode 1.5.10 report identifies five rejected variants of WB nmID 686600110. Catalog explicitly rejects `is_kit` for TN VED 6112110000 and cannot fetch the WB basket CDN image. The two unrelated rows in the processing filter do not prove any of these five cards exists.

## Changes

- Default preparation no longer infers a Catalog kit from sports-suit names for 6112110000 (or the existing 6112120000 exception). Other TN VED codes retain the established behavior; a fresh explicit server rejection can correct packaging.
- Omit optional WB basket CDN thumbnails from new feeds. Keep marketplace images in local product views. Repair preparation also avoids reintroducing an explicitly rejected image URL.
- The durable WB submission path now corrects a server-proven terminal rejection involving only packaging/optional-image errors, sends the corrected request using the same GTIN, then reads the exact card. This handles immediate and delayed rejections; unknown requests are never replayed.
- Old terminal batches with these errors can reopen once through the background monitor. Saved error text schedules readback only; a fresh authoritative rejection is required before any corrective POST. Original receipts/payloads stay archived. Other errors remain actionable.
- TN VED comes from the current marketplace product, not from document coverage. A four-digit Catalog category fallback retains the exact ten-digit product code in attribute 13933. Reject unrelated transport codes and stale Ozon product codes. Documents continue to be checked against the product's code, without substituting a document group.
- Persist actual preparation, lookup, allocation and dispatch stages instead of displaying “sending” during all earlier work.

## Evidence and limits

The Catalog API was reachable (HTTP 200; 1,279 listed cards). A read-only WB search for nmID 686600110 with the configured environment token returned no matching card. The seller's five GTIN/feed IDs are not present in the supplied screenshot. No production registration or marketplace mutation was performed; therefore this change does not claim production acceptance of the seller's five cards. No installer, version tag, or Latest release was changed.

246 backend regression tests passed (zero failures/errors), including the unchanged anti-replay/read-only reconciliation tests. Coverage includes immediate combined kit/image correction, delayed rejection, recovery of a terminal batch saved by the previous build, no new GTIN allocation, and preservation of the source product code. HTTP/UI validation is recorded separately when complete.

## Published candidate versus local queue

The seller also supplied a `111 pume` row: no local request/GTIN/feed, a published Catalog candidate, and a variant mismatch. Current rejection filtering already excludes such an unmatched candidate. Add a dedicated matching-review filter to both marketplaces, expose the observed candidate GTIN in the row report, and label the local queue as “no creation request sent by this app” rather than implying the remote card does not exist. Candidate matching remains blocked until article/color/size evidence agrees; no automatic rebinding or duplicate creation is introduced.


## Unresponsive view and incomplete sync follow-up

- Route local Znack table reads to the existing reserved local-read lane. Two blocked general tasks must not prevent the table from opening. Keep the loaded table/filter/selection when reopening the same shop; changed credentials still reset the view.
- Yield safe registration preparation/lookup/readback work during API cooldown rather than keeping the worker asleep through the whole retry window. Unknown mutation outcomes remain protected from replay.
- Both explicit WB entry points (Products and RegistrationSources) now scan until an authoritative empty page, probing beyond short pages. Cursor contradictions/repeated cursors still fail without claiming completion.
- Ozon continues after short pages when the reported total says more products remain; an empty page with missing products is an incomplete read.
- Read-only API evidence before the environment restart: configured WB token returned 388 distinct listings and 1,982 variants across pages 100/100/100/88. It did not return the screenshot's nmID 686600110; do not present that as acceptance of the seller's specific card or confirmation of the same shop.

The GUI test run interrupted by the execution environment restart lost its X display. It is not counted as passing; restart Xvfb and rerun the selected tests on the final source.


## Final targeted validation

384 tests pass across the latest reports for 19 targeted classes, counting repeats once. This is not a full-app or production acceptance claim. The final controller rerun passes all 30 cases; all other targeted reports are green. The real HTTP transport fixture corrects three combined kit/photo rejections, reads back three exact cards, retains GTINs, and sends no repeated request after completion. Matching-review isolation, queue starvation, short-page continuation, source TN VED, and no replay after uncertain responses are covered. See `2026-10-22-catalog-kit-image-tests.json`.
