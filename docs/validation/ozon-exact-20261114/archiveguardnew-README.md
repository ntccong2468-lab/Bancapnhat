# Source ZIP guard — new Ozon exact-matching checkpoint

This helper verifies source bytes and archive structure only. It does not run Maven, VN Code, remote APIs, paid KIZ purchases, signing or Windows tests.

- Frozen code: `a86048ec57521bc4d32b0a27c86d2eb1ee70fcb3`.
- Baseline: `4ef0f1e2b8ef7b28190de3034b0c67ebf615be0b`; previous documentation anchor `e277d30208d12778ce9239dcde4be16f5ddc9438` must be an ancestor.
- Reviewed manifest SHA-256: `fdf343f467b6edf11033192275adf922e288655a3463f5579749497d604e6762`.
- Baseline 800 runtime paths all retained; final code has 801 paths, 779 unchanged, 21 modified, one exact new matcher path, no deletion. Source/tests and Maven/build inputs at final docs commit must still equal the code pin.
- Every tracked final Git blob must appear byte-for-byte in the ZIP; no EOL normalization or silent omission. CRC, Git archive comment, path/type/mode, wrapper executable bits, ZIP metadata, export attributes, LFS and symlinks are checked.
- The sole credential-pattern exception is the already reviewed `StartupProgressTest.java`, exact whole-file SHA, one JWT with only a sub claim and invalid 12-byte ES256 signature. All other matches, including tests, fail. These finite patterns do not prove absence of every credential form.

## Final archive invocation

Create the final archive from the actual final docs commit, then use a new receipt path outside the repository:

```bash
python3 /tmp/vncode-ozon-exact-20261114/verify-source-zip.py \
  --repo /workspace/vncode-ozon-exact-gtin-20261114 \
  --zip /workspace/artifacts/VN-code-1.6.10-source-20261114.zip \
  --ref FINAL_DOCS_COMMIT_FULL_40_HEX \
  --code-ref a86048ec57521bc4d32b0a27c86d2eb1ee70fcb3 \
  --preservation /tmp/vncode-ozon-exact-20261114/source-preservation.json \
  --preservation-sha256 fdf343f467b6edf11033192275adf922e288655a3463f5579749497d604e6762 \
  --receipt /workspace/artifacts/VN-code-1.6.10-source-20261114-verification.json
```

Helper refuses mutable branch refs, a changed code pin, changed manifest bytes/pin, and an existing receipt path. It never overwrites prior evidence. Prefix is exactly `VN-code-1.6.10-source/`.

## Controls actually run

`archiveguardnew-test-source-zip-helper.py`: **36 PASS**, comprising 21 predecessor controls plus 15 controls using the actual code/baseline trees and reviewed manifest. These cover acceptance of the one approved addition, unauthorized addition, baseline deletion with equal total count, new matcher missing/tampered/mode changed, untested source/test/build changes, old reduced 504-file tree, manifest byte tamper and caller repinning, and the exact synthetic JWT shape.

A CODE-only control archive (3341 files) passed all checks with the confirmed test fixture reported. Three actual modified ZIP archives then correctly failed: byte tamper in the new matcher with valid ZIP CRC, untracked added file, and missing baseline launcher. These are verifier controls, **not acceptance of the later final source ZIP**. Actual root final archive remains to be generated and checked against its final docs commit.

Predecessor `/tmp/vncode-kiz-repair-20261114/verify-source-zip.py` remains unchanged at SHA `cf494177d2edeacbe317ab1318a38319348360899289039fac6f89f9ca45a0d7`.
