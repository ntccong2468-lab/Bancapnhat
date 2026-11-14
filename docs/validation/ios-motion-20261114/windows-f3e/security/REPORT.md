# Fresh security comparison — VN Code 1.6.10

Security run [43639388384](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/43639388384) analyzed exact source `f3e98725ee2892abf225cb66978ed9302fd49489`. It completed with **failure**: Java's unchanged security gate blocks one high finding. JavaScript and lint/audit jobs passed. This is a comparison of actual downloaded analysis output, not a security waiver or an application functional test.

## Actual artifact verification

| Artifact | ID | ZIP bytes | Verified ZIP SHA-256 / API digest |
| --- | --- | ---: | --- |
| codeql-java-kotlin | 14131508814 | 172592 | `449bbdbf3d9e53d30df441a7a310c85706734b26fde9add856c83bf6d5667da2` |
| codeql-javascript-typescript | 14130623864 | 133412 | `8bf34499da086c8672c94234992fb8f93b30f9617e5ea56af1da22ef85159149` |

Both ZIPs were downloaded from their artifact IDs, matched the API digest and size, passed complete ZIP CRC checks, and contained exactly one expected SARIF file. Before/after API metadata matched for artifact identity, name, digest, size, expiry, timestamps and the complete workflow-run object, including the exact source commit. See `artifact-verification.json` and `stable-download-metadata.json`.

Raw SARIF hashes:

- Java: `e114e50e33f96e364787e9c201ec3273d7022bfe5b00db95d0c30b8f2435f3f7`.
- JavaScript: `72d558ce8357ddbd4ce5f906c651a26b3480c9ba90a73c9dda5eacc935f13be2`.

## Findings and gate result

The candidate has **8 Java findings: 0 new, 0 resolved**, compared independently with both:

- 1.6.9 source `4ef0f1e2b8ef7b28190de3034b0c67ebf615be0b`.
- Earlier 1.6.10 source `43b82cca683b29e783a33df995afd675a76aa0dd`.

All eight complete raw SARIF result records match as multisets. The normalized records also match, including rule and severity, messages, every primary/related physical and logical location, full regions/snippets, both fingerprint maps, stacks, and all **7 code flows / 7 thread flows / 80 thread-flow locations**. Reference locations, line numbers, array order and code flows were preserved; no finding was dropped or deduplicated.

JavaScript has **0 findings**, unchanged against both references.

The unchanged gate was replayed on the actual fresh files:

| Language | Findings | Blocked | CLI exit | Result |
| --- | ---: | ---: | ---: | --- |
| Java | 8 | 1 | 1 | FAIL |
| JavaScript | 0 | 0 | 0 | PASS |

The blocked finding remains `java/potentially-weak-cryptographic-algorithm`, security severity **7.5**. The other severity counts are 6.5×3, 5.4×2 and 5×2. No claim that this finding is fixed follows from the absence of new findings.

## Extraction coverage and notification comparison

The complete warning/error notification multiset is identical against both references: one Java extraction error, `java/diagnostics/extraction-errors`, for `src/main/java/com/vncode/app/features/gs1/Gs1SignatureInk.java`. Analysis of that file remains incomplete. JavaScript has no warning/error notifications.

**Not every informational notification is identical.** The additional all-level comparison retains these exact differences:

| Reference | Language | Informational records added | Removed |
| --- | --- | ---: | ---: |
| 1.6.9 | Java | 55 | 2 |
| 1.6.9 | JavaScript | 51 | 1 |
| 43b | Java | 10 | 3 |
| 43b | JavaScript | 10 | 2 |

All changes above have level `none` or `note`, covering expected/successfully extracted file lists, overlay/cache diagnostics and the Java extractor summary. They are recorded in `all-level-notification-summary.json` and `all-level-notification-differences.json`; they were not counted as new or resolved security findings. The unchanged extraction error was not filtered out.

## Reproducibility and unchanged policy

The reviewed comparator is `compare_sarif.py`, SHA-256 `780add1aa965d1d10beb925448d004577193aefa13574817072ed4399e5a9b44`. It resolves SARIF rule/index/URI references and preserves record multiplicity; only text/markdown whitespace and object key ordering are normalized. A separate complete raw-result comparison includes fields outside that historical normalized projection.

- `normalized-records.json`: `9f4c3d05e532ff0f65c624a8277c406f67679a99a02e02bdd95918904ccc6f57`.
- `comparison-details.json` versus 1.6.9: `46b070ef58f508e9fa3f2e8c25a6f183573d513bbc884dfc5450ed56e5870a3f`.
- `comparison-vs-43b/comparison-details.json`: `da198373c5532ec1b02dcb0938d87ce807e4ca220041497304b8bea8bc03c98d`.
- Canonical Java raw-result multiset: `08771b607fdb7cd7a93b23fa18bcedc5469a671b2abae55ef0c296ce132fe8d6`.

The security workflow and gate are byte-identical across all three sources:

- `.github/workflows/security-ci.yml`: `0d1f3df39ed3306f2551da7d3ba3a402ea1ad275f26a2660fbbe5a400c60f0f0`.
- `tools/codeql-sarif-gate.mjs`: `a5722170627f60c57e30464d7af348ef90acbb944c92ec05c65c16ea36efb0bb`.

No policy change, waiver, suppression or commit was made during this review. `sanitized-receipt.json` is the concise handoff; raw SARIF/ZIPs remain separate from this report. The full Windows run 43639385681 was still in its expanded preflight at the last read-only status snapshot; this security comparison does not certify its 41 suites or its later unfiltered verification.
