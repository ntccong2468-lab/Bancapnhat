# Independent VN code Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline. Implementation, Windows packaging and GitHub publication are authorized; do not merge shared branches.

**Goal:** Install VN code beside ứng dụng tham chiếu with empty independent data and safe uninstall.

**Architecture:** Use new installer upgrade identities, fixed own data/cache roots and own update channels. Verify the MSI embedded in the produced EXE on a disposable hosted Windows runner against a checksum-pinned original ứng dụng tham chiếu installation.

**Tech Stack:** Java 25/JavaFX, Maven, jpackage/WiX, PowerShell, GitHub Actions.

**Spec:** docs/superpowers/specs/2026-10-08-vn-code-independent-app.md

## Global Constraints

- Exact display VN code; version 1.1.34; separate VNcodeApp/VNcodeData; new upgrade UUIDs.
- Empty initial shop/license/data; never copy ứng dụng tham chiếu data or consume ứng dụng tham chiếu JVM properties.
- No production seller mutation or license bypass; preserve genuine upstream provenance.

## Review Focus

- WCodeData, ứng dụng tham chiếu, ứng dụng tham chiếu test data and old update backups already exist.
- A ứng dụng tham chiếu process holds its own data lock while VN code starts.
- Ambient ứng dụng tham chiếu.appdata.dir/data.profile cannot redirect VN code.
- The production MSI has no test data override and retains the new upgrade identity.
- Installing/uninstalling VN code leaves ứng dụng tham chiếu bytes and registration intact; native probes refuse real user machines and pre-existing directories.

### Task 1: Independent roots and installer identity

**Files:** AppPaths.java/test, build.bat, update client/channel workflow, current documentation.
**Interfaces:** selectDataDirectory and selectSystemDirectory always select VNcode roots; legacyAppDataDirs returns empty; only vncode properties are consumed.

- [x] Replace reuse assertions with independent-root, shared-process-lock and old-property rejection cases; observe RED8/12.
- [x] Implement independent paths/properties, fresh installer UUIDs and own update channel; targeted GREEN15.
- [x] Independent roots full Maven verify passed563 and Node22 before personal-edition scope; rerun final suite after that change.

### Task 2: Native installation proof and delivery

**Files:** windows-smoke-common.ps1, windows-side-by-side-smoke.ps1, windows-native-smoke.ps1, WindowsDataProbe.java, publisher and workflows.
**Interfaces:** Wait-VncodeWindow reads process-owned visible titles; side-by-side-smoke.json proves actual MSI identity, empty data and ứng dụng tham chiếu-preserving uninstall; publisher rejects missing/failed proof.

- [x] Add publication proof contract; observe missing validator/workflow RED; implement and obtain Node22 GREEN.
- [x] PowerShell/YAML parse passed; independent review found no P1/P2 application defect. Delivered MSI path narrowed after contract RED/GREEN to exclude WiX intermediates.
- [x] Run native Windows CI on exact source commit; inspect Java/Node, migration and installation/uninstall proofs.
- [x] Publish a new prerelease from the verified tag and verify public installer checksums.

### Task 3: Free personal edition

- Remove the original vendor subscription/reporting runtime and activation UI; no fake license state or original binary changes.
- Observe failing UI tests for the free edition status and confirming a verified fixture operation without activation, then implement.
- Preserve external authentication, signing, seller confirmation and unverified-production-contract guards.
- Keep error diagnostics available to copy locally. Update all four UI languages and release documentation.
- Run full verification and review before the final native Windows build and release.

Personal edition RED18: fixture confirmation stayed blocked and free sidebar status absent; GREEN18 after removal. Node subscription dependency contract RED, then all23 GREEN. Removed14 obsolete vendor licensing tests; final expected Java/FXML count551.

Final local verification: Maven551 Java/FXML tests, Node23 contracts, no failures/errors/skips; packaged JAR contains no original licensing/report clients or server configuration. Windows native CI remains required.

Native run37842327089 passed551 Java/FXML,23 Node, EXE packaging and native history/window proof. Coexistence stopped before installation because the upstream1.1.9 URL returned404. Replaced the retired fixture with the genuine1.1.75 EXE supplied by the user (SHA509e29...), extracting only its MSI resource with resource-only Win32 loading; embedded MSI SHA5f109c..., ProductVersion1.1.75, all-users ProgramFiles/WCodeApp verified by local read-only PE/MSI inspection. No original bytecode/installer modification. Repeat native CI before release.

Native run37843779293 again passed app suites, packaging and native history/window proof. Read-only MSI diagnostic37844872466 isolated the metadata defect: COM Execute/Close emit null placeholders into PowerShell pipeline, returning Object[] [null, property, null]. Suppress those method outputs explicitly; keep strict scalar/name/version checks and verify read-only inspection before repeating the full Windows probe.

## Completed delivery

- Verified application/tag commit: `bfa5a689a0329b9f770f30aeefa62f071ba9d86c`.
- [Native Windows CI](https://github.com/ntccong2468-lab/Vncode/actions/runs/37845191448):551 Java/FXML and23 Node contracts, zero failures/errors/skips; native version window, schema/history/snapshot proof; real ứng dụng kiểm thử tham chiếu installation followed by VN code install/fresh own data/uninstall, preserving ứng dụng tham chiếu executable/data/registration.
- [Publisher](https://github.com/ntccong2468-lab/Vncode/actions/runs/37846803832): exact verified tag/artifact, all6 assets uploaded and digest-checked, prerelease published.
- [VN code1.1.34 Windows prerelease](https://github.com/ntccong2468-lab/Vncode/releases/tag/v1.1.34).
- EXE140996096 bytes, SHA-256 `d3970ee5ca88f72a810bbedb807c84cdb7201b96c0d2ef132d0a7a01cea6ac79`; public download HTTP200 and all published checksums/build metadata independently verified.
- Independent free personal app, own shop setup and empty data; no original license/report server dependency. Genuine upstream attribution and external authentication/signing/write-contract gates preserved.
- Unsigned Windows prerelease, manual installation. No exact ứng dụng kiểm thử tham chiếu source integration and no live seller/GS1/CryptoPro/printer acceptance; production GTIN ADD/REPLACE remains disabled.
