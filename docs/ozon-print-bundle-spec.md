# Spec: Ozon FBS print bundle

## Objective

Add the JavaFX Ozon print flow that mirrors the useful parts of the existing WB workflow without reusing WB mutations. A single print action must idempotently prepare Ozon exemplars, download the official Ozon shipping document, append one physical KIZ label for every accepted exemplar, and create a separate A4 picking list.

For the common posting with one product and quantity one, the label bundle contains three pages: the two official Ozon pages followed by one 58 x 40 mm KIZ page. For larger postings the page count is `official Ozon pages + accepted exemplars`; it is not fixed at three pages per posting.

Printing does not ship, cancel, change stock, or change prices.

## Tech Stack

- Java 25
- JavaFX 25.0.2
- iText 8.0.3 for preserving/composing PDF pages and generating GS1 DataMatrix with FNC1
- ZXing 3.5.4 for independent DataMatrix decode verification
- JUnit 5.12.1 and PDFBox 3.0.6 for verification

## Commands

- Focused tests: `./mvnw -Dtest=OzonPrintBundleServiceTest test`
- JavaFX/Ozon regression: `./mvnw -Dtest='Ozon*Test,FxmlSmokeTest' test`
- Maven verification: `./mvnw test`
- Production packaging contract: `node --test tools/javafx-production-entrypoint.test.mjs`

## Project Structure

- `src/main/java/.../integration/ozon/` - Ozon prepare, label download and print bundle services
- `src/main/java/.../ui/ozon/` - JavaFX print action
- `src/main/resources/.../i18n/` - localized print action/status text
- `src/test/java/.../integration/ozon/` - PDF composition and fail-closed tests
- `output/pdf/` - manually verified final PDF artifacts

## Code Style

Keep the orchestration explicit and fail closed:

```java
OzonExemplarJob job = jobs.find(shop.getId(), postingNumber);
if (markingIsRequested(posting) && !accepted(job)) {
    OzonPreparationResult result = exemplars.prepare(shop, postingNumber);
    requireAcceptedOrNotRequired(result);
}
```

The official Ozon pages are copied without scaling. Raw KIZ values are used only while rendering DataMatrix and are never logged, shown in the UI, or written to evidence.

The picking PDF is intentionally minimal: it contains only a product table with columns `#`, `Image`, `Product`, `SKU`, `Offer ID`, and `Qty`. It contains no title/metadata block, no KIZ column, no KIZ explanatory note, and no posting footer. The product image comes from the synchronized Ozon catalog and is embedded in the PDF; an unavailable or invalid image renders a stable placeholder instead of aborting the whole print job.

The physical KIZ page has no border, brand/shop heading, or Ozon status text. Its GS1 DataMatrix is drawn as PDF vector modules with a one-module quiet zone so it stays sharp at printer resolution; it must never be stretched from a low-resolution bitmap.

## Testing Strategy

- Unit/integration test that a two-page official PDF plus one accepted exemplar produces three pages.
- Decode the generated DataMatrix, require GS1 symbology identifier `]d2`, and compare it with the persisted KIZ inside the test process.
- Verify multiple exemplars append one KIZ page each in stable item/exemplar order.
- Verify an accepted durable job does not invoke preparation again.
- Verify a non-accepted marking job blocks output and does not publish partial files.
- Verify the separate A4 picking list contains an embedded product image, product name, SKU, offer ID and quantity.
- Verify the picking list omits the title/metadata block, KIZ column, KIZ explanatory note and posting/order/shop/timestamp text.
- Verify the KIZ page omits every brand/shop heading, `OZON - KIZ` and `KIZ accepted by Ozon`, and has no drawn outer border.
- Verify the GS1 DataMatrix is vector content rather than an image XObject and remains independently decodable from a 300 DPI page render.
- Keep the existing Ozon label and exemplar tests green.

## Boundaries

- Always: prepare before printing a required/mapped KIZ; print only `ACCEPTED` exemplars; publish final files atomically; preserve official Ozon page sizes.
- Ask first: changing schema, adding dependencies, or changing the ship confirmation workflow.
- Never: send a second blind exemplar mutation, expose raw KIZ, synthesize an Ozon shipping barcode, resize official Ozon pages, or automatically ship from the print action.

## Success Criteria

- One-item/quantity-one accepted posting exports two official pages plus one 58 x 40 mm KIZ page.
- Each additional accepted exemplar adds exactly one KIZ page.
- A separate A4 picking PDF is exported for the posting.
- The picking PDF starts with the product table, embeds catalog images to the left of product names, and contains no KIZ or order-metadata prose.
- Every KIZ page is borderless and contains no brand/shop heading or Ozon status copy.
- The DataMatrix remains sharp and decodable when rendered at 300 DPI.
- Reprinting an accepted posting reuses the same KIZ and label job.
- A posting whose KIZ is not accepted produces no final print bundle.
- JavaFX presents one print action and reports both output files.

## Open Questions

None for the JavaFX MVP.
