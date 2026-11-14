# Fresh b74 security comparison — VN Code 1.6.10

Security run [43641523097](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/43641523097) completed on exact source `b74b5edfd46636e8779c94f467eef01c03c50a14`. Its conclusion is **FAILURE** because Java's existing high-severity gate still blocks one finding. JavaScript and lint/audit jobs passed. This report does not claim an overall security pass.

## Verified fresh artifacts

| Artifact | ID | ZIP bytes | ZIP SHA-256 matched to API digest |
| --- | --- | ---: | --- |
| codeql-java-kotlin | 14132023560 | 171464 | `0b09d0e1e35cf664d1bbf0fe4e2e014b75ba9452f55687b96877231b92319ed1` |
| codeql-javascript-typescript | 14132317600 | 133414 | `65fdbaf3838d41db95ea44cb057d41cd81e8eb295ee2e7b3889338da6fe48329` |

Each ZIP matched both API digest and size, passed full CRC verification and contained one expected SARIF file. Before/after API reads matched identity, name, digest, size, expiry, timestamps and the complete workflow-run object, including run 43641523097 and the exact b74 source. See `artifact-verification.json` and the preserved metadata reads.

Raw SARIF SHA-256:

- Java: `78eae046bb179a29b3ad8815083fff9b02acab8999c3b1ebdee5a6b1d29bd4b4`.
- JavaScript: `5e6762394962c290c06ec6b0ebd983088aa890995f7cb5512b21f93183d97d7e`.

## Finding equivalence and the raw index differences

The reference-resolved finding multisets match independently against all three references:

| Reference source | Java findings | New | Resolved | JavaScript findings |
| --- | ---: | ---: | ---: | ---: |
| 1.6.9 — `4ef0f1e2b8ef7b28190de3034b0c67ebf615be0b` | 8 | 0 | 0 | 0 |
| f3 — `f3e98725ee2892abf225cb66978ed9302fd49489` | 8 | 0 | 0 | 0 |
| 43b — `43b82cca683b29e783a33df995afd675a76aa0dd` | 8 | 0 | 0 | 0 |

**The raw serialized Java result records DIFFER.** Compared with each reference, there are 107 changed leaf values:99 `artifactLocation.index` references and8 `rule.index` references. These are SARIF table addresses, not changed source locations or finding identities.

`raw-result-index-resolution-proof.json` records every full JSON path, before/after index and the resolved artifact-location object or rule ID on each side. All107 references resolve equally. Referenced full rule descriptors also match. After resolving these references, **all complete raw result fields are equivalent**; no other field differs. The proof retains URI/base identity, full regions and line numbers, snippets, message arguments, fingerprint maps, stacks, array order and every code-flow field. No source location or finding is discarded. The complete 8-result reference-resolved multiset hash is `f5ef51b0271864220963c329c0b31d3ff26339e87db765ac16a96626b2f91b71`.

The historical normalized comparator independently confirms equality of all 8 findings, including all 7 code flows / 7 thread flows / 80 thread-flow locations. The original byte-identical raw-record result from f3 versus older baselines remains historical evidence; it is not asserted for b74.

## Unchanged failing gate and extraction limitation

The unchanged repository gate was replayed against the fresh SARIF:

| Language | Findings | Blocked | CLI exit | Result |
| --- | ---: | ---: | ---: | --- |
| Java | 8 | 1 | 1 | FAIL |
| JavaScript | 0 | 0 | 0 | PASS |

The existing blocker is `java/potentially-weak-cryptographic-algorithm`, severity 7.5. Other finding severities remain 6.5×3, 5.4×2 and 5×2. No finding was fixed by this UI correction.

Warning/error notification multisets match all references exactly: Java still reports one `java/diagnostics/extraction-errors` error for `src/main/java/com/vncode/app/features/gs1/Gs1SignatureInk.java`. Analysis of that file remains incomplete. JavaScript has no warning/error notifications.

## Informational notifications are not identical

All differences below have level `none` or `note`; their complete records remain in `all-level-notification-differences.json`.

| Reference | Language | Informational records added | Removed |
| --- | --- | ---: | ---: |
| 1.6.9 | Java | 55 | 2 |
| 1.6.9 | JavaScript | 51 | 1 |
| f3 | Java | 3 | 3 |
| f3 | JavaScript | 2 | 2 |
| 43b | Java | 10 | 3 |
| 43b | JavaScript | 10 | 2 |

These changes concern expected/successfully extracted file lists, tool-cache cleanup, overlay diagnostics and the extractor summary. They were not relabeled as fixed/new security findings. The extraction error above was preserved separately.

## Reproducible comparison and unchanged policy

The reviewed comparator `compare_sarif.py` has SHA-256 `780add1aa965d1d10beb925448d004577193aefa13574817072ed4399e5a9b44`. It preserves multiplicity and resolves rule/artifact/logical/thread-flow references. Text/markdown whitespace and object-key order are normalized; line movement, array order and symbolic URI bases are retained. The supplemental whole-result comparison validates all raw fields beyond that historical projection.

Security policy is byte-identical across 169, 43b, f3 and b74:

- `.github/workflows/security-ci.yml`: `0d1f3df39ed3306f2551da7d3ba3a402ea1ad275f26a2660fbbe5a400c60f0f0`.
- `tools/codeql-sarif-gate.mjs`: `a5722170627f60c57e30464d7af348ef90acbb944c92ec05c65c16ea36efb0bb`.

No waiver, suppression, source/gate edit or commit was made. `sanitized-receipt.json`, `security-comparison-summary.json`, the three comparison-details files, the complete index-resolution proof and `gate-cli-exits.json` preserve the actual results. Raw SARIF and ZIPs remain separate from this sanitized report. This analysis does not certify completion of the Windows build or marketplace transactions.
