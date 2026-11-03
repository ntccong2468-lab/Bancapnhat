# Ozon FBS and desktop improvements (review branch)

This change is based on the OpenQodex integration branch and is intended for review, not production deployment.

- Ozon acceptance and printing preserve the selection and report individual blocked postings. Label tasks use Ozon's batch API and persist task membership; ambiguous PDF-to-posting identity is rejected instead of attaching a KIZ to the wrong order.
- Ozon picking lists distinguish a posting number from the actual tracking number. Tracking is scoped to the seller credential and missing or stale values are identified.
- KIZ mapping proofs and reservation/publish checks reject uncertain or conflicting product identities. Cross-marketplace-only proofs are intentionally blocked for purchase and print until they can be bound atomically to the entire workflow. Direct mapping is revalidated before the Ozon request, but a race remains between this check and the remote HTTP call.
- The desktop shell and core lists have light and dark treatments. The actual Overview route includes a WB penalty view with a separate per-owner, per-date checkpoint and respects API throttling. Ozon penalties remain unsupported until authoritative transaction taxonomy and deduplication are established.

Validation uses mock APIs and temporary databases. A read-only Ozon FBS list succeeded for the configured test account, but no real order acceptance, label purchase, KIZ purchase, or production data modification was performed. Local timing fixtures omit images, KIZ and real network delays; they do not predict Windows or production performance. Requests can still fail because of marketplace state, identity, permissions, KIZ availability or service limits, so successful printing of every posting cannot be guaranteed.

Finance tables are additive and scoped by seller credentials. They have not been applied to production data. Older finance results with unverified ownership are not attributed to a new key. Changes require further review and representative Windows/end-to-end tests before release.
