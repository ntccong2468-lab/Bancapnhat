# WB stale supply cleanup and KIZ export specification

## Scope

This change fixes stale Wildberries supplies in the FBS packing board, prevents KIZ that are not legally in circulation from entering any print or marketplace workflow, lets the user archive unusable KIZ for a GTIN, and exports standalone KIZ labels as a 58 x 40 mm PDF.

## Wildberries supply reconciliation

- A supply returned by the WB list or detail endpoints remains stored locally, including a real empty supply.
- A successful `404 Not Found` response for a specific locally-open supply is authoritative evidence that WB no longer has it.
- On that response WCode deletes the local `wb_supplies` row for the same shop and supply ID. Existing foreign-key cascades remove its local links.
- Authentication, rate-limit, timeout, server, and parsing failures never delete local data.
- Both automatic overview sync and the manual WB sync verify locally-open supplies, prioritizing old zero-item rows.
- The next board refresh therefore removes overnight empty supplies that WB already removed.

## KIZ availability invariant

- A KIZ is visible as available and can be reserved only when both conditions are true:
  - inventory status is `AVAILABLE`; and
  - legal status is `IN_CIRCULATION`.
- The invariant applies to WB FBS, WB FBO, Ozon FBS, Ozon FBO, readiness counts, the Supply Details GTIN cards, and the GTIN mapping page.
- Downloaded, emitted, introduction-pending, and introduction-failed codes stay in the database for recovery/audit but are not counted or allocated.
- Existing reserved or consumed rows keep their current state; this change does not silently release or reuse them.

## Archive unusable KIZ

- Each GTIN item in Supply Details and the GTIN page has a delete icon with a localized tooltip.
- The action is enabled only when the GTIN has codes from an explicitly failed introduction pipeline: `AVAILABLE` codes whose legal status is absent or is not `IN_CIRCULATION` and whose pipeline stage is `INTRODUCTION_FAILED`.
- Codes waiting for readiness, missing documents or metadata, and codes retained for manual circulation remain recoverable and cannot be archived by this action.
- The user must confirm the action.
- Deletion is recoverability-oriented: qualifying rows are changed to `ARCHIVED`; raw KIZ values and audit relationships are retained, and successfully circulated, reserved, or consumed codes are never changed.
- The operation is shop-scoped and GTIN-scoped and records a sanitized operation-log entry.

## Standalone KIZ PDF export

- Each GTIN item has an export icon. The action is enabled when at least one legally available KIZ exists.
- WCode asks for a positive quantity no greater than the displayed/rechecked available count, then asks for the target PDF path and filename.
- Codes are rechecked and reserved transactionally after the user chooses the target, preventing concurrent workflows from selecting the same rows.
- PDF layout is exactly 58 x 40 mm, one KIZ per page:
  - a printer-sharp GS1 DataMatrix occupies the left half at the largest safe square size;
  - the right half shows product name, gender, and size, with wrapping and bounded text.
- Product name comes from the synchronized Znack GTIN. Gender comes from the GTIN mapping rules. Size is resolved from synchronized WB product sizes matching those rules; multiple distinct values are compacted rather than guessed.
- The PDF is generated to a staging file. The reserved rows are consumed before the staging file is atomically published, matching the existing order-export safety rule that favors preventing duplicate KIZ use.
- If generation fails before consumption, all reservations are released. If publishing fails after consumption, the codes remain consumed to prevent duplicate circulation labels.
- After successful publication WCode opens the PDF. Failure to open it does not invalidate the saved PDF or release the consumed codes.

## Acceptance tests

- A WB `404` for supply detail or order-count sync removes only that shop's local supply; a non-404 failure keeps it.
- Inventory counts and reservations exclude `RECEIVED`, `PRINTED`, `INTRO_SENT`, null, and archived codes.
- Ozon's direct reservation query also excludes every non-`IN_CIRCULATION` code.
- Archiving changes only unusable `AVAILABLE` rows for the selected shop/GTIN.
- Export rejects invalid/excess quantity, emits the requested number of 58 x 40 mm pages, contains DataMatrix graphics plus the three metadata fields, consumes the exported reservations, and releases them when PDF generation fails.
