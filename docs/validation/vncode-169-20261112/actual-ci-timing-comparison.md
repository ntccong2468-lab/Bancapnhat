# Actual full Windows timing comparison — 1.6.8 and 1.6.9

Read-only evidence collected on 12 November 2026 UTC. The 1.6.9 acceptance is the successful **attempt 2** of [run 43131000128](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/43131000128), source `4ef0f1e2b8ef7b28190de3034b0c67ebf615be0b`. The old baseline is successful [run 42828436446](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/42828436446), source `38906a2108501029d9609ed1d071ee987c8b2637`.

| Evidence | 1.6.8 | 1.6.9 attempt 2 |
|---|---:|---:|
| Full Java observed wall time | about 88m31s | 118m55s |
| Sum of suite elapsed times | 5,258.872s | 7,072.586s |
| Suite time / observed wall time | 99.016% | 99.125% |
| Test cases | 4,254 | 4,464 |
| Passed | 4,252 | 4,462 |
| Failures / errors | 0 / 0 | 0 / 0 |
| OS skips | 2 | 2 |
| Test classes/suites | 554 | 565 |

The successful 1.6.9 full Java step ran from **03:38:41 to 05:37:36 UTC** on 12 November. It spent nearly all of the observed interval within measured test suites. This is consistent with a long test run, rather than an unmeasured Maven dependency-download delay outside tests. It does not identify the reason each test took longer.

| Slow suite (seconds) | 1.6.8 | 1.6.9 |
|---|---:|---:|
| `GtinBulkPipelineTest` | 2,480.000 | 3,404.738 |
| `RegistrationLargeBatchDurabilityTest` | 270.800 | 363.207 |
| `ZnackRegistrationBatchServiceTest` | 169.600 | 213.859 |
| `OzonLargeShopReservationTest` | 141.500 | 183.272 |
| `OzonCardRegistrationWorkflowTest` | 111.300 | 155.570 |

In the old 1.6.8 log, `GtinBulkPipelineTest` produces a 2,480.311-second silent output gap that matches its successful suite elapsed time. All 291 Maven dependency transfers in that old stage have matching completion lines; their merged transfer intervals total only 19.955 seconds, outside its test span. Its first/last test classes are `BuildConfigTest` and `WindowsUpgradeDataProbeTest`. The new suite's slowest class alone reports 3,404.738 seconds. Long silence by itself is therefore not a reliable assertion-failure signal.

**Keep the first attempt failure:** attempt 1 ended at 03:30:05 UTC with the GitHub annotation “The hosted runner lost communication with the server.” Its ZIP was empty and it produced no artifacts. That was an infrastructure/communication failure, with no available Java assertion result; it is not acceptance evidence. Only attempt 2 supplied the full passing test, native installer, data-preservation, and upgrade evidence. No gates were weakened between attempts.

Precision/scope: old suite times come from Maven console class summaries (some rounded); new suite times come from archived JUnit XML. Old wall time uses the command banner to `BUILD SUCCESS`; new wall time uses official job-step timestamps. The comparison explains the delay but is not a controlled performance benchmark. Synthetic HTTP/SQLite/large-batch tests are not real seller purchases, Catalog registrations or Ozon acceptance.

Sources: `windows-jobs-final.json`, `windows-actual-junit-summary.json`, `package-review.json`, the completed 1.6.8 official run-log ZIP and its parsed timing report. No current source, Git, CI run or publication changes were made for this comparison.
