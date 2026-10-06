# Znack KIZ recovery and label quality

## Problem

Persisted KIZ purchase pipelines can remain in `WAITING_INTRODUCTION_READINESS` after the
codes become ready when WCode fails to read the permit attached to the exact GTIN. The documented
National Catalog `/v3/feed-product` response exposes permits through `good_attrs`, while the
catalog UI payload exposes the same attributes through `businessLayer.attrGroup[].attributes[]`.
Treating an unparsed UI-shaped card as "no document" incorrectly parks valid purchases.

The authoritative circulation check is `/v4/rd-info-by-gtin`. Per National Catalog API v5.62 it
returns `result.documents[]`; `23557` is a declaration, `23561` a conformity certificate and
`23765` a state-registration certificate. Only permit status group `1` is valid for primary
introduction. The optional request field `inn` is the goods-card owner's INN, so WCode must not
silently substitute the authenticated participant when no owner override was configured.

The standalone 58 x 40 mm KIZ export label also derives gender/size from WB mappings instead of
the National Catalog GTIN card and does not include the Chestny ZNAK logo.

## Required behaviour

1. Never mark or expose a KIZ as usable/exportable until True API confirms
   `IN_CIRCULATION`.
2. Keep already purchased codes and their pipeline; do not buy replacement codes during
   recovery.
3. If the permit registry confirms that the exact GTIN has no active supported permit document,
   park the pipeline in a durable document-waiting state and do not poll/sign repeatedly.
4. Read permit references from both the documented `good_attrs` contract and the compatible
   catalog UI `businessLayer` contract. For the latter, read the displayed document number and
   date from `showValue.number` and `showValue.dateFrom`; never use its internal numeric `value`
   as a document number.
5. After a GTIN sync obtains a complete permit reference, restore the GTIN from the automatic
   no-document trash state and resume the parked pipeline with its existing codes. Before signing,
   verify the permit through `/v4/rd-info-by-gtin` and continue only with status group `1`.
6. Reject a new KIZ purchase before any remote mutation when the selected GTIN has no complete
   supported permit reference in the latest local National Catalog snapshot. This local check is
   not itself proof that the permit is active.
7. Existing `EMITTED` codes remain in readiness polling because they can become `APPLIED`
   without a catalog change.
8. Persist gender and size read from either National Catalog attribute representation. Prefer
   attribute 14013 (`Целевой пол`) for gender and attribute 35 (`Размер одежды / изделия`),
   with compatible size-name/footwear fallbacks.
9. Export one vector DataMatrix per 58 x 40 mm page, increase its physical size, use crisp vector
   text, include `chestniy-znak.png`, and show raw gender/size values without the literal labels
   “Gender/Giới tính” or “Size”.

## Compatibility and safety

- Schema changes must use additive nullable columns so existing `database.db` files open without
  data loss.
- Legacy `CONSUMED + RECEIVED` rows are preserved for audit; the migration must not assert a
  legal state that True API has not confirmed.
- A missing permit document is a durable legal wait, not a terminal purchase failure.
- Network or registry failures remain retryable and are not treated as proof that documents are
  absent.

## Acceptance checks

- A missing-document submission transitions once to the parked state and no background poll is
  scheduled.
- A parked pipeline is excluded from normal startup resume, but resumes after its product has a
  complete permit document.
- A purchase for a document-less GTIN fails without adding a pipeline/order.
- `EMITTED` readiness continues to schedule polling.
- Old schemas gain `gender` and `size` columns and retain existing rows.
- Product-card parsing accepts both `good_attrs` and `businessLayer`, extracts the conformity
  certificate from the supplied real payload, and never persists an internal catalog value as
  its certificate number.
- The registry request omits `inn` when the card owner is not explicitly configured, and the
  introduction payload still defaults `owner_inn` to the authenticated participant.
- Product-card parsing stores gender/size from either schema and export metadata prefers those
  values over WB mapping metadata.
- Rendered labels decode at print resolution, contain the logo, and have no Size/Gender prefixes.
