# Spec: macOS artifacts for WCode releases

## Objective

Every tagged WCode JavaFX release must publish runnable macOS packages alongside the existing Windows packages. Intel and Apple Silicon Macs receive native builds so JavaFX and SQLite native libraries match the target CPU.

## Tech Stack

- GitHub Actions pinned to the stable `macos-15-intel` and `macos-15` runner labels.
- Oracle JDK 25, Maven Wrapper, and `jpackage`.
- Existing JavaFX launcher: `com.tuandev.fbsbarcode.Launcher`.

## Commands

- Contract tests: `node --test tools/javafx-production-entrypoint.test.mjs tools/release-version.test.mjs tools/update-manifest.test.mjs`
- Java verification: `./mvnw -B clean verify`
- Local Mac package: `./build.sh dmg`

## Project Structure

- `.github/workflows/release.yml`: authoritative tagged release pipeline.
- `tools/javafx-production-entrypoint.test.mjs`: static release/package contract tests.
- `src/main`: JavaFX source and resources packaged by Maven.

## Code Style

Use an explicit matrix whose entries own the runner label, architecture name, and artifact name:

```yaml
matrix:
  include:
    - runner: macos-15-intel
      architecture: x64
    - runner: macos-15
      architecture: arm64
```

## Testing Strategy

- Contract tests must require both stable runner labels, `jpackage` DMG creation, portable ZIP creation, and both artifacts in the publish inventory.
- Windows and Apple Silicon execute the complete Maven/FXML suite. The virtual Intel runner executes every non-FXML test because in-process `Platform.startup` aborts in that runner environment; the same FXML suite remains covered on Windows and Apple Silicon.
- Each Mac runner verifies the `.app` launcher configuration and executable architecture, starts the packaged native launcher against an isolated data directory, and checks SQLite integrity before uploading artifacts.
- The publish job verifies non-empty files and checksums before creating or updating the GitHub release.

## Boundaries

- Always: keep the data-safe Windows 1.1.10+ upgrade UUID unchanged; never reuse the legacy 1.1.9 UUID; preserve optional signing behavior; package native dependencies on their matching Mac architecture; checksum every public artifact.
- Ask first: adding paid GitHub larger runners or Apple Developer credentials.
- Never: commit signing secrets, local databases, KIZ files, or publish a Mac build from a mismatched CPU architecture.

## Success Criteria

- Tagged releases build `WCode-macos-x64.dmg`, `WCode-macos-x64.zip`, `WCode-macos-arm64.dmg`, and `WCode-macos-arm64.zip`.
- A Mac failure blocks the publish job; a partially built release is never marked latest.
- Existing Windows 1.1.9-to-1.1.10 upgrade behavior remains intact; a signed update manifest is included when its signing keys are configured.
- Release notes state that the initial Mac packages are not Apple-notarized.

## Open Questions

- Apple code signing/notarization remains a follow-up because no Apple Developer release secrets are currently declared in the repository.
