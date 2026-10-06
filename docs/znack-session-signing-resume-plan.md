# Implementation plan: session-scoped Znack signing

1. Add failing tests for the session authorization and wait semantics.
2. Implement the in-memory signing gate and guarded provider.
3. Integrate pipeline context, current-session authorization, and scheduler pause/resume into `ZnackPurchaseCoordinator`.
4. Separate programmatic shop restoration from explicit dropdown selection and resume the explicitly selected shop on the background executor.
5. Run targeted and full verification, then review concurrency, data safety, and idempotency behavior.
