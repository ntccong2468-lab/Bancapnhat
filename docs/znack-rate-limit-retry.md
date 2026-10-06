# Spec and plan: Znack HTTP 429 recovery

## Objective

Prevent Znack National Catalog synchronization from leaving GTIN names and TN VED metadata blank when the API rate limit is reached. Safe GET requests retry automatically, while chargeable or state-changing requests remain single-attempt.

## Source contract

The bundled National Catalog API documentation states that:

- request limits are scoped both globally per organization and per method;
- a limit window can last up to five minutes from the first request in the series;
- usage is exposed through `API-Usage-Limit` and `API-Method-Usage-Limit` response headers;
- HTTP 429 includes `Retry-After` as seconds until access is restored;
- `feed-product` accepts at most 25 GTINs per request.

Source: `znack_api/ZnackAPIDocument_md/api-v5.62-05.06.2026-at-13-03-26.md`, sections 2.1, 2.5 and 3.1.1.

## Stack and structure

- Java 25
- OkHttp 4.12.0
- JUnit Jupiter 5.12.1
- Client: `src/main/java/com/tuandev/fbsbarcode/integration/znack/ZnackApiClient.java`
- Catalog sync: `src/main/java/com/tuandev/fbsbarcode/integration/znack/ZnackProductService.java`
- Tests: `src/test/java/com/tuandev/fbsbarcode/integration/znack/`

## Behavior

1. Retry HTTP 429 only when the HTTP method is idempotent (`GET` or `HEAD`).
2. Prefer a valid non-negative `Retry-After` value in seconds.
3. When `Retry-After` is absent or malformed, use bounded exponential fallback delays.
4. Limit attempts and total retry delay to prevent an unbounded background task. The total delay must not exceed the documented five-minute limit window.
5. Preserve thread interruption and stop retrying immediately when interrupted.
6. Never apply the 429 retry policy to order creation, document submission, or other state-changing requests.
7. If catalog enrichment still exhausts retries, preserve successful batches but record the overall GTIN sync as `WARN`/partial rather than `INFO`/complete.
8. Keep the documented 25-GTIN catalog batch size.

## Code style

Follow the existing Java style and keep retry policy inside the Znack API boundary:

```java
if (response.code() == 429 && isIdempotent(request) && attempt < MAX_ATTEMPTS) {
    sleeper.sleep(retryDelay(response, attempt));
    continue;
}
```

## Testing strategy

- Use a localhost HTTP server; no external Znack calls.
- Prove that an idempotent request retries after 429 and succeeds.
- Prove `Retry-After` is interpreted as seconds without real sleeping.
- Prove malformed/missing headers use bounded fallback.
- Prove retry exhaustion returns the final 429 error.
- Prove a POST that receives 429 is attempted only once.
- Prove a catalog sync with an exhausted batch is logged as partial and preserves successful metadata.

## Commands

- Targeted JavaFX integration test: `./mvnw -Dtest=ZnackModuleTest test`
- Full JavaFX CI verification: `./mvnw -B verify`
- JavaFX production/package contract: `node --test tools/javafx-production-entrypoint.test.mjs`

## Boundaries

- Always: sanitize API error bodies in logs, preserve interruption, keep retries bounded.
- Ask first: schema changes, dependencies, retrying state-changing requests.
- Never: log tokens, raw KIZ values, or retry order creation automatically.

## Implementation tasks

1. Add failing localhost integration tests for 429 retry, delay parsing, exhaustion and POST safety.
2. Add the smallest testable retry policy to `ZnackApiClient` and make the tests pass.
3. Add a failing catalog-sync test for partial enrichment, then record partial sync truthfully while preserving successful batches.
4. Run targeted verification, full Maven CI verification and the JavaFX package contract.

## Success criteria

- A transient 429 on `feed-product` recovers automatically using `Retry-After`.
- Repeated 429 responses terminate predictably within the attempt/time budget.
- State-changing calls are never retried by this policy.
- Partial catalog enrichment is visible in persisted operation logs.
- Targeted and full WCode verification pass.

## Open questions

None. The requested implementation and bundled API contract define the behavior above.
