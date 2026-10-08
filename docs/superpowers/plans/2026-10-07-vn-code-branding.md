# VN code Implementation Plan

**Superseded:** use [independent application plan](2026-10-08-vn-code-independent-app.md). The user requested installation beside WCode with empty independent data.

> **For agentic workers:** Use superpowers:executing-plans for inline implementation. User has authorized implementation and GitHub publication.

**Goal:** Rename the existing Windows application to VN code without losing existing data.

**Architecture:** Centralize display identity in BuildConfig, move Java/FXML namespaces together, and keep durable legacy identifiers. Default paths reuse existing legacy directories; fresh installs use the new name.

**Tech Stack:** Java 25, JavaFX, SQLite, Maven, jpackage, GitHub Actions.

**Spec:** docs/superpowers/specs/2026-10-07-vn-code-branding.md

## Global Constraints

- Display name VN code; version 1.1.34; Java namespace com.vncode.app.
- Preserve data, license and remote service compatibility; no production seller writes.
- Preserve historical upstream URLs, provenance, license attribution and published releases.

## Review Focus

- Legacy database and license directories remain selected, including an empty directory held by another process.
- Canonical properties override aliases; test data stays isolated.
- Old and new processes use the same lock and recovery markers.
- Spaces in the Windows launcher name are quoted throughout packaging and update relaunch.
- Update asset renaming preserves signed-manifest validation and real upstream URLs.

### Task 1: Brand and compatibility

**Files:** pom.xml, src/main and src/test package/resource trees, AppPaths.java, BuildConfig.java, build.bat, build.sh, tools, .github/workflows, current documentation.

**Interfaces:** BuildConfig.getAppName(): String; AppPaths.selectDataDirectory(Path base, boolean windows, boolean testProfile): Path (package-private); canonical vncode.appdata.dir and vncode.data.profile with legacy aliases.

- [x] Add property-precedence and branding assertions; run targeted Maven tests and observe failure.
- [x] Implement identity, namespace move, compatible directory selection and installer/resource renaming.
- [x] Add legacy-directory regression cases; run full Maven verify and Node contracts, expecting no failures or skips.
- [x] Review the complete change, fix consequential findings, and commit.

### Task 2: Windows delivery

**Files:** docs/releases/VN-code-1.1.34.md; native smoke/publisher tooling and workflows.

**Interfaces:** CI artifact VN-code-1.1.34-Windows-x64; native-smoke.json includes appName VN code, migration and history evidence.

- [ ] Push an isolated feature branch, dispatch the native Windows build, inspect actual test and launcher results.
- [ ] Create a tag and prepared draft tied to the verified source commit, then publish verified artifacts through the existing runner publisher.
- [ ] Check public release metadata, installer digest and download availability.

## Implementation evidence

2026-10-07: canonical-property/branding assertions RED (3 failures), targeted GREEN14; full Maven561 Java/FXML tests, Node21, zero failures/errors/skips. Linux packaged native window VN code v1.1.34, schema4 integrity/FK clean. Independent review found and verified fixes for the actual test-update repository guard and bounded window-title readiness. Durable compatibility identifiers, upstream service endpoints and source attribution are deliberately retained. Windows CI/publishing pending.

Ownership follow-up: a new regression reproduced default-directory switching when a legacy folder appears after startup (RED1). Cache the selected default root for each base/platform/profile, preserving explicit override behavior. Full revalidation and native Windows rebuild required for this final source.

Follow-up verification: full Maven562/562, Node21/21, zero failures/errors/skips. Regression selectedDefaultDirectoryCannotChangeWhileItsOwnershipLockIsHeld is GREEN; the previous Windows run was intentionally cancelled before native validation, and the final commit requires its own new build.
