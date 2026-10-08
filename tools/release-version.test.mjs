import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { resolveReleaseVersion } from "./release-version.mjs";

const LEGACY_WINDOWS_UPGRADE_UUID = "D0FC7057-DA6C-3181-ADF9-C21DB2C9152A";
const WCODE_CURRENT_UPGRADE_UUID = "0356BE08-487C-4E04-A2C2-353AF93DB2DE";
const VNCODE_UPGRADE_UUID = "8CBBA0E2-6E73-4F56-9101-6BC0948D3C72";

async function createProject(overrides = {}) {
  const root = await mkdtemp(path.join(tmpdir(), "vncode-release-version-"));
  const files = {
    "pom.xml": `<?xml version="1.0"?>
<project>
  <version>1.1.10</version>
  <properties><app.version>1.1.10</app.version></properties>
</project>
`,
    ...overrides,
  };
  for (const [relativePath, content] of Object.entries(files)) {
    const target = path.join(root, relativePath);
    await writeFile(target, content, "utf8");
  }
  return root;
}

test("returns the single version declared consistently by the source tree", async () => {
  const root = await createProject();
  try {
    const release = await resolveReleaseVersion({ root });

    assert.equal(release.version, "1.1.10");
    assert.equal(release.tag, "v1.1.10");
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("accepts a release tag only when it exactly matches the source version", async () => {
  const root = await createProject();
  try {
    const release = await resolveReleaseVersion({ root, refType: "tag", refName: "v1.1.10" });

    assert.equal(release.version, "1.1.10");
    assert.equal(release.shouldPublish, true);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("manual and branch runs resolve the source version but cannot publish", async () => {
  const root = await createProject();
  try {
    const release = await resolveReleaseVersion({ root, refType: "branch", refName: "main" });

    assert.equal(release.version, "1.1.10");
    assert.equal(release.shouldPublish, false);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("rejects inconsistent source manifests", async () => {
  const root = await createProject({
    "pom.xml": `<?xml version="1.0"?>
<project>
  <version>1.1.10</version>
  <properties><app.version>1.1.11</app.version></properties>
</project>
`,
  });
  try {
    await assert.rejects(
      () => resolveReleaseVersion({ root }),
      /Version declarations do not match/,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("rejects malformed or mismatched release tags", async () => {
  const root = await createProject();
  try {
    await assert.rejects(
      () => resolveReleaseVersion({ root, refType: "tag", refName: "release-1.1.10" }),
      /Release tags must use vMAJOR\.MINOR\.PATCH/,
    );
    await assert.rejects(
      () => resolveReleaseVersion({ root, refType: "tag", refName: "v1.1.11" }),
      /does not match source version 1\.1\.10/,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("VN code has an independent Windows installer identity and tests coexistence", async () => {
  const workflow = await readFile(new URL("../.github/workflows/release.yml", import.meta.url), "utf8");
  const build = await readFile(new URL("../build.bat", import.meta.url), "utf8");
  const probe = await readFile(new URL("../tools/windows-side-by-side-smoke.ps1", import.meta.url), "utf8");
  const declaration = workflow.match(/^\s*WINDOWS_UPGRADE_UUID:\s*([0-9A-F-]+)\s*$/m);
  assert.equal(declaration?.[1], VNCODE_UPGRADE_UUID);
  assert.notEqual(VNCODE_UPGRADE_UUID, LEGACY_WINDOWS_UPGRADE_UUID);
  assert.notEqual(VNCODE_UPGRADE_UUID, WCODE_CURRENT_UPGRADE_UUID);
  assert.ok(build.includes(`WINDOWS_UPGRADE_UUID=${VNCODE_UPGRADE_UUID}`));
  assert.equal((workflow.match(/--win-upgrade-uuid \$env:WINDOWS_UPGRADE_UUID/g) ?? []).length, 2);
  assert.equal((workflow.match(/--install-dir 'VNcodeApp'/g) ?? []).length, 2);
  assert.match(workflow, /windows-side-by-side-smoke\.ps1/);
  assert.match(build, /--temp "target\\jpackage-temp"/,
    "the native probe must install the same MSI payload embedded in the EXE");
  assert.ok(probe.includes('target\\jpackage-temp\\msi\\VN code-$Version.msi'),
    "select the delivered MSI payload, not WiX intermediate files");
  assert.match(probe, /RUNNER_ENVIRONMENT -cne 'github-hosted'/);
  assert.match(probe, /releases\/download\/v1\.1\.75\/WCode\.exe/);
  assert.match(probe, /509e29e167b4731e8a387e4f9309cfb3779405ba84bd75c16ba9fd1ffab42cca/);
  assert.match(probe, /5f109cb64afb6be8947a46238708c6e28e67dacdeb265ca5cb3189b30bb39a52/);
  assert.match(probe, /EmbeddedMsi\]::Extract/);
  assert.match(probe, /WindowsDataProbe fresh/);
  assert.match(probe, /Invoke-Msi '\/x' \$MsiPath/);
  assert.match(probe, /Verify-WcodeUnchanged/);
  assert.match(probe, /coinstall-data-sentinel\.txt/);
});

test("builds a releasable Windows package when optional signing secrets are absent", async () => {
  const workflow = await readFile(
    new URL("../.github/workflows/release.yml", import.meta.url),
    "utf8",
  );

  assert.match(workflow, /Resolve-OptionalGroup 'Windows Authenticode signing'/);
  assert.match(workflow, /WINDOWS_SIGNING_ENABLED=/);
  assert.match(workflow, /if \(-not \$signingEnabled\) \{ return \}/,
    "the shared installer build must make Authenticode conditional");
  assert.match(workflow, /UPDATE_MANIFEST_ENABLED=/);
  assert.match(workflow, /if \(\$env:UPDATE_MANIFEST_ENABLED -ceq 'true'\)/,
    "the signed update manifest must only be emitted when its key pair exists");
  assert.match(workflow, /Bộ cài Windows hiện chưa có chữ ký Authenticode/,
    "release notes must disclose unsigned Windows packages");
  assert.doesNotMatch(workflow, /for file in \\\n\s+VN-code\.msi VN code\.exe VN-code-portable\.zip update-manifest\.json/,
    "publishing must not require an optional manifest");
});
