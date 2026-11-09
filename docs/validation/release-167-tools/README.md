# VN Code1.6.7 — archived session validation tools

This directory preserves the exact non-secret source of three tools used during the1.6.7 release session. It is documentation, **not application/runtime code, a portable validation package, or a passing release receipt**. At capture, full Windows/native/package verification and publication were still pending for app source **`d0231cd6913ce3a1764067bad0901b32c535de41`**, run **`42611294387`**, workflow392732536, draft438471378. The frozen application source ZIP has2993 files; these later documentation files do not change that pinned tree.

The scripts retain their historical defaults under `/workspace/vncode-167-release` and `/workspace/vncode-znack-reconcile`. Their original comments include a pre-guard example filename; the executable constants and the commands below identify the **guard** tools. Do not run them automatically on another repository/source/run, edit their pins silently, or treat a static/source-only check as installer acceptance. Prepare and review a separate tool revision if the source or run changes.

## Exact source copies

| File | SHA-256 |
| --- | --- |
| [verify_package-certificate-guard.py](verify_package-certificate-guard.py) | `1af43751eabd4020911ee1951665d05dd4f873bd4005e50c37608ca728bc219f` |
| [verify_public_preservation-certificate-guard.py](verify_public_preservation-certificate-guard.py) | `5cabcecb0f0f93b253ff0140558eaf61b4999af79f00f427636c8dad01a1125f` |
| [Installed166UpdateProbe.java](Installed166UpdateProbe.java) | `d7c0d48c0d4417db06ed8277c58d629e01428786f2dd7fe7bd1a633a836dd39b` |

Every source copy is byte-identical to its outside-session original. [SHA256SUMS](SHA256SUMS) covers archived inputs; [archive-manifest.json](archive-manifest.json) records sizes, hashes, origin paths and exact-copy/derived status. The archive operation inspected static source/metadata, checked hashes and syntax, and copied files. It did **not** execute these tools, run Maven or installers, call APIs, activate a license, read a token/private certificate, or publish anything.

## Small historical inputs retained

These source proofs are required by the package verifier's fixed `WORK` dependencies. They remain historical source/build metadata, not evidence that the final guard build passed.

| Archived metadata | SHA-256 / meaning |
| --- | --- |
| [source-inclusion-fifth-attempt.json](metadata/source-inclusion-fifth-attempt.json) | `63b37971dd05c8cb4ab6ba877b0a34a9d9cdc94eda25d39309123006625402e2`; source0f5/run42587222212, before certificate changes |
| [source-inclusion-certificate-first-attempt.json](metadata/source-inclusion-certificate-first-attempt.json) | `f8b0994e43ed625e5fb051f1b14551bfb4928b7f932fac9fb5e97c9d04d65d71`; sourcee774/run42605121356, failed preflight |
| [source-inclusion-certificate-pre-guard-attempt.json](metadata/source-inclusion-certificate-pre-guard-attempt.json) | `ef28606a62e94c8b3e7c334887562a11e9c994db52565c507bbc92cdd7c1e680`; source2a5/run42607033320, cancelled before full/package acceptance |
| [installed166-extraction.json](metadata/installed166-extraction.json) | Actual published1.6.6 portable hash/size, application JAR hash and75-JAR count; **no binaries** archived |
| [updater-before.json](metadata/updater-before.json) | Actual1.6.6 binary returned `CURRENT` before Publish; this does not prove `AVAILABLE1.6.7`, anonymous network access, or a seller popup |
| [tags-before-final.json](metadata/tags-before-final.json) | Exact public45-tag snapshot before `refs/tags/v1.6.7` existed |

Already tracked proof is referenced instead of duplicated:

- Current inclusion: [2026-11-08-vncode-167-source-inclusion.json](../2026-11-08-vncode-167-source-inclusion.json), frozen at publication commit `b1e5e7774e1ab01d0c1c57ee934900c6927638f7`, SHA **`10f1384af2fd2b5b7b53fd50d38ff46e78546f7095099cc5e1b7a0000026c88f`**. Restore as `WORK/source-inclusion.json`.
- Failed first certificate preflight: [2026-11-09-vncode-167-certificate-first-windows-failure.json](../2026-11-09-vncode-167-certificate-first-windows-failure.json), SHA **`688fe5ac068dfb8a87ca7d4c855fdb6558ba2a24e499fe1360fa2b41dd53c422`**. Restore unchanged as `WORK/certificate-windows-first-failure.json`.
- The pinned Git history supplies source/proof inside each old tree, including [the Progress diagnostic](../2026-11-09-cryptopro-windows-progress-diagnostic.json), [certificate handoff](../2026-11-09-cryptopro-certificate-discovery-handoff.md), [guard manifest](../2026-11-09-cryptopro-certificate-discovery.json) and [helper review](../2026-11-09-vncode-167-certificate-guard-helper-review.json). A shallow checkout lacking these commits cannot satisfy the verifier.

