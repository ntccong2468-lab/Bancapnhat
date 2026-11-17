---
name: vncode-ecc-review
description: Evidence-based VNCode change-impact and code review adapted from ECC for Codex Web. Use before cross-module changes and after implementation to trace callers, report concrete defects, preserve behavior and select downstream regression checks.
---

# VNCode ECC review

Read the repository's `AGENTS.md` and [review checklist](../../../docs/engineering/ecc/review.md). This is a Markdown workflow; it does not register a native agent, hook or command, or grant additional permissions. Follow current task authorization and project instructions.

1. Establish the actual repository, branch, base revision and working-tree changes. Preserve pre-existing edits. For a branch review, include committed changes since the selected base, staged/unstaged changes and relevant untracked files; a clean working tree is not proof of an empty review.
2. Trace affected callers, contracts, persistence and downstream consumers from source evidence. State unknown dependencies rather than guessing from folder names.
3. For a bug, use [Code Doctor](../code-doctor/SKILL.md) and the existing [debugging guide](../../../docs/engineering/debugging.md). Reproduce and diagnose before the smallest authorized fix.
4. Review shop/owner scope, business-state transitions, retry/idempotency, pagination, cancellation, resources, migrations and JavaFX thread ownership where relevant. Preserve WB/Ozon-specific contracts.
5. Report a finding only with an exact location, concrete trigger and bad outcome, surrounding caller/guard context and justified severity. Do not manufacture findings or treat file length as a defect.
6. Compare the final diff with the intended scope. Use [TDD and verification](../vncode-ecc-tdd-verification/SKILL.md) for meaningful regression checks and [security review](../vncode-ecc-security/SKILL.md) for sensitive boundaries.
7. State the checked scope, findings, executed evidence, unresolved risks and target checks not performed. A model review is not a build, real seller-API test or Windows acceptance result.

When supported tools permit, an independent reviewer can inspect the diff. Otherwise perform the checklist sequentially; do not claim an agent was registered by this file. Existing [OpenQodex guidance](../openqodex/SKILL.md) governs any actual scanner/reviewer use.

UI changes route to the existing Taste, Impeccable and UI UX Pro Max skills through the [integration guide](../../../docs/engineering/ecc/README.md), preserving the approved VNCode design. Durable facts go to existing project memory with provenance and sanitized evidence, not a second memory store.

Adapted from ECC at the pinned revision documented in the [source manifest](../../../docs/engineering/ecc/integration-manifest.json). See [upstream MIT notice](../../../docs/engineering/ecc/LICENSE.upstream.txt).
