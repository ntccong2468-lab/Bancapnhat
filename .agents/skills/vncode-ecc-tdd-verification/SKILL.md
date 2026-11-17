---
name: vncode-ecc-tdd-verification
description: Risk-based VNCode regression, build and acceptance verification adapted from ECC for Codex Web. Use when fixing behavior or verifying a change to record meaningful RED/GREEN evidence, affected consumers, actual exit codes and untested Windows or live-API boundaries.
---

# VNCode ECC TDD and verification

Read `AGENTS.md`, [testing](../../../docs/engineering/testing.md), [Code Doctor](../code-doctor/SKILL.md) for defects and the [verification checklist](../../../docs/engineering/ecc/verification.md). Select commands from the actual project and available runtime, not generic upstream examples.

1. Record the base revision, working-tree scope, expected behavior and affected callers/consumers. Distinguish prior failures from regressions caused by the change.
2. For a meaningful behavioral defect, write or select a focused regression, execute it before the fix and inspect why it fails. A compile/environment error is not evidence that the intended behavior was reproduced. Do not add tests that merely mirror trivial implementation details or low-impact reversible edits.
3. Make the smallest authorized fix, execute the regression again and record the result. Preserve existing assertions, protections and unrelated behavior. Do not impose a new blanket coverage target, mandatory stage commits or a different framework.
4. Run relevant downstream checks based on real call/data dependencies. Broaden testing only for changed behavior, failures or unresolved concerns. Do not replace runtime evidence with a model's review verdict.
5. Check build/static analysis only where applicable and available. Capture the real process exit code and complete result artifacts; output trimming must not hide a failing command. A missing tool or permission is BLOCKED/NOT RUN, not PASS.
6. Review the diff, including untracked additions, and run appropriate whitespace/structural checks. Keep existing CI/security findings and extraction limits visible.
7. Report command, revision plus working-tree scope, environment, exit code, actual counts/skips and evidence path. Separate synthetic unit/integration checks, rendered UI, Windows/native/installer tests and real marketplace/Catalog acceptance.

For UI evidence, use the existing UI skills linked in the [integration guide](../../../docs/engineering/ecc/README.md). Source inspection cannot pass rendered keyboard/focus, long-content, motion or viewport checks. If a required runtime is absent, state the next concrete check and access dependency.

Final statuses: PASS, FAIL, NOT RUN or BLOCKED for each local check; VERIFIED, FAILED, NOT PERFORMED or NOT APPLICABLE for target acceptance. Do not claim all functions are error-free or that mock HTTP success proves a business transaction completed.

See pinned [provenance](../../../docs/engineering/ecc/integration-manifest.json) and [upstream MIT notice](../../../docs/engineering/ecc/LICENSE.upstream.txt).