## Public-before projection: derived, not a raw snapshot copy

[releases-before.required-fields.json](metadata/releases-before.required-fields.json) is a **DERIVED** compact projection of the public pre-publication snapshot. It preserves all16 release IDs, all258 asset IDs, and **every exact field value consumed by the unchanged public-preservation helper**. It is not byte-identical to the original512217-byte JSON.

- Original raw SHA: **`8a7537b5bdcec8962aac8a77b820e2faf1ea078960d82df1c1a453802edffe06`**.
- Projection SHA: **`a317c42979133ab656d639f0d8f41003a007b16c0d0812179a027be6ebc4dcf5`**;158330 bytes.
- [Projection provenance](metadata/releases-before.projection-provenance.json) records exact retained/dropped field lists, schema groups, record IDs, hashes and equality checks. The retained release fields are `id`, `tag_name`, `target_commitish`, `name`, `draft`, `prerelease`, `immutable`, `created_at`, `published_at`, `body` and projected `assets`. Asset fields are `id`, `name`, `size`, `digest`, `state`, `content_type`, `created_at`, `updated_at`, `browser_download_url`.
- Non-consumed fields such as author/uploader objects and download counters were dropped. No seller payload, email, credential/token, license, certificate/key material, raw log, installer, application binary or PDF was copied.

The outside original `WORK/releases-before.json` was left untouched and remains the input for the actual first verification. **Do not overwrite it with this projection.** If that original is later lost, the projection may be restored under that basename **only as the metadata-comparison input the helper consumes**. It cannot reproduce the original raw snapshot SHA. Any new verifier receipt must record the restored projection's hash and this derivation; do not claim the raw snapshot was recovered.

## Restore fixed-session inputs without overwriting originals

The following is a recovery recipe, not a command executed by the archive task. Use a disposable/restored session directory. The unmodified scripts have fixed `WORK` paths even when `--repo`, `--artifact-dir` and other command options are provided.

```bash
release_repo=/workspace/vncode-znack-reconcile
release_work=/workspace/vncode-167-release
archive_dir="$release_repo/docs/validation/release-167-tools"
mkdir -p "$release_work"
cd "$archive_dir"
sha256sum -c SHA256SUMS

for proof_name in source-inclusion-fifth-attempt.json source-inclusion-certificate-first-attempt.json source-inclusion-certificate-pre-guard-attempt.json; do
  if test -e "$release_work/$proof_name"; then
    cmp "$archive_dir/metadata/$proof_name" "$release_work/$proof_name"
  else
    cp "$archive_dir/metadata/$proof_name" "$release_work/$proof_name"
  fi
done
```

Restore current inclusion and the failed receipt by their **pinned Git revision**, then check the hashes above. Current working-tree copies can change after a later release, so their filenames alone are not a pin. Do not overwrite existing outside files without comparing them.

```bash
git -C "$release_repo" show b1e5e7774e1ab01d0c1c57ee934900c6927638f7:docs/validation/2026-11-08-vncode-167-source-inclusion.json > "$release_work/source-inclusion.restored.json"
git -C "$release_repo" show b1e5e7774e1ab01d0c1c57ee934900c6927638f7:docs/validation/2026-11-09-vncode-167-certificate-first-windows-failure.json > "$release_work/certificate-windows-first-failure.restored.json"
sha256sum "$release_work/source-inclusion.restored.json" "$release_work/certificate-windows-first-failure.restored.json"
```

Only after verifying those two digests, move a missing destination into its required basename or compare with the existing file. The public verifier likewise needs `tags-before-final.json` and `releases-before.json`; restore the exact tag snapshot and use the release projection only under the explicit missing-original rule above. Historical failures/cancellations remain historical; restoring their JSON does not turn them into a passing final build.

## Package verification — only after fresh build success

The package helper performs **offline** inspection of exactly16 already-downloaded final build files, their source/runtime/JAR/resources, full XML, native/migration/DPAPI/install/coexist/uninstall/passive-upgrade evidence, checksums, bundle and optional saved GitHub metadata. It does not fetch assets, install software or authorize publication.

Its final pins require **4188 tests,547 suites,0 failure/error, exactly2 named OS skips**, certificate suites12/38/14 and all three10000-case stress fixtures. These are required gates, **not results established by this archive**. Compiler-vendor byte differences from the Linux reference are informational. An omitted release/run JSON leaves those remote checks unverified.

