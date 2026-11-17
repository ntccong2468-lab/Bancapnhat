# Unknown KIZ download and replay — test handoff

Own target edit: new `ZnackAmbiguousCodeDownloadTest.java` only. No production source, existing test, dependency, schema or configuration edited by this agent.

## Diagnosis reproduced

Ordinary SUZ `/codes` is a consuming operation. Before the fix, after a lost IO/5xx/408 outcome or restart, production Coordinator/CodeService performed another ordinary delivery instead of reobtaining already issued packages. An empty/unavailable package index could not safely authorize another delivery. A simulated abrupt termination also demonstrated the missing pre-call durable receipt. Both WB/Ozon use the same production path.

Clean meaningful RED: **24 cases,16 assertion failures,0errors/skips** on original CodeService03b42ae457c01c406af3d65a96d20ab69ef4b991d59ee653dc95085da45cb3c0. Eight auth-before-request/definite4xx negative controls passed. The earlier initialrun had six additional null-vs-empty oracle failures; they were corrected and are not feature RED evidence. Logs retained separately.

After owner added WAL, a second prevalidation RED on frozen CodeService6e7be759550586e87801bd0042c8cfd1f860fcaed4530e0ddb011eb0cf1deacd reproduced **12/12 assertion failures,0errors/skips**: duplicate logical CIS with conflicting crypto; a replay overflowing original quantity while consumed stock was present; boolean/numeric code entries or object-CIS values being treated as opaque codes. Tests require validation of the complete replay before any insert and preserve consumed/legal statuses plus full GS/crypto bytes.

## Final execution

**GREEN36/36,0failure/error/skip, actual exit0** after overlaying only the owner's final CodeServiceSHA5a421a3edf2865a5fab9f939243a41eab80b298759c585672e5420da5bf8a42f. Target/copy testSHA85058eaa72711a6aa6d501889f94da7d2d71a0eed4c81cb6601281ec7975c939. All other frozen snapshot files unchanged. The36 already include both preceding RED sets; do not add them to the final count again.

Commands, exactprocessresults, JUnitXML hashes and archivedXML are in `red-final-*`, `replay-red-*` and `green-*`; finalsummary `green-result.json`. All Maven calls use the sharedflock, cachedJDK25/Maven, offline resolution, Xvfb and isolatedappdata. Current production interface/signature has no test-only bypass.

No realSUZ/Ozon/WB request, fee, signing, licenseactivation, Windows/native run, commit/push or release. These are synthetic SQLite/fakeAPI protocol regression tests, not commercial acceptance. Parent owns the full final branch regression and actual JAR verification.
