# GTIN-centered Znack workflow

WCode uses a shop-scoped GTIN workflow for Znack and KIZ allocation. It does not upload KIZ PDFs or generate standalone KIZ PDFs.

Every Znack setting, certificate selection, GTIN, purchase order, downloaded code, circulation document, purchase pipeline, and operation log belongs to one WCode shop. Legacy unscoped Znack data is retained only as `znack_legacy_unscoped_*` audit archives when it cannot be assigned safely.

## Configuration

Basic Settings contains:

- required `omsId` and `omsConnection`;
- a CryptoPro certificate selected from the current user's `My` store;
- a real **Test signature** result;
- optional default goods-document number, issue date, and expiry date;
- optional automatic introduction into circulation.

There is no Advanced Settings section. Blank persisted API hosts resolve to the production services:

- True API: `https://markirovka.crpt.ru/api/v3/true-api`;
- SUZ: `https://suzgrid.crpt.ru`.

Authentication tokens, derived participant identity, signatures, PINs, and private-key material are never persisted.

## Mapping And Inventory

- The KIZ Mapping page lists registered Znack GTINs.
- Opening a WB supply automatically synchronizes the selected shop's GTIN list from Znack in the background, then refreshes the local GTIN inventory pane. A Znack sync failure is written to the shop audit log and does not block opening the WB supply.
- Each WB subject and gender pair belongs to at most one GTIN in a shop.
- A wildcard gender rule includes current, unspecified, and future genders for that subject.
- Downloaded codes enter the GTIN inventory as `AVAILABLE`.
- FBS and FBO reserve codes atomically as `RESERVED` and finalize them as `CONSUMED`.
- Legal Znack status is stored separately from inventory status.

## Purchase Pipeline

1. Validate the shop, GTIN, `omsId`, `omsConnection`, tested certificate, and an available CryptoPro signing backend (`cryptcp` or Windows CAdESCOM).
2. Sign and create the SUZ order.
3. Poll the persisted order until codes are ready.
4. Download codes idempotently into GTIN inventory.
5. When automatic introduction is enabled and required metadata exists, submit `LP_INTRODUCE_GOODS` and poll until every code is `INTRODUCED`.

Only safe read, poll, and download steps retry automatically. An ambiguous order-creation response remains in `CREATING_ORDER` to prevent duplicate charges. Pipelines resume after application restart and stay scoped to their original shop and GTIN.

If circulation documents or metadata are incomplete, downloaded codes remain available and the pipeline records a specific skipped-introduction stage.
When automatic introduction remains enabled, a later successful Znack synchronization automatically resumes skipped introductions whose document and GTIN metadata have become complete.

## API Notes

- Participant GTIN synchronization uses paginated True API v4 `/product/gtin`.
- SUZ order creation signs the exact JSON body and sends the dynamic token in `clientToken`.
- Code download uses `omsId`, `orderId`, `quantity`, and `gtin`.
- Introduction signs the unencoded document JSON with detached CMS/CAdES before Base64 transport.
- Final circulation is recorded only after the document is `CHECKED_OK` and every submitted code is confirmed as `INTRODUCED`.

API failures and operation logs are sanitized. Raw KIZ values are preserved for API submission and DataMatrix use, while user-facing values escape control characters.