After source d023/run42611294387 actually succeeds and all16 files are completely saved in the guard artifact directory, the original invocation is:

```bash
python3 "$archive_dir/verify_package-certificate-guard.py" \
  --repo "$release_repo" \
  --artifact-dir "$release_work/windows-artifact-certificate-guard" \
  --source-inclusion "$release_work/source-inclusion.json" \
  --run-json "$release_work/validated-final-run-metadata.json" \
  --receipt "$release_work/package-verification-certificate-guard.json"
```

`validated-final-run-metadata.json` is a descriptive example basename for a separately saved **actual** GitHub run response; it must identify the pinned completed successful build and repository. It is not included or fabricated here. `--release-json` may additionally check an actual saved16-asset release response when available. No installer, native result, full-suite PASS or package receipt is created merely by archiving/importing this script.

## Public preservation — only after actual publication

This offline tool requires the **passed guard package receipt**, the same16 build files, all16 actual public downloads in `public-downloads-certificate-guard`, before snapshots, and fresh after snapshots:

- `releases-after-certificate-guard.json`
- `latest-after-certificate-guard.json`
- `tags-after-certificate-guard.json`

The after snapshots and payloads must come from the real publication; they are future gated inputs, not archived fabricated data. The helper requires immutable stable Latest1.6.7 at source d023, exact17 releases/46 tags after preserving all old16 releases/258 assets/45 tags, and equality of public/build/package/GitHub hashes and sizes. It ignores download counters. It writes `public-preservation-verification-certificate-guard.json` only after all checks succeed.

```bash
python3 "$archive_dir/verify_public_preservation-certificate-guard.py" \
  --directory "$release_work/public-downloads-certificate-guard"
```

Without `--updater-receipt`, updater acceptance remains explicitly unverified. After the actual1.6.6 probe has produced its new receipt, rerun with:

```bash
python3 "$archive_dir/verify_public_preservation-certificate-guard.py" \
  --directory "$release_work/public-downloads-certificate-guard" \
  --updater-receipt "$release_work/updater-after-certificate-guard.json"
```

## Actual published1.6.6 updater probe

The Java source belongs to `com.vncode.app.integration.update` and must compile/run against the **actual published1.6.6 application and dependency JARs**, not a new source checkout with a version override. [Extraction metadata](metadata/installed166-extraction.json) pins portable SHA `14b5c2cb64994cbb8b18bd434eb3d7c5333768cde3be6819d999ff2b60aab0dd`,148213914 bytes, app JAR SHA `fee9bbcee1658b2b8b5a6bf623bef5f76f035cc805ef97958f35713897babfd1` and75 JARs. Restore those separately from immutable published assets and validate their digests; no executable/JAR is kept here.

Example for the historical Linux/Java25 session after that binary restoration:

```bash
mkdir -p "$release_work/installed166/probe"
javac -encoding UTF-8 -cp "$release_work/installed166/app/*" \
  -d "$release_work/installed166/probe" "$archive_dir/Installed166UpdateProbe.java"
java -Dvncode.appdata.dir="$release_work/installed166/blank-profile" \
  -cp "$release_work/installed166/probe:$release_work/installed166/app/*" \
  com.vncode.app.integration.update.Installed166UpdateProbe \
  "$release_work/updater-after-certificate-guard.json" AVAILABLE
```

Unlike the Python tools, this Java probe **does make a network read** and initializes a blank local database; execute it only in the authorized verification step. Reuse the managed Cloud proxy/CA JVM configuration from the successful before-probe. Do not disable TLS/signature verification or add authentication secrets. The probe rejects an explicit application Authorization header and a credential-bearing proxy URI; inherited infrastructure may still add repository credentials, so anonymous internet access is not independently proven. It requires binary version1.6.6 and expected status `AVAILABLE`, then checks1.6.7 Windows MSI selection; its temporary `os.name` change occurs only after the update network read, not as a version override. It does not run an installer, call seller APIs or activate a license. Seller popup/installation remain unobserved; native passive upgrade is separate evidence.

## Scope and preservation

The current source inclusion/security/helper reviews already tracked elsewhere remain authoritative within their recorded scopes. The Java security gate had the same eight old findings and remained **FAIL**; this archive makes no new scanner or all-security-green claim. It does not waive source/runtime/native/package/public-byte/updater gates or approve a new source pin.

Only this documentation subtree was added. Outside originals, application code, workflows/build inputs, STATE and release/tag/assets were not modified by the archive task. Historical source proof, derived metadata and real final acceptance must stay distinguishable when future AI resumes work.
