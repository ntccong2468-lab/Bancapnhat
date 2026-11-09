#!/usr/bin/env python3
"""Pinned certificate-change package verifier; not a release receipt until verification succeeds.

Source/run/counts/commits/delta/manifest constants are pinned for the certificate candidate.
Historical 0f5 source proof is checked separately; its old Windows result is not
reused as verification of the new certificate code. Never download or execute assets.

The constants in this file are configured for the certificate candidate. A new
source/run requires another reviewed repin. The preserved 0f5 verifier and its
artifact/receipt must not be treated as certificate-build verification.

Usage only after configuration and all 16 NEW files have finished downloading:
  python3 /workspace/vncode-167-release/verify_package-certificate.py

Optional --release-json and --run-json accept already-saved GitHub API metadata.
Without these inputs the receipt explicitly leaves remote digests / CI status
unverified. Linux compiler class-byte equality is reported, never required.
"""

import argparse
import collections
import datetime
import hashlib
import io
import json
import pathlib
import re
import stat
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile


SOURCE = 'd0231cd6913ce3a1764067bad0901b32c535de41'
HISTORICAL_SOURCE = "0f5f3217b4d5c33ec1c10a1ed279c9dd78f62e96"
HISTORICAL_BUILD = 42587222212
FIRST_CERTIFICATE_SOURCE = "e77437c5ddbd168c7cfecfcf12d202f019751d87"
FIRST_CERTIFICATE_BUILD = 42605121356
PROGRESS_FIX_SOURCE = "2a5c335c14874bdea18094825c48477b825ba858"
PRE_GUARD_BUILD = 42607033320
PRE_GUARD_PROOF_NAME = "source-inclusion-certificate-pre-guard-attempt.json"
PRE_GUARD_PROOF_SHA256 = "ef28606a62e94c8b3e7c334887562a11e9c994db52565c507bbc92cdd7c1e680"
# Frozen exact 2a5 -> identity-change guard source delta; historical progress fix remains separate.
GUARD_DELTA_PATHS = ('docs/validation/2026-11-09-cryptopro-certificate-discovery-handoff.md', 'docs/validation/2026-11-09-cryptopro-certificate-discovery.json', 'src/main/java/com/vncode/app/integration/znack/ZnackRepository.java', 'src/main/java/com/vncode/app/ui/znack/ZnackAutomationController.java', 'src/main/resources/com/vncode/app/i18n/messages_en.properties', 'src/main/resources/com/vncode/app/i18n/messages_ru.properties', 'src/main/resources/com/vncode/app/i18n/messages_vi.properties', 'src/main/resources/com/vncode/app/i18n/messages_zh.properties', 'src/test/java/com/vncode/app/ui/znack/ZnackCertificateDiscoveryUiTest.java')
GUARD_RUNTIME_PATHS = (
    "src/main/java/com/vncode/app/integration/znack/ZnackRepository.java",
    "src/main/java/com/vncode/app/ui/znack/ZnackAutomationController.java",
    "src/main/resources/com/vncode/app/i18n/messages_en.properties",
    "src/main/resources/com/vncode/app/i18n/messages_ru.properties",
    "src/main/resources/com/vncode/app/i18n/messages_vi.properties",
    "src/main/resources/com/vncode/app/i18n/messages_zh.properties",
)
FIRST_CERTIFICATE_PROOF_SHA256 = "f8b0994e43ed625e5fb051f1b14551bfb4928b7f932fac9fb5e97c9d04d65d71"
CERTIFICATE_DIAGNOSTIC_RUN = 42606535850
CERTIFICATE_DIAGNOSTIC_PATH = "docs/validation/2026-11-09-cryptopro-windows-progress-diagnostic.json"
HISTORICAL_SOURCE_FILES = 2985
HISTORICAL_PROOF_NAME = "source-inclusion-fifth-attempt.json"
HISTORICAL_PROOF_SHA256 = "63b37971dd05c8cb4ab6ba877b0a34a9d9cdc94eda25d39309123006625402e2"
FIRST_ATTEMPT_SOURCE = "026f1a86ae5928769a9b36720c2da98cf8dd7efd"
FIRST_ATTEMPT_BUILD = 42515409829
SECOND_ATTEMPT_SOURCE = "968d639f6e8233d572b4992f1ac3a4bd09a91903"
SECOND_ATTEMPT_BUILD = 42523983786
THIRD_ATTEMPT_SOURCE = "c9c7b36cd9e68cd3cec159e7be904c4ffe04913f"
THIRD_ATTEMPT_BUILD = 42525209391
FOURTH_ATTEMPT_SOURCE = "210c01d4a0854a55333151f252a74308888279ad"
FOURTH_ATTEMPT_BUILD = 42570802535
PREVIOUS_SOURCE = "ac8ba1c939e22d0318877a3a2ba7242359cce78e"
VERSION = "1.6.7"
BASELINE = "1.6.6"
BUILD = 42611294387
WORKFLOW = 392732536
RELEASE_ID = 438471378
REPOSITORY = "ntccong2468-lab/Bancapnhat"
UPDATE_URL = "https://github.com/" + REPOSITORY
UPGRADE_UUID = "8CBBA0E2-6E73-4F56-9101-6BC0948D3C72"
EXPECTED_CLASSES = 1987
EXPECTED_SOURCE_FILES = 2993
EXPECTED_RUNTIME_FILES = 115
EXPECTED_JAVA_INPUTS = 90
EXPECTED_RESOURCE_INPUTS = 25
EXPECTED_JAVA_TESTS = 4188
EXPECTED_JAVA_SUITES = 547
EXPECTED_JAVA_SUMMARY = {"tests": EXPECTED_JAVA_TESTS, "failures": 0, "errors": 0, "skipped": 2,
                         "passed": EXPECTED_JAVA_TESTS - 2}
EXPECTED_OS_SKIPS = (
    ("com.vncode.app.features.gs1.Gs1PrefixTemplateStoreTest", "privatePermissionsAreAppliedWhereSupported"),
    ("com.vncode.app.shared.WindowsSecretProtectorTest", "neverFallsBackToPlaintextOffWindows"),
)
EXPECTED_CERTIFICATE_SUITES = {
    "com.vncode.app.integration.znack.signature.CryptoProCertificateDiscoveryServiceTest": 12,
    "com.vncode.app.integration.znack.signature.CryptoProCertificateRoutingTest": 38,
    "com.vncode.app.ui.znack.ZnackCertificateDiscoveryUiTest": 14,
}
CERTIFICATE_COMMITS = ('e77437c5ddbd168c7cfecfcf12d202f019751d87', '2a5c335c14874bdea18094825c48477b825ba858', 'd0231cd6913ce3a1764067bad0901b32c535de41')
CERTIFICATE_DELTA_PATHS = ('.github/workflows/publish-vncode-baseline.yml', 'docs/releases/VN-code-1.6.7.md', 'docs/validation/2026-11-09-cryptopro-certificate-discovery-handoff.md', 'docs/validation/2026-11-09-cryptopro-certificate-discovery.json', 'docs/validation/2026-11-09-cryptopro-windows-progress-diagnostic.json', 'src/main/java/com/vncode/app/integration/znack/ZnackRepository.java', 'src/main/java/com/vncode/app/integration/znack/signature/CryptoProCertificateDiscoveryService.java', 'src/main/java/com/vncode/app/integration/znack/signature/CryptoProSignatureProvider.java', 'src/main/java/com/vncode/app/integration/znack/signature/CryptoProSignedCertificateReader.java', 'src/main/java/com/vncode/app/integration/znack/signature/WindowsCadesCertificateDiscovery.java', 'src/main/java/com/vncode/app/ui/znack/ZnackAutomationController.java', 'src/main/resources/com/vncode/app/i18n/messages_en.properties', 'src/main/resources/com/vncode/app/i18n/messages_ru.properties', 'src/main/resources/com/vncode/app/i18n/messages_vi.properties', 'src/main/resources/com/vncode/app/i18n/messages_zh.properties', 'src/test/java/com/vncode/app/integration/znack/signature/CryptoProCertificateDiscoveryServiceTest.java', 'src/test/java/com/vncode/app/integration/znack/signature/CryptoProCertificateRoutingTest.java', 'src/test/java/com/vncode/app/ui/DarkReferenceScreensTest.java', 'src/test/java/com/vncode/app/ui/workspace/ApprovedWorkspaceAcceptanceTest.java', 'src/test/java/com/vncode/app/ui/znack/CertificateDiscoveryFixture.java', 'src/test/java/com/vncode/app/ui/znack/ZnackAutomationControllerTest.java', 'src/test/java/com/vncode/app/ui/znack/ZnackCertificateDiscoveryUiTest.java')
CERTIFICATE_MANIFEST_PATH = 'docs/validation/2026-11-09-cryptopro-certificate-discovery.json'
CERTIFICATE_SENTINELS = ('integration/znack/signature/CryptoProCertificateDiscoveryService', 'integration/znack/signature/CryptoProSignatureProvider', 'integration/znack/signature/CryptoProSignedCertificateReader', 'integration/znack/signature/WindowsCadesCertificateDiscovery', 'ui/znack/ZnackAutomationController', 'integration/znack/signature/WindowsCadesCertificateDiscovery$Deadline', 'integration/znack/signature/CryptoProSignatureProvider$SigningDeadline', 'integration/znack/ZnackRepository', 'ui/znack/ZnackAutomationController$SignatureTaskSubmitter')
LINUX_REFERENCE_SHA = 'ec24b407017a40e94c58e8246b637a67f5ad8461e9500ea622c59a1f5bd98cd2'
ROOT = pathlib.Path("/workspace/vncode-znack-reconcile")
WORK = pathlib.Path("/workspace/vncode-167-release")
REQUIRED = [
    f"VN-code-{VERSION}-Windows-x64.exe",
    f"VN-code-{VERSION}-Windows-x64.msi",
    f"VN-code-{VERSION}-Windows-x64.sha256",
    f"VN-code-{VERSION}-Windows-x64.zip",
    f"VN-code-{VERSION}-source.zip",
    f"VN-code-{VERSION}-history.bundle",
    f"VN-code-{VERSION}-verification.zip",
    "build-info.json", "release-notes.md", "native-smoke.json",
    "upgrade-baseline-smoke.json",
]
ASSETS = REQUIRED + ["asset-manifest.json", "SHA256SUMS", "gs1-smoke.json",
                     "side-by-side-smoke.json", "previous-baseline.json"]
BASE_SOURCE_COMMITS = [
    "ae2d48a2e397428869d553b7e008402f469a3566",
    "5f816b1df7772a8fb40771c6851b146c4356f76d",
    "6905fe0a73b5300b6f5390ed0c71dbeca62ea81a",
    "0a00fbd837d5e92eeb0018e480fb1891436107e9",
]
SOURCE_COMMITS = BASE_SOURCE_COMMITS + list(CERTIFICATE_COMMITS)

SENTINELS = [
    "features/catalogsync/CatalogPermitEvidence",
    "features/gtinsync/GtinColorIdentity",
    "integration/znack/CatalogReadRevision",
    "shared/MotionBoundary", "shared/NavigationContext",
    "shared/PermitDocumentDate", "shared/PermitDocumentNumber",
    "shared/WorkspaceMotionTokens", "ui/controls/WorkspaceFilterTabs",
    "ui/workspace/WorkspaceAppearancePane", "ui/workspace/WorkspaceCommandPalette",
    "features/sync/RegistrationSources", "features/kizmapping/WbCatalogMappingService",
    "features/kiz/KizPurchaseMappingProof",
    "integration/znack/registration/RegistrationPermitValidator",
    "integration/znack/registration/ZnackNationalCatalogService",
    "shared/ThemeService", "shared/WorkspaceMotion",
    "ui/shop/ShopSidebarController", "ui/workspace/WorkspaceHeaderController",
    "features/gtinsync/OzonReviewedVariantProof",
    "integration/ozon/OzonAcceptanceKizReplacement",
    "ui/znackregistration/OzonCatalogVariantLinkDialog",
] + list(CERTIFICATE_SENTINELS)
STRESS = [
    "wbTenThousandGtinUseFourWholeCardWritesAndReadBackEveryMember",
    "ozonTenThousandGtinUseOneHundredDedicatedBarcodeWrites",
    "wbTenThousandDelayedReadbacksKeepDurableReportPendingUntilVerifiedWithoutReplay",
]


class VerificationError(Exception):
    pass


def require(condition, code):
    if not condition:
        raise VerificationError(code)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_hash(file):
    value = hashlib.sha256()
    with file.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def no_duplicate_keys(pairs):
    value = {}
    for key, item in pairs:
        require(key not in value, "JSON_DUPLICATE_KEY")
        value[key] = item
    return value


def json_bytes(data):
    return json.loads(data.decode("utf-8-sig"), object_pairs_hook=no_duplicate_keys)


def git(repo, *args):
    result = subprocess.run(["git", "-C", str(repo), *args], capture_output=True)
    require(result.returncode == 0, "GIT_READ_FAILED:" + args[0])
    return result.stdout


def git_blob(repo, ref, path):
    return git(repo, "show", ref + ":" + path)


def is_ancestor(repo, ancestor, descendant):
    code = subprocess.run(["git", "-C", str(repo), "merge-base", "--is-ancestor",
                           ancestor, descendant], capture_output=True).returncode
    require(code in (0, 1), "GIT_ANCESTRY_READ_FAILED")
    return code == 0


def pinned_tree(repo, source=None):
    """Read Git blobs in one batch, independent of the active checkout."""
    source = SOURCE if source is None else source
    entries = {}
    for row in git(repo, "ls-tree", "-r", "-z", source).split(b"\0"):
        if not row:
            continue
        meta, name = row.split(b"\t", 1)
        mode, kind, object_id = meta.split()
        require(kind == b"blob", "UNSUPPORTED_GIT_TREE_ENTRY")
        entries[name.decode("utf-8")] = object_id.decode("ascii")
    object_ids = list(dict.fromkeys(entries.values()))
    result = subprocess.run(["git", "-C", str(repo), "cat-file", "--batch"],
                            input=("\n".join(object_ids) + "\n").encode(), capture_output=True)
    require(result.returncode == 0, "GIT_BLOB_BATCH_FAILED")
    stream = io.BytesIO(result.stdout)
    objects = {}
    for wanted in object_ids:
        header = stream.readline().split()
        require(len(header) == 3 and header[0].decode() == wanted and header[1] == b"blob",
                "GIT_BLOB_BATCH_HEADER_MISMATCH")
        size = int(header[2])
        data = stream.read(size)
        require(len(data) == size and stream.read(1) == b"\n", "GIT_BLOB_BATCH_LENGTH_MISMATCH")
        objects[wanted] = data
    require(stream.read() == b"", "GIT_BLOB_BATCH_TRAILING_BYTES")
    return {name: objects[object_id] for name, object_id in entries.items()}


def text_equivalence(actual, expected, context):
    """Only exact equality or UTF-8 text CRLF/LF normalization is accepted."""
    if actual == expected:
        return "exact"
    try:
        actual.decode("utf-8")
        expected.decode("utf-8")
    except UnicodeDecodeError:
        raise VerificationError(context + ":BINARY_MISMATCH") from None
    require(b"\0" not in actual and b"\0" not in expected,
            context + ":NUL_TEXT_MISMATCH")
    require(actual.replace(b"\r\n", b"\n") == expected.replace(b"\r\n", b"\n"),
            context + ":TEXT_BYTES_MISMATCH")
    return "utf8-crlf-normalized"


def zip_files(archive):
    files = {}
    for member in archive.infolist():
        name = member.filename
        path = pathlib.PurePosixPath(name)
        require(not name.startswith(("/", "\\")) and "\\" not in name
                and ".." not in path.parts and not re.match(r"^[A-Za-z]:", name),
                "UNSAFE_ZIP_MEMBER")
        if member.is_dir():
            continue
        require(name not in files, "DUPLICATE_ZIP_MEMBER")
        files[name] = member
    return files


def checksums(data):
    rows = {}
    for line in data.decode("ascii").strip().splitlines():
        match = re.fullmatch(r"([a-f0-9]{64})  ([A-Za-z0-9._-]+)", line)
        require(match is not None, "INVALID_CHECKSUM_LINE")
        digest, name = match.groups()
        require(name not in rows, "DUPLICATE_CHECKSUM_MEMBER")
        rows[name] = digest
    return rows


def properties(data):
    rows = {}
    for line in data.decode("utf-8-sig").splitlines():
        if not line.strip() or line.lstrip().startswith(("#", "!")):
            continue
        require("=" in line, "UNEXPECTED_PROPERTY_SYNTAX")
        key, value = line.split("=", 1)
        key = key.strip()
        require(key not in rows, "DUPLICATE_PROPERTY")
        rows[key] = value.strip()
    return rows


def manifest_fields(data):
    text = data.decode("utf-8").replace("\r\n", "\n")
    lines = []
    for line in text.splitlines():
        if line.startswith(" "):
            require(bool(lines), "INVALID_JAR_MANIFEST_CONTINUATION")
            lines[-1] += line[1:]
        elif line:
            lines.append(line)
    result = {}
    for line in lines:
        require(": " in line, "INVALID_JAR_MANIFEST_LINE")
        key, value = line.split(": ", 1)
        require(key not in result, "DUPLICATE_JAR_MANIFEST_FIELD")
        result[key] = value
    return result


def expect_fields(value, expected, context):
    for key, wanted in expected.items():
        actual = value.get(key)
        require(type(actual) is type(wanted) and actual == wanted, context + ":" + key)


def verify_smokes(load, info, local, tree):
    native = load("native-smoke.json")
    isolation = load("side-by-side-smoke.json")
    upgrade = load("upgrade-baseline-smoke.json")
    gs1 = load("gs1-smoke.json")
    previous = load("previous-baseline.json")
    expect_fields(native, {"version": VERSION, "platform": "windows-x64", "appName": "VN code",
                          "windowTitle": "VN code v" + VERSION, "nativeLauncher": "passed",
                          "schemaMigration": "3 to 6", "historyPreserved": True,
                          "rollbackSnapshotVerified": True, "liveMarketplaceMutations": False}, "NATIVE_SMOKE")
    expect_fields(isolation, {"version": VERSION, "appName": "VN code", "platform": "windows-x64",
                             "result": "passed", "wcodeVersion": "1.1.75",
                             "windowTitle": "VN code v" + VERSION, "installerUpgradeUuid": UPGRADE_UUID,
                             "independentRegistrations": True, "freshVncodeData": True,
                             "wcodeFilesUnchanged": True, "uninstallPreservesWcode": True,
                             "liveMarketplaceMutations": False}, "ISOLATION_SMOKE")
    expect_fields(upgrade, {"version": VERSION, "appName": "VN code", "fromVersion": BASELINE,
                           "result": "passed", "installerUpgradeUuid": UPGRADE_UUID,
                           "singleRegistration": True, "dataPreserved": True,
                           "liveMarketplaceMutations": False, "passiveExeHandoffVerified": True}, "UPGRADE_SMOKE")
    require(re.fullmatch(r"[a-f0-9]{64}", upgrade.get("fingerprint", "")) is not None,
            "UPGRADE_FINGERPRINT_INVALID")
    exe = f"VN-code-{VERSION}-Windows-x64.exe"
    msi = f"VN-code-{VERSION}-Windows-x64.msi"
    expect_fields(upgrade, {"passiveExeInstallerSha256": local[exe]["sha256"],
                           "passiveExeEmbeddedMsiSha256": local[msi]["sha256"]}, "UPGRADE_INSTALLER_FINGERPRINT")
    expect_fields(gs1, {"version": VERSION, "result": "passed", "liveGS1Mutations": False}, "GS1_SMOKE")
    expect_fields(previous, {"version": BASELINE, "tag": "v" + BASELINE, "skipUpgrade": False}, "PREVIOUS_BASELINE")
    previous_package = json_bytes(tree["docs/validation/2026-11-07-vncode-166-package.json"])
    old_exe = previous_package["assets"][f"VN-code-{BASELINE}-Windows-x64.exe"]
    expect_fields(previous, old_exe, "PREVIOUS_BASELINE_IMMUTABLE_EXE")
    expect_fields(info["installer"], {"name": exe, "bytes": local[exe]["bytes"],
                                      "sha256": local[exe]["sha256"]}, "BUILD_INSTALLER")
    return {"native": "passed", "isolation": "passed", "upgrade": "passed", "gs1": "passed",
            "upgradeFrom": BASELINE, "previousExeMatchesImmutable166Receipt": True,
            "passiveInstallerFingerprintsMatchPackagedExeAndMsi": True}


def verify_historical_inclusion(file, repo, tree, archived):
    proof = json_bytes(file.read_bytes())
    expect_fields(proof, {"version": VERSION, "sourceCommit": HISTORICAL_SOURCE}, "SOURCE_INCLUSION_IDENTITY")
    require(proof["previousRelease"]["sourceCommit"] == PREVIOUS_SOURCE, "INCLUSION_BASELINE_MISMATCH")
    require(is_ancestor(repo, PREVIOUS_SOURCE, HISTORICAL_SOURCE), "PREVIOUS_RELEASE_NOT_ANCESTOR")
    require({row["commit"] for row in proof["includedUnreleasedChanges"]} == set(BASE_SOURCE_COMMITS),
            "INCLUSION_REQUIRED_COMMITS_MISMATCH")
    for commit in BASE_SOURCE_COMMITS:
        require(is_ancestor(repo, commit, HISTORICAL_SOURCE), "INCLUSION_SOURCE_NOT_ANCESTOR")
    paths = git(repo, "diff", "--name-only", PREVIOUS_SOURCE, HISTORICAL_SOURCE, "--", "src/main").decode().splitlines()
    require(len(paths) == 109 and sum(p.startswith("src/main/java/") for p in paths) == 84
            and sum(p.startswith("src/main/resources/") for p in paths) == 25, "INCLUSION_RUNTIME_COUNT_MISMATCH")
    rows = proof["runtimeDiff"]["inputs"]
    require(len(rows) == 109 and {row["path"] for row in rows} == set(paths), "INCLUSION_RUNTIME_SET_MISMATCH")
    for row in rows:
        path = row["path"]
        require(row["sha256"] == sha(tree[path]), "INCLUSION_RUNTIME_SOURCE_HASH:" + path)
        text_equivalence(archived[path], tree[path], "INCLUSION_ARCHIVED_SOURCE:" + path)
        require(row.get("priorHandoverMatch") is True, "INCLUSION_PRIOR_HANDOVER_NOT_MATCHED")
    coverage = proof["priorHandoverCoverage"]
    require(coverage["matchingRuntimeFiles"] == coverage["coveredRuntimeFiles"] == 109
            and not coverage["missing"] and not coverage["mismatches"], "INCLUSION_PRIOR_HANDOVER_COVERAGE")
    # Independently re-read the historical manifests, rather than trusting their aggregate boolean.
    manifests = {}
    for receipt in coverage["receipts"]:
        raw = tree[receipt["path"]]
        require(sha(raw) == receipt["sha256"], "INCLUSION_HANDOVER_RECEIPT_HASH")
        values = json_bytes(raw)
        for field in receipt["manifestField"].split("."):
            values = values[field]
        manifests.update(values)
    require(all(manifests.get(path) == sha(tree[path]) for path in paths), "INCLUSION_HANDOVER_109_HASHES")
    require(proof["pom"]["sha256"] == sha(tree["pom.xml"]), "INCLUSION_POM_HASH")
    text_equivalence(archived["pom.xml"], tree["pom.xml"], "INCLUSION_POM_ARCHIVE")
    old_pom = git_blob(repo, PREVIOUS_SOURCE, "pom.xml")
    version_only = old_pom.replace(b"<version>1.6.6</version>", b"<version>1.6.7</version>", 1).replace(
        b"<app.version>1.6.6</app.version>", b"<app.version>1.6.7</app.version>", 1)
    require(version_only == tree["pom.xml"], "POM_HAS_NON_VERSION_CHANGE")
    first_attempt = proof["firstAttemptProvenance"]
    expect_fields(first_attempt, {"sourceCommit": FIRST_ATTEMPT_SOURCE,
                                  "fullWindowsBuildRunId": FIRST_ATTEMPT_BUILD,
                                  "testOnlyCorrectionCommit": SECOND_ATTEMPT_SOURCE,
                                  "freshFullWindowsSuiteRerunRequired": True,
                                  "freshFullWindowsBuildRunId": SECOND_ATTEMPT_BUILD,
                                  "earlierTestResultsReused": False,
                                  "originalFailuresPreserved": True,
                                  "recoveryOrSkipGateAdded": False}, "FIRST_ATTEMPT_PROVENANCE")
    expect_fields(first_attempt["originalJava"], {"tests": 4122, "failures": 1,
                                                 "errors": 1, "skipped": 2}, "FIRST_ATTEMPT_FAILED_TOTALS")
    fixture_delta = {
        ".github/workflows/publish-vncode-baseline.yml",
        "src/test/java/com/vncode/app/ui/tnved/FashionTnvedBrowserTest.java",
        "src/test/java/com/vncode/app/ui/znackregistration/ZnackCardRegistrationControllerTest.java",
        "tools/diagnostics/windows-input-observer.ps1",
    }
    actual_delta = git(repo, "diff", "--name-only", FIRST_ATTEMPT_SOURCE, HISTORICAL_SOURCE).decode().splitlines()
    require(set(actual_delta) == fixture_delta, "FIRST_ATTEMPT_NOT_FIXTURE_PREFLIGHT_ONLY_DELTA")
    for unchanged in first_attempt["productionAndPackagingInputsUnchanged"]:
        path = unchanged["path"]
        before = git(repo, "rev-parse", FIRST_ATTEMPT_SOURCE + ":" + path).decode().strip()
        after = git(repo, "rev-parse", HISTORICAL_SOURCE + ":" + path).decode().strip()
        require(before == after == unchanged["gitObject"] == unchanged["previousGitObject"],
                "FIRST_ATTEMPT_RUNTIME_OR_PACKAGING_CHANGED:" + path)
    second_attempt = proof["secondAttemptProvenance"]
    expect_fields(second_attempt, {"sourceCommit": SECOND_ATTEMPT_SOURCE,
                                   "fullWindowsBuildRunId": SECOND_ATTEMPT_BUILD,
                                   "testOnlyCorrectionCommit": THIRD_ATTEMPT_SOURCE,
                                   "freshFullWindowsSuiteRerunRequired": True,
                                   "freshFullWindowsBuildRunId": THIRD_ATTEMPT_BUILD,
                                   "earlierTestResultsReused": False,
                                   "originalFailuresPreserved": True,
                                   "recoveryOrSkipGateAdded": False}, "SECOND_ATTEMPT_PROVENANCE")
    expect_fields(second_attempt["originalJava"], {"tests": 164, "failures": 1,
                                                  "errors": 0, "skipped": 0}, "SECOND_ATTEMPT_FAILED_TOTALS")
    second_delta = git(repo, "diff", "--name-only", SECOND_ATTEMPT_SOURCE, THIRD_ATTEMPT_SOURCE).decode().splitlines()
    require(second_delta == ["src/test/java/com/vncode/app/ui/znackregistration/ZnackCardRegistrationControllerTest.java"],
            "SECOND_CORRECTION_NOT_DIALOG_FIXTURE_ONLY")
    expect_fields(second_attempt["groupedRegression"]["totals"], {"tests": 184, "failures": 0,
                                                                 "errors": 0, "skipped": 0}, "FINAL_GROUPED_REGRESSION")
    third_attempt = proof["thirdAttemptProvenance"]
    expect_fields(third_attempt, {"sourceCommit": THIRD_ATTEMPT_SOURCE,
                                  "fullWindowsBuildRunId": THIRD_ATTEMPT_BUILD,
                                  "testOnlyCorrectionCommit": FOURTH_ATTEMPT_SOURCE,
                                  "freshFullWindowsSourceCommit": FOURTH_ATTEMPT_SOURCE,
                                  "freshFullWindowsSuiteRerunRequired": True,
                                  "freshFullWindowsBuildRunId": FOURTH_ATTEMPT_BUILD,
                                  "earlierTestResultsReused": False,
                                  "originalFailuresPreserved": True,
                                  "recoveryOrSkipGateAdded": False,
                                  "originalPassedCases": 4119,
                                  "originalSuites": 544}, "THIRD_ATTEMPT_PROVENANCE")
    expect_fields(third_attempt["originalJava"], {"tests": 4122, "failures": 1,
                                                 "errors": 0, "skipped": 2}, "THIRD_ATTEMPT_FAILED_TOTALS")
    require(is_ancestor(repo, SECOND_ATTEMPT_SOURCE, THIRD_ATTEMPT_SOURCE)
            and is_ancestor(repo, THIRD_ATTEMPT_SOURCE, FOURTH_ATTEMPT_SOURCE), "THIRD_ATTEMPT_SOURCE_NOT_ANCESTOR")
    third_delta = {
        ".github/workflows/publish-vncode-baseline.yml",
        "src/test/java/com/vncode/app/ui/tnved/FashionTnvedBrowserTest.java",
    }
    actual_third_delta = git(repo, "diff", "--name-only", THIRD_ATTEMPT_SOURCE, FOURTH_ATTEMPT_SOURCE).decode().splitlines()
    require(set(actual_third_delta) == third_delta, "THIRD_CORRECTION_NOT_POINTER_FIXTURE_PREFLIGHT_ONLY")
    changed_rows = third_attempt["changedPaths"]
    require(len(changed_rows) == 2 and {row["path"] for row in changed_rows} == third_delta,
            "THIRD_ATTEMPT_CHANGED_PATHS_MISMATCH")
    for row in changed_rows:
        path = row["path"]
        require(row["previousSha256"] == sha(git_blob(repo, THIRD_ATTEMPT_SOURCE, path))
                and row["sha256"] == sha(git_blob(repo, FOURTH_ATTEMPT_SOURCE, path)),
                "THIRD_ATTEMPT_CHANGED_PATH_HASH:" + path)
    unchanged_rows = third_attempt["productionAndPackagingInputsUnchanged"]
    unchanged_paths = {"src/main", "pom.xml", "build.bat", "build.sh", ".mvn", "mvnw", "mvnw.cmd"}
    require(len(unchanged_rows) == len(unchanged_paths)
            and {row["path"] for row in unchanged_rows} == unchanged_paths,
            "THIRD_ATTEMPT_UNCHANGED_INPUT_SET")
    for row in unchanged_rows:
        path = row["path"]
        before = git(repo, "rev-parse", THIRD_ATTEMPT_SOURCE + ":" + path).decode().strip()
        after = git(repo, "rev-parse", FOURTH_ATTEMPT_SOURCE + ":" + path).decode().strip()
        require(before == after == row["previousGitObject"] == row["gitObject"]
                and row["unchanged"] is True, "THIRD_ATTEMPT_RUNTIME_OR_PACKAGING_CHANGED:" + path)
    fourth_attempt = proof["fourthAttemptProvenance"]
    expect_fields(fourth_attempt, {"sourceCommit": FOURTH_ATTEMPT_SOURCE,
                                   "fullWindowsBuildRunId": FOURTH_ATTEMPT_BUILD,
                                   "testOnlyCorrectionCommit": HISTORICAL_SOURCE,
                                   "freshFullWindowsSourceCommit": HISTORICAL_SOURCE,
                                   "freshFullWindowsSuiteRerunRequired": True,
                                   "freshFullWindowsBuildRunId": HISTORICAL_BUILD,
                                   "earlierTestResultsReused": False,
                                   "originalFailuresPreserved": True,
                                   "recoveryOrSkipGateAdded": False,
                                   "originalPassedCases": 4119,
                                   "originalSuites": 544}, "FOURTH_ATTEMPT_PROVENANCE")
    expect_fields(fourth_attempt["originalJava"], {"tests": 4122, "failures": 1,
                                                  "errors": 0, "skipped": 2}, "FOURTH_ATTEMPT_FAILED_TOTALS")
    require(is_ancestor(repo, FOURTH_ATTEMPT_SOURCE, HISTORICAL_SOURCE), "FOURTH_ATTEMPT_SOURCE_NOT_ANCESTOR")
    observer_path = "tools/diagnostics/windows-input-observer.ps1"
    fourth_delta = {
        ".github/workflows/publish-vncode-baseline.yml",
        "src/test/java/com/vncode/app/ui/tnved/FashionTnvedBrowserTest.java",
        observer_path,
    }
    actual_fourth_delta = git(repo, "diff", "--name-only", FOURTH_ATTEMPT_SOURCE, HISTORICAL_SOURCE).decode().splitlines()
    require(set(actual_fourth_delta) == fourth_delta, "FINAL_CHANGE_NOT_NATIVE_FIXTURE_OBSERVER_ONLY")
    fourth_changed = fourth_attempt["changedPaths"]
    require(len(fourth_changed) == 3 and {row["path"] for row in fourth_changed} == fourth_delta,
            "FOURTH_ATTEMPT_CHANGED_PATHS_MISMATCH")
    for row in fourth_changed:
        path = row["path"]
        if path == observer_path:
            require(row["previousSha256"] is None
                    and not git(repo, "ls-tree", FOURTH_ATTEMPT_SOURCE, "--", path).strip(),
                    "FOURTH_ATTEMPT_OBSERVER_NOT_NEW")
        else:
            require(row["previousSha256"] == sha(git_blob(repo, FOURTH_ATTEMPT_SOURCE, path)),
                    "FOURTH_ATTEMPT_PREVIOUS_PATH_HASH:" + path)
        require(row["sha256"] == sha(tree[path]), "FOURTH_ATTEMPT_CHANGED_PATH_HASH:" + path)
    fourth_unchanged = fourth_attempt["productionAndPackagingInputsUnchanged"]
    require(len(fourth_unchanged) == len(unchanged_paths)
            and {row["path"] for row in fourth_unchanged} == unchanged_paths,
            "FOURTH_ATTEMPT_UNCHANGED_INPUT_SET")
    for row in fourth_unchanged:
        path = row["path"]
        before = git(repo, "rev-parse", FOURTH_ATTEMPT_SOURCE + ":" + path).decode().strip()
        after = git(repo, "rev-parse", HISTORICAL_SOURCE + ":" + path).decode().strip()
        require(before == after == row["previousGitObject"] == row["gitObject"]
                and row["unchanged"] is True, "FOURTH_ATTEMPT_RUNTIME_OR_PACKAGING_CHANGED:" + path)
    fixture_path = "src/test/java/com/vncode/app/ui/tnved/FashionTnvedBrowserTest.java"
    old_fixture = git_blob(repo, FOURTH_ATTEMPT_SOURCE, fixture_path)
    old_show = b"stage[0].setScene(scene);stage[0].show();"
    isolated_show = (b"stage[0].setScene(scene);\n"
                     b"            stage[0].setAlwaysOnTop(true); // Keep this native fixture above CI runner/observer consoles.\n"
                     b"            stage[0].show();")
    require(old_fixture.count(old_show) == 1 and old_fixture.replace(old_show, isolated_show, 1) == tree[fixture_path],
            "NATIVE_FIXTURE_CHANGED_BEYOND_WINDOW_ISOLATION")
    observer = tree[observer_path].decode("utf-8")
    # Guards for this reviewed diagnostic, not a general PowerShell security scan.
    for forbidden in ("SetCursorPos", "SendInput", "mouse_event", "SwitchDesktop", "LockWorkStation",
                      "SetThreadExecutionState", "SetProcessDpiAwareness", "SetThreadDpiAwareness",
                      "SetValue(", "GetWindowText", "MainWindowTitle", "GetUserName", "CommandLine"):
        require(forbidden not in observer, "NATIVE_OBSERVER_UNREVIEWED_API:" + forbidden)
    workflow = tree[".github/workflows/publish-vncode-baseline.yml"].decode("utf-8")
    for required in (".\\mvnw.cmd -B clean verify",
                     "if($LASTEXITCODE -ne 0){throw 'Full Windows Java verification failed.'}",
                     "-WindowStyle Hidden", "'-MaxSeconds','11000'", "WaitForExit(10000)",
                     "Stop-Process -InputObject $nativeInputWatch -Force"):
        require(required in workflow, "NATIVE_OBSERVER_WORKFLOW_GUARD_MISSING:" + required)
    return {"receiptSha256": file_hash(file), "runtimeInputs": 109, "javaInputs": 84,
            "resourceInputs": 25, "runtimePlusPom": 110, "priorHandoverHashesMatched": 109,
            "requiredSourceCommitsIncluded": BASE_SOURCE_COMMITS, "previousReleaseRetained": True,
            "pomOnlyTwoVersionValuesChanged": True, "firstAttemptProvenance": first_attempt,
            "secondAttemptProvenance": second_attempt, "thirdAttemptProvenance": third_attempt,
            "fourthAttemptProvenance": fourth_attempt, "nativeFixtureOnlyWindowIsolationVerified": True,
            "readOnlyNativeObserverSourceSha256": sha(tree[observer_path])}, paths


def is_commit_pin(value):
    return isinstance(value, str) and re.fullmatch(r"[a-f0-9]{40}", value) is not None


def validate_certificate_configuration():
    require(SOURCE != PROGRESS_FIX_SOURCE and is_commit_pin(PROGRESS_FIX_SOURCE),
            "GUARD_FINAL_SOURCE_NOT_CONFIGURED")
    require(GUARD_DELTA_PATHS and len(set(GUARD_DELTA_PATHS)) == len(GUARD_DELTA_PATHS),
            "GUARD_EXACT_DELTA_NOT_CONFIGURED")
    require(re.fullmatch(r"[a-f0-9]{40}", SOURCE or "") is not None
            and SOURCE != HISTORICAL_SOURCE, "CERTIFICATE_SOURCE_PIN_NOT_CONFIGURED")
    require(type(BUILD) is int and BUILD > 0 and BUILD != HISTORICAL_BUILD,
            "CERTIFICATE_BUILD_PIN_NOT_CONFIGURED")
    for name, value in [("classes", EXPECTED_CLASSES), ("sourceFiles", EXPECTED_SOURCE_FILES),
                        ("runtimeFiles", EXPECTED_RUNTIME_FILES), ("javaInputs", EXPECTED_JAVA_INPUTS),
                        ("resourceInputs", EXPECTED_RESOURCE_INPUTS)]:
        require(type(value) is int and value > 0, "CERTIFICATE_EXACT_COUNT_NOT_CONFIGURED:" + name)
    require(EXPECTED_RUNTIME_FILES == EXPECTED_JAVA_INPUTS + EXPECTED_RESOURCE_INPUTS,
            "CERTIFICATE_RUNTIME_COUNTS_INCONSISTENT")
    require(CERTIFICATE_COMMITS and len(set(CERTIFICATE_COMMITS)) == len(CERTIFICATE_COMMITS)
            and all(re.fullmatch(r"[a-f0-9]{40}", c) for c in CERTIFICATE_COMMITS),
            "CERTIFICATE_FIX_COMMITS_NOT_CONFIGURED")
    require(SOURCE_COMMITS == BASE_SOURCE_COMMITS + list(CERTIFICATE_COMMITS),
            "CERTIFICATE_DERIVED_COMMIT_LIST_STALE")
    require(CERTIFICATE_DELTA_PATHS and len(set(CERTIFICATE_DELTA_PATHS)) == len(CERTIFICATE_DELTA_PATHS)
            and all(p and not p.startswith("/") and ".." not in pathlib.PurePosixPath(p).parts
                    for p in CERTIFICATE_DELTA_PATHS), "CERTIFICATE_DELTA_PATHS_NOT_CONFIGURED")
    require(CERTIFICATE_MANIFEST_PATH in CERTIFICATE_DELTA_PATHS,
            "CERTIFICATE_SUPPLEMENTAL_MANIFEST_PATH_NOT_CONFIGURED")
    require(CERTIFICATE_SENTINELS and all(re.fullmatch(r"[A-Za-z0-9_/$]+", name)
            and not name.startswith("/") and ".." not in name for name in CERTIFICATE_SENTINELS),
            "CERTIFICATE_RUNTIME_SENTINELS_NOT_CONFIGURED")
    require(all(name in SENTINELS for name in CERTIFICATE_SENTINELS),
            "CERTIFICATE_DERIVED_SENTINEL_LIST_STALE")


def verify_first_certificate_attempt(addition, repo, tree):
    attempt = addition["firstCertificateAttempt"]
    # This correction is historical; later guard runtime changes must not rewrite it.
    progress_tree = pinned_tree(repo, PROGRESS_FIX_SOURCE)
    expect_fields(attempt, {"sourceCommit": FIRST_CERTIFICATE_SOURCE,
                           "runId": FIRST_CERTIFICATE_BUILD, "workflowId": WORKFLOW,
                           "conclusion": "failure", "phase": "early Windows preflight",
                           "fullSuiteRan": False, "installerBuilt": False,
                           "failedReceiptSha256": "688fe5ac068dfb8a87ca7d4c855fdb6558ba2a24e499fe1360fa2b41dd53c422",
                           "diagnosticRunId": CERTIFICATE_DIAGNOSTIC_RUN,
                           "diagnosticApplicationSource": FIRST_CERTIFICATE_SOURCE,
                           "diagnosticStderrBytes": 392,
                           "diagnosticStderrSha256": "a3639af5e23464fc391dae127fd5493f02c8178068a35866d9c7fdd35dcd623f",
                           "correctionCommit": PROGRESS_FIX_SOURCE, "productionAndPackagingUnchanged": True,
                           "newUnfilteredFullWindowsRunRequired": True}, "FIRST_CERTIFICATE_ATTEMPT")
    expect_fields(attempt["java"], {"tests": 220, "passed": 219, "failures": 1,
                                   "errors": 0, "skipped": 0}, "FIRST_CERTIFICATE_PREFLIGHT_TOTALS")
    first_proof_file = WORK / "source-inclusion-certificate-first-attempt.json"
    require(first_proof_file.is_file() and file_hash(first_proof_file) == FIRST_CERTIFICATE_PROOF_SHA256,
            "FIRST_CERTIFICATE_SOURCE_PROOF_HASH")
    first_proof = json_bytes(first_proof_file.read_bytes())
    expect_fields(first_proof, {"sourceCommit": FIRST_CERTIFICATE_SOURCE,
                                "fullWindowsBuildRunId": FIRST_CERTIFICATE_BUILD},
                  "FIRST_CERTIFICATE_SOURCE_PROOF_IDENTITY")
    failed_receipt = WORK / "certificate-windows-first-failure.json"
    require(failed_receipt.is_file() and file_hash(failed_receipt) == attempt["failedReceiptSha256"],
            "FIRST_CERTIFICATE_FAILED_RECEIPT_HASH")
    require(is_ancestor(repo, FIRST_CERTIFICATE_SOURCE, PROGRESS_FIX_SOURCE), "FIRST_CERTIFICATE_SOURCE_NOT_ANCESTOR")
    test_path = "src/test/java/com/vncode/app/integration/znack/signature/CryptoProCertificateDiscoveryServiceTest.java"
    correction_paths = {
        "docs/validation/2026-11-09-cryptopro-certificate-discovery-handoff.md",
        CERTIFICATE_MANIFEST_PATH, CERTIFICATE_DIAGNOSTIC_PATH, test_path,
    }
    require(set(attempt["correctionDeltaPaths"]) == correction_paths
            and len(attempt["correctionDeltaPaths"]) == len(correction_paths),
            "FIRST_CERTIFICATE_CORRECTION_PATH_METADATA")
    actual = git(repo, "diff", "--name-only", FIRST_CERTIFICATE_SOURCE, PROGRESS_FIX_SOURCE).decode().splitlines()
    require(set(actual) == correction_paths, "FIRST_CERTIFICATE_CORRECTION_NOT_FIXTURE_DIAGNOSTIC_ONLY")
    for path in ("src/main", "pom.xml", "build.bat", "build.sh", ".mvn", "mvnw", "mvnw.cmd", "proguard.conf"):
        require(git(repo, "rev-parse", FIRST_CERTIFICATE_SOURCE + ":" + path)
                == git(repo, "rev-parse", PROGRESS_FIX_SOURCE + ":" + path),
                "FIRST_CERTIFICATE_CORRECTION_CHANGED_PRODUCTION:" + path)
    previous_test = git_blob(repo, FIRST_CERTIFICATE_SOURCE, test_path)
    old_fixture = b'String fixture = """\n                $script:fixtureCalls = 0'
    new_fixture = b'String fixture = """\n                $ProgressPreference = \'SilentlyContinue\'\n                $script:fixtureCalls = 0'
    require(previous_test.count(old_fixture) == 1
            and previous_test.replace(old_fixture, new_fixture, 1) == progress_tree[test_path],
            "CERTIFICATE_STDERR_ASSERTION_OR_FIXTURE_CHANGED_BEYOND_PROGRESS_PREFERENCE")
    diagnostic = json_bytes(progress_tree[CERTIFICATE_DIAGNOSTIC_PATH])
    expect_fields(diagnostic, {"schemaVersion": 1, "kind": "CERTIFICATE_WINDOWS_STDERR_DIAGNOSTIC",
                              "runId": CERTIFICATE_DIAGNOSTIC_RUN,
                              "checkedOutApplicationSourceCommit": FIRST_CERTIFICATE_SOURCE,
                              "diagnosticOnly": True, "applicationReleaseGate": False,
                              "originalFailedPreflight": FIRST_CERTIFICATE_BUILD,
                              "scriptReturnedBothExpectedCertificatesBeforeFinalAssertion": True,
                              "realCryptoProOrSellerOperations": False}, "CERTIFICATE_STDERR_DIAGNOSTIC")
    capture = diagnostic["capture"]
    expect_fields(capture, {"bytes": 392, "sha256": attempt["diagnosticStderrSha256"],
                           "clixmlObjectCount": 1, "allStreams": "progress", "errorRecords": 0,
                           "certificateOrPrivateKeyData": False}, "CERTIFICATE_PROGRESS_CAPTURE")
    raw = capture["text"].encode("utf-8")
    require(len(raw) == 392 and sha(raw) == capture["sha256"] and raw.startswith(b"#< CLIXML\r\n"),
            "CERTIFICATE_PROGRESS_RAW_BYTES")
    xml = ET.fromstring(raw.split(b"\r\n", 1)[1])
    namespace = "{http://schemas.microsoft.com/powershell/2004/04}"
    objects = xml.findall(namespace + "Obj")
    activities = [node.text for node in xml.iter(namespace + "AV")]
    require(len(objects) == 1 and objects[0].get("S") == "progress"
            and activities == ["Preparing modules for first use."], "CERTIFICATE_CAPTURE_NOT_ONLY_PREPARATION_PROGRESS")
    expect_fields(diagnostic["junit"], {"expectedRedCaptured": True, "failures": 1, "errors": 0,
                                        "tests": 1, "skipped": 0, "mavenExitCode": 1,
                                        "sourceCommit": FIRST_CERTIFICATE_SOURCE,
                                        "diagnosticOnly": True, "runtimeSourceChanged": False,
                                        "releaseGate": False}, "CERTIFICATE_DIAGNOSTIC_RED_SCOPE")



def verify_cancelled_pre_guard_attempt(addition, repo, tree):
    attempt = addition["cancelledPreGuardAttempt"]
    expect_fields(attempt, {"sourceCommit": PROGRESS_FIX_SOURCE,
                           "runId": PRE_GUARD_BUILD, "workflowId": WORKFLOW,
                           "conclusion": "cancelled", "fullSuiteStarted": True,
                           "fullSuiteAccepted": False, "installerBuilt": False,
                           "earlierTestResultsReused": False,
                           "sourceProofSha256": PRE_GUARD_PROOF_SHA256,
                           "guardCorrectionCommit": SOURCE,
                           "freshUnfilteredFullWindowsRunRequired": True},
                  "CANCELLED_PRE_GUARD_ATTEMPT")
    expect_fields(attempt["preflightJava"], {"tests": 220, "passed": 220,
                  "failures": 0, "errors": 0, "skipped": 0}, "PRE_GUARD_PREFLIGHT")
    expect_fields(attempt["freshJvmNativeJava"], {"tests": 3, "passed": 3,
                  "failures": 0, "errors": 0, "skipped": 0}, "PRE_GUARD_FRESH_NATIVE")
    proof_file = WORK / PRE_GUARD_PROOF_NAME
    require(proof_file.is_file() and not proof_file.is_symlink()
            and file_hash(proof_file) == PRE_GUARD_PROOF_SHA256, "PRE_GUARD_PROOF_HASH")
    prior = json_bytes(proof_file.read_bytes())
    expect_fields(prior, {"sourceCommit": PROGRESS_FIX_SOURCE,
                          "fullWindowsBuildRunId": PRE_GUARD_BUILD}, "PRE_GUARD_PROOF_IDENTITY")
    require(addition["firstCertificateAttempt"]
            == prior["postVerifiedCertificateChanges"]["firstCertificateAttempt"],
            "FIRST_CERTIFICATE_ATTEMPT_HISTORY_REWRITTEN_AFTER_GUARD")
    require(is_ancestor(repo, PROGRESS_FIX_SOURCE, SOURCE), "GUARD_BASE_NOT_ANCESTOR")
    actual = git(repo, "diff", "--name-only", PROGRESS_FIX_SOURCE, SOURCE).decode().splitlines()
    require(set(actual) == set(GUARD_DELTA_PATHS), "GUARD_EXACT_DELTA_MISMATCH")
    require(len(attempt["guardDeltaPaths"]) == len(GUARD_DELTA_PATHS)
            and set(attempt["guardDeltaPaths"]) == set(GUARD_DELTA_PATHS), "GUARD_DELTA_METADATA")
    actual_runtime = git(repo, "diff", "--name-only", PROGRESS_FIX_SOURCE, SOURCE,
                         "--", "src/main").decode().splitlines()
    require(set(actual_runtime) == set(GUARD_RUNTIME_PATHS), "GUARD_RUNTIME_DELTA_MISMATCH")
    require(len(attempt["guardRuntimePaths"]) == len(GUARD_RUNTIME_PATHS)
            and set(attempt["guardRuntimePaths"]) == set(GUARD_RUNTIME_PATHS),
            "GUARD_RUNTIME_METADATA")
    # The repository exposes the existing predicate for a read-only preview; it must not
    # change the owner-change/reset/transaction implementation merely to add the warning.
    repository_path = "src/main/java/com/vncode/app/integration/znack/ZnackRepository.java"
    before = git_blob(repo, PROGRESS_FIX_SOURCE, repository_path)
    after = tree[repository_path]
    previous_method = b"    private static boolean signerChanged(Settings previous, Settings next) {"
    current_method = b"    public static boolean signerChanged(Settings previous, Settings next) {"
    documentation = b"    /**\n     * Read-only preview of the exact signer-change predicate used by certificate persistence.\n     * UI confirmation must not substitute account names/INN for this stored certificate identity.\n     * This preview neither authenticates the certificate nor bypasses the transaction's scope,\n     * active-operation or optimistic settings checks.\n     */\n"
    require(before.count(previous_method) == 1
            and before.replace(previous_method, documentation + current_method, 1) == after,
            "GUARD_REPOSITORY_RESET_OR_SIGNER_POLICY_CHANGED")


def verify_inclusion(file, repo, tree, archived):
    validate_certificate_configuration()
    proof = json_bytes(file.read_bytes())
    expect_fields(proof, {"version": VERSION, "sourceCommit": SOURCE}, "SOURCE_INCLUSION_IDENTITY")
    require(proof["previousRelease"]["sourceCommit"] == PREVIOUS_SOURCE,
            "INCLUSION_BASELINE_MISMATCH")
    require(is_ancestor(repo, HISTORICAL_SOURCE, SOURCE), "VERIFIED_CANDIDATE_NOT_ANCESTOR")
    require({row["commit"] for row in proof["includedUnreleasedChanges"]} == set(SOURCE_COMMITS),
            "INCLUSION_REQUIRED_COMMITS_MISMATCH")
    for commit in SOURCE_COMMITS:
        require(is_ancestor(repo, commit, SOURCE), "INCLUSION_SOURCE_NOT_ANCESTOR:" + commit)

    historical_file = WORK / HISTORICAL_PROOF_NAME
    require(historical_file.is_file() and not historical_file.is_symlink()
            and file_hash(historical_file) == HISTORICAL_PROOF_SHA256,
            "HISTORICAL_0F5_PROOF_HASH_MISMATCH")
    historical_tree = pinned_tree(repo, HISTORICAL_SOURCE)
    require(len(historical_tree) == HISTORICAL_SOURCE_FILES, "HISTORICAL_0F5_SOURCE_COUNT")
    # These are historical Git/source guards, not another claim to inspect the old ZIP.
    historical_result, historical_runtime_paths = verify_historical_inclusion(
        historical_file, repo, historical_tree, historical_tree)
    historical_proof = json_bytes(historical_file.read_bytes())
    for name in ("firstAttemptProvenance", "secondAttemptProvenance", "thirdAttemptProvenance",
                 "fourthAttemptProvenance"):
        require(proof[name] == historical_proof[name], "HISTORICAL_PROVENANCE_REWRITTEN:" + name)

    addition = proof["postVerifiedCertificateChanges"]
    expect_fields(addition, {"baseSourceCommit": HISTORICAL_SOURCE,
                             "baseFullWindowsBuildRunId": HISTORICAL_BUILD,
                             "baseFullWindowsBuildOutcome": "success",
                             "sourceCommit": SOURCE, "fullWindowsBuildRunId": BUILD,
                             "earlierTestResultsReused": False,
                             "freshFullWindowsSuiteRerunRequired": True},
                  "POST_VERIFIED_CERTIFICATE_PROVENANCE")
    require(addition["certificateFixCommits"] == list(CERTIFICATE_COMMITS),
            "CERTIFICATE_REVIEWED_COMMITS_MISMATCH")
    verify_first_certificate_attempt(addition, repo, tree)
    verify_cancelled_pre_guard_attempt(addition, repo, tree)
    actual_delta = git(repo, "diff", "--name-only", HISTORICAL_SOURCE, SOURCE).decode().splitlines()
    require(set(actual_delta) == set(CERTIFICATE_DELTA_PATHS), "CERTIFICATE_EXACT_DELTA_MISMATCH")
    changes = addition["changedPaths"]
    require(len(changes) == len(CERTIFICATE_DELTA_PATHS)
            and {row["path"] for row in changes} == set(CERTIFICATE_DELTA_PATHS),
            "CERTIFICATE_DELTA_MANIFEST_SET_MISMATCH")
    for row in changes:
        path = row["path"]
        require(path in tree, "CERTIFICATE_DELTA_DELETION_NOT_REVIEWED:" + path)
        if path in historical_tree:
            require(row["previousSha256"] == sha(historical_tree[path]),
                    "CERTIFICATE_PREVIOUS_SOURCE_HASH:" + path)
        else:
            require(row["previousSha256"] is None, "CERTIFICATE_NEW_FILE_HAS_PREVIOUS_HASH:" + path)
        require(row["sha256"] == sha(tree[path]), "CERTIFICATE_SOURCE_HASH:" + path)
        text_equivalence(archived[path], tree[path], "CERTIFICATE_ARCHIVED_SOURCE:" + path)

    supplemental = addition["supplementalManifest"]
    expect_fields(supplemental, {"path": CERTIFICATE_MANIFEST_PATH,
                                 "sha256": sha(tree[CERTIFICATE_MANIFEST_PATH])},
                  "CERTIFICATE_SUPPLEMENTAL_MANIFEST_HASH")
    manifest = json_bytes(tree[CERTIFICATE_MANIFEST_PATH])
    expect_fields(manifest, {"schemaVersion": 1, "kind": "CERTIFICATE_DISCOVERY_SOURCE_MANIFEST",
                             "baseSourceCommit": HISTORICAL_SOURCE}, "CERTIFICATE_MANIFEST_IDENTITY")
    inputs = manifest["inputs"]
    require(set(inputs) == set(CERTIFICATE_DELTA_PATHS) - {CERTIFICATE_MANIFEST_PATH},
            "CERTIFICATE_SUPPLEMENTAL_INPUT_SET")
    require(all(inputs[path] == sha(tree[path]) for path in inputs),
            "CERTIFICATE_SUPPLEMENTAL_INPUT_HASH")

    # Certificate code changes main runtime intentionally. Keep POM/build/wrappers fixed,
    # but never pretend the complete main tree is still byte-identical to old 026/0f5.
    fixed_packaging = ("pom.xml", "build.bat", "build.sh", ".mvn", "mvnw", "mvnw.cmd", "proguard.conf")
    for path in fixed_packaging:
        require(git(repo, "rev-parse", HISTORICAL_SOURCE + ":" + path)
                == git(repo, "rev-parse", SOURCE + ":" + path),
                "CERTIFICATE_UNREVIEWED_PACKAGING_CHANGE:" + path)
    for path in ("src/test/java/com/vncode/app/ui/tnved/FashionTnvedBrowserTest.java",
                 "tools/diagnostics/windows-input-observer.ps1"):
        require(tree[path] == historical_tree[path],
                "CERTIFICATE_CHANGE_ALTERED_VERIFIED_NATIVE_INPUT_CONTROL:" + path)
    current_workflow = tree[".github/workflows/publish-vncode-baseline.yml"].decode("utf-8")
    for required in (".\\mvnw.cmd -B clean verify",
                     "if($LASTEXITCODE -ne 0){throw 'Full Windows Java verification failed.'}",
                     "-WindowStyle Hidden", "'-MaxSeconds','11000'", "WaitForExit(10000)",
                     "Stop-Process -InputObject $nativeInputWatch -Force"):
        require(required in current_workflow, "CURRENT_NATIVE_WORKFLOW_GUARD_MISSING:" + required)
    require(any(p.startswith("src/main/") for p in actual_delta),
            "CERTIFICATE_RUNTIME_CHANGE_MISSING")

    paths = git(repo, "diff", "--name-only", PREVIOUS_SOURCE, SOURCE, "--", "src/main").decode().splitlines()
    require(len(paths) == EXPECTED_RUNTIME_FILES
            and sum(p.startswith("src/main/java/") for p in paths) == EXPECTED_JAVA_INPUTS
            and sum(p.startswith("src/main/resources/") for p in paths) == EXPECTED_RESOURCE_INPUTS,
            "INCLUSION_RUNTIME_COUNT_MISMATCH")
    require(set(historical_runtime_paths) <= set(paths), "ORIGINAL_RUNTIME_PATHS_REMOVED_FROM_CURRENT_DELTA")
    rows = proof["runtimeDiff"]["inputs"]
    require(len(rows) == EXPECTED_RUNTIME_FILES and {row["path"] for row in rows} == set(paths),
            "INCLUSION_RUNTIME_SET_MISMATCH")
    for row in rows:
        path = row["path"]
        require(row["sha256"] == sha(tree[path]), "INCLUSION_RUNTIME_SOURCE_HASH:" + path)
        text_equivalence(archived[path], tree[path], "INCLUSION_ARCHIVED_SOURCE:" + path)
        require(row.get("priorHandoverMatch") is True, "INCLUSION_PRIOR_HANDOVER_NOT_MATCHED")
    coverage = proof["priorHandoverCoverage"]
    require(coverage["matchingRuntimeFiles"] == coverage["coveredRuntimeFiles"] == EXPECTED_RUNTIME_FILES
            and not coverage["missing"] and not coverage["mismatches"], "INCLUSION_PRIOR_HANDOVER_COVERAGE")
    prior_receipts = historical_proof["priorHandoverCoverage"]["receipts"]
    current_receipts = coverage["receipts"]
    expected_receipts = {r["path"]: r for r in prior_receipts}
    expected_receipts[CERTIFICATE_MANIFEST_PATH] = {"path": CERTIFICATE_MANIFEST_PATH,
        "sha256": supplemental["sha256"], "manifestField": "inputs"}
    require(len(current_receipts) == len(expected_receipts)
            and {r["path"] for r in current_receipts} == set(expected_receipts),
            "CERTIFICATE_COMBINED_HANDOVER_RECEIPT_SET")
    manifests = {}
    for receipt in current_receipts:
        expected = expected_receipts[receipt["path"]]
        expect_fields(receipt, {k: expected[k] for k in ("path", "sha256", "manifestField")},
                      "CERTIFICATE_COMBINED_HANDOVER_METADATA")
    for receipt in prior_receipts:
        raw = tree[receipt["path"]]
        require(raw == historical_tree[receipt["path"]] and sha(raw) == receipt["sha256"],
                "HISTORICAL_HANDOVER_RECEIPT_REWRITTEN")
        values = json_bytes(raw)
        for part in receipt["manifestField"].split("."):
            values = values[part]
        manifests.update(values)
    manifests.update(inputs)  # Only the exact declared certificate delta may supersede old hashes.
    require(all(manifests.get(path) == sha(tree[path]) for path in paths),
            "INCLUSION_COMBINED_HANDOVER_HASHES")
    require(proof["pom"]["sha256"] == sha(tree["pom.xml"]), "INCLUSION_POM_HASH")
    text_equivalence(archived["pom.xml"], tree["pom.xml"], "INCLUSION_POM_ARCHIVE")
    return {"receiptSha256": file_hash(file), "runtimeInputs": EXPECTED_RUNTIME_FILES,
            "javaInputs": EXPECTED_JAVA_INPUTS, "resourceInputs": EXPECTED_RESOURCE_INPUTS,
            "runtimePlusPom": EXPECTED_RUNTIME_FILES + 1,
            "priorHandoverHashesMatched": EXPECTED_RUNTIME_FILES,
            "requiredSourceCommitsIncluded": SOURCE_COMMITS, "previousReleaseRetained": True,
            "pomOnlyTwoVersionValuesChanged": True,
            "historical0f5SourceCoverage": {"sourceCommit": HISTORICAL_SOURCE,
                "fullBuildRun": HISTORICAL_BUILD, "proofSha256": HISTORICAL_PROOF_SHA256,
                "runtimeInputs": historical_result["runtimeInputs"],
                "scope": "Historical Git/provenance guards; not a new inspection of old ZIP bytes."},
            "firstAttemptProvenance": historical_result["firstAttemptProvenance"],
            "secondAttemptProvenance": historical_result["secondAttemptProvenance"],
            "thirdAttemptProvenance": historical_result["thirdAttemptProvenance"],
            "fourthAttemptProvenance": historical_result["fourthAttemptProvenance"],
            "postVerifiedCertificateChanges": addition,
            "firstCertificateFailureAndProgressDiagnosticValidated": True,
            "cancelledPreGuardAttemptAndExactGuardDeltaValidated": True}, paths


def verify_portable(file, tree, changed_paths, linux_reference):
    with zipfile.ZipFile(file) as portable:
        files = zip_files(portable)
        jars = [name for name in files if name.endswith(f"/app/VNcode-{VERSION}.jar")]
        require(len(jars) == 1, "PORTABLE_APPLICATION_JAR_NOT_UNIQUE")
        jar_path = jars[0]
        app_prefix = jar_path.rsplit("/", 1)[0] + "/"
        require(jar_path == f"VN code/app/VNcode-{VERSION}.jar", "PORTABLE_PRODUCTION_APP_PATH")
        cfg_path = app_prefix + "VN code.cfg"
        require(cfg_path in files, "PORTABLE_PRODUCTION_CONFIG_MISSING")
        cfg = portable.read(files[cfg_path]).decode("utf-8-sig").replace("\r\n", "\n")
        require(re.search(r"(?m)^app\.mainclass=com\.vncode\.app\.Launcher$", cfg) is not None,
                "PORTABLE_MAIN_CLASS_NOT_PRODUCTION_LAUNCHER")
        require(f"VNcode-{VERSION}.jar" in cfg, "PORTABLE_CONFIG_WRONG_JAR_VERSION")
        require(all(token not in cfg.lower() for token in ["znack-registration-test", "vncode.appdata.dir=",
                                                           "-dvncode.data.profile=", "license-test", "test-classes"]),
                "PORTABLE_CONFIG_HAS_TEST_PROFILE")
        jar_bytes = portable.read(files[jar_path])
        with zipfile.ZipFile(io.BytesIO(jar_bytes)) as jar:
            members = zip_files(jar)
            classes = sorted(name for name in members if name.endswith(".class"))
            require(len(classes) == EXPECTED_CLASSES, "APPLICATION_CLASS_COUNT_MISMATCH")
            require(all(name.startswith("com/vncode/app/") for name in classes), "NON_APPLICATION_CLASS_IN_APP_JAR")
            main_stems = {p[len("src/main/java/"):-5] for p in tree if p.startswith("src/main/java/") and p.endswith(".java")}
            test_stems = {p[len("src/test/java/"):-5] for p in tree if p.startswith("src/test/java/") and p.endswith(".java")}
            forbidden_stems = test_stems - main_stems
            for name in classes:
                stem = name[:-6].split("$", 1)[0]
                require(stem not in forbidden_stems and not name.startswith(("org/junit/", "org/mockito/",
                        "com/vncode/app/testsupport/", "com/vncode/app/performance/")), "TEST_FIXTURE_CLASS_IN_APP_JAR")
            class_hashes = {}
            for name in classes:
                data = jar.read(members[name])
                require(data[:4] == b"\xca\xfe\xba\xbe" and int.from_bytes(data[6:8], "big") == 69,
                        "APPLICATION_CLASS_NOT_JAVA_25")
                class_hashes[name] = sha(data)
            required_classes = ["com/vncode/app/" + name + ".class" for name in SENTINELS]
            require(all(name in members for name in required_classes), "REQUIRED_HELPER_UI_EPOCH_CLASS_MISSING")
            props = properties(jar.read(members["app.properties"]))
            expect_fields(props, {"app.name": "VN code", "app.version": VERSION,
                                  "app.update.url": UPDATE_URL, "app.update.public-key": "",
                                  "app.update.publisher": ""}, "APPLICATION_PROPERTIES")
            require(not any("${" in value for value in props.values()), "UNFILTERED_APPLICATION_PROPERTY")
            attributes = manifest_fields(jar.read(members["META-INF/MANIFEST.MF"]))
            require(attributes.get("Main-Class") == "com.vncode.app.Launcher", "JAR_MAIN_CLASS_NOT_PRODUCTION")
            classpath = attributes.get("Class-Path", "").split()
            require(bool(classpath) and len(set(classpath)) == len(classpath), "JAR_DEPENDENCY_CLASSPATH_INVALID")
            for dependency in classpath:
                require(dependency.startswith("lib/") and ".." not in pathlib.PurePosixPath(dependency).parts
                        and app_prefix + dependency in files, "PORTABLE_RUNTIME_DEPENDENCY_MISSING")
                require(not any(token in dependency.lower() for token in ["junit", "mockito", "mockwebserver",
                                                                          "opentest4j", "apiguardian"]),
                        "TEST_DEPENDENCY_IN_PRODUCTION_CLASSPATH")
            pom_properties = properties(jar.read(members["META-INF/maven/com.vncode/VNcode/pom.properties"]))
            expect_fields(pom_properties, {"groupId": "com.vncode", "artifactId": "VNcode", "version": VERSION}, "EMBEDDED_MAVEN_VERSION")
            resource_checks = []
            for source_path in changed_paths:
                if not source_path.startswith("src/main/resources/"):
                    continue
                name = source_path[len("src/main/resources/"):]
                require(name in members, "CHANGED_PACKAGED_RESOURCE_MISSING:" + name)
                data = jar.read(members[name])
                match = text_equivalence(data, tree[source_path], "PACKAGED_RESOURCE:" + name)
                resource_checks.append({"path": source_path, "sha256": sha(data),
                                        "gitSha256": sha(tree[source_path]), "comparison": match})
            require(len(resource_checks) == EXPECTED_RESOURCE_INPUTS, "CHANGED_RESOURCE_COUNT_MISMATCH")
            comparison = {"status": "not-available", "classByteEqualityRequired": False}
            if linux_reference.is_file():
                reference_hash = file_hash(linux_reference)
                comparison["referenceJarSha256"] = reference_hash
                if reference_hash != LINUX_REFERENCE_SHA:
                    comparison["status"] = "reference-not-the-frozen-candidate-not-compared"
                else:
                    with zipfile.ZipFile(linux_reference) as reference:
                        reference_classes = {name: sha(reference.read(name)) for name in reference.namelist() if name.endswith(".class")}
                    common = set(class_hashes) & set(reference_classes)
                    identical = sum(class_hashes[name] == reference_classes[name] for name in common)
                    comparison.update({"status": "compared-informationally", "comparedClasses": len(common),
                                       "identicalClassBytes": identical, "differentClassBytes": len(common) - identical,
                                       "sameClassFileSet": set(class_hashes) == set(reference_classes),
                                       "note": "Compiler vendor/patch byte differences do not fail source/payload verification."})
            java_release = [name for name in files if name.endswith("/runtime/release")]
            java_version = None
            if len(java_release) == 1:
                release_text = portable.read(files[java_release[0]]).decode("utf-8")
                match = re.search(r'(?m)^JAVA_VERSION="([0-9A-Za-z.+_-]+)"$', release_text)
                java_version = match.group(1) if match else None
            return {"jar": jar_path, "jarSha256": sha(jar_bytes), "classes": len(classes), "javaMajor": 69,
                    "version": VERSION, "productionMainClass": "com.vncode.app.Launcher",
                    "productionLauncherProfile": True, "fixtureClassesAbsent": True,
                    "defaultUpdateSource": UPDATE_URL, "requiredSentinelClasses": required_classes,
                    "sentinelClassSha256": {name: class_hashes[name] for name in required_classes},
                    "changedResourcesChecked": len(resource_checks), "changedResources": resource_checks,
                    "runtimeDependenciesPresent": len(classpath), "bundledJavaVersion": java_version,
                    "linuxReferenceComparison": comparison}


def verify_xml(file, directory, info):
    with zipfile.ZipFile(file) as archive:
        files = zip_files(archive)
        suites = []
        for name, member in files.items():
            if pathlib.PurePosixPath(name).name.startswith("TEST-") and name.endswith(".xml"):
                suite = ET.fromstring(archive.read(member))
                require(suite.tag == "testsuite", "UNEXPECTED_SUREFIRE_XML_ROOT")
                suites.append(suite)
        require(len(suites) == EXPECTED_JAVA_SUITES, "EXACT_SUREFIRE_SUITE_COUNT_REQUIRED")
        names = [suite.attrib.get("name") for suite in suites]
        require(len(names) == len(set(names)), "DUPLICATE_SUREFIRE_SUITE")
        for suite in suites:
            cases = suite.findall("testcase")
            require(len(cases) == int(suite.attrib.get("tests", 0)), "XML_CASE_COUNT_MISMATCH:" + str(suite.attrib.get("name")))
            for tag, attribute in [("failure", "failures"), ("error", "errors"), ("skipped", "skipped")]:
                require(sum(case.find(tag) is not None for case in cases) == int(suite.attrib.get(attribute, 0)),
                        "XML_CASE_STATUS_COUNTER_MISMATCH:" + str(suite.attrib.get("name")))
        totals = {key: sum(int(suite.attrib.get(key, 0)) for suite in suites)
                  for key in ["tests", "failures", "errors", "skipped"]}
        expect_fields(info["verification"]["java"], EXPECTED_JAVA_SUMMARY, "EXACT_BUILD_JAVA_SUMMARY")
        require(totals == {key: info["verification"]["java"][key] for key in totals}, "XML_BUILD_TOTALS_MISMATCH")
        expect_fields(totals, {key: EXPECTED_JAVA_SUMMARY[key] for key in totals}, "EXACT_XML_JAVA_TOTALS")
        by_name = {suite.attrib["name"]: suite for suite in suites}
        certificate_suites = {}
        for name, count in EXPECTED_CERTIFICATE_SUITES.items():
            require(name in by_name, "CERTIFICATE_SUREFIRE_SUITE_MISSING:" + name)
            suite = by_name[name]
            require(len(suite.findall("testcase")) == count
                    and int(suite.attrib.get("tests", 0)) == count,
                    "CERTIFICATE_SUITE_CASE_COUNT_MISMATCH:" + name)
            require(all(case.find(tag) is None for case in suite.findall("testcase")
                        for tag in ("failure", "error", "skipped")), "CERTIFICATE_SUITE_NOT_ALL_PASSED:" + name)
            certificate_suites[name] = {"tests": count, "passed": count, "failures": 0, "errors": 0, "skipped": 0}
        skipped = []
        stress = []
        for suite in suites:
            for case in suite.findall("testcase"):
                if case.find("skipped") is not None:
                    skipped.append({"suite": suite.attrib.get("name"), "case": case.attrib.get("name")})
                name = case.attrib.get("name", "")
                for wanted in STRESS:
                    if name == wanted or name.startswith(wanted + "("):
                        require(all(case.find(tag) is None for tag in ["skipped", "failure", "error"]), "TEN_THOUSAND_CASE_NOT_PASSED")
                        stress.append({"method": wanted, "seconds": float(case.attrib.get("time", 0)), "passed": True})
        require(collections.Counter(row["method"] for row in stress) == collections.Counter(STRESS), "THREE_TEN_THOUSAND_CASES_MISSING")
        require(collections.Counter((row["suite"], row["case"]) for row in skipped)
                == collections.Counter(EXPECTED_OS_SKIPS), "EXACT_OS_SKIP_IDENTITIES_REQUIRED")
        for wanted in ["native-smoke.json", "gs1-smoke.json", "side-by-side-smoke.json",
                       "upgrade-baseline-smoke.json", "previous-baseline.json"]:
            matches = [name for name in files if pathlib.PurePosixPath(name).name == wanted]
            require(len(matches) == 1 and archive.read(files[matches[0]]) == (directory / wanted).read_bytes(),
                    "INNER_OUTER_SMOKE_BYTES_MISMATCH:" + wanted)
        return {"suites": len(suites), "totals": totals, "skippedCases": skipped,
                "tenThousandStressCases": stress, "innerSmokeCopiesMatchOuterFiles": True,
                "requiredCertificateSuites": certificate_suites, "exactFrozenSourceSuiteVerified": True}


def verify_remote_metadata(release_file, run_file, local):
    result = {"releaseDigestVerification": "not-provided", "runStatusVerification": "not-provided",
              "networkCalls": False}
    if release_file:
        release = json_bytes(release_file.read_bytes())
        expect_fields(release, {"id": RELEASE_ID, "tag_name": "v" + VERSION,
                                "target_commitish": SOURCE, "prerelease": False}, "SAVED_RELEASE_METADATA")
        assets = release["assets"]
        require(len(assets) == 16 and {asset["name"] for asset in assets} == set(ASSETS), "REMOTE_16_ASSET_SET_MISMATCH")
        for asset in assets:
            name = asset["name"]
            expect_fields(asset, {"state": "uploaded", "size": local[name]["bytes"],
                                  "digest": local[name]["digest"]}, "REMOTE_ASSET_DIGEST:" + name)
        result["releaseDigestVerification"] = "all-16-saved-metadata-digests-match-local-bytes"
        result["releaseId"] = RELEASE_ID
        result["releaseDraft"] = release["draft"]
    if run_file:
        run = json_bytes(run_file.read_bytes())
        expect_fields(run, {"id": BUILD, "head_sha": SOURCE, "workflow_id": WORKFLOW,
                            "status": "completed", "conclusion": "success"}, "SAVED_RUN_METADATA")
        require(run["repository"]["full_name"] == run["head_repository"]["full_name"] == REPOSITORY,
                "SAVED_RUN_REPOSITORY_MISMATCH")
        result["runStatusVerification"] = "saved-completed-success-metadata-matches-pin"
    return result


def verify(args):
    validate_certificate_configuration()
    repo = args.repo.resolve()
    directory = args.artifact_dir.resolve()
    require(directory.is_dir(), "ARTIFACT_DIRECTORY_MISSING")
    require({file.name for file in directory.iterdir()} == set(ASSETS), "EXACT_16_ARTIFACT_FILES_REQUIRED")
    local = {}
    initial_stats = {}
    for name in ASSETS:
        file = directory / name
        details = file.lstat()
        require(stat.S_ISREG(details.st_mode) and not file.is_symlink() and details.st_size > 0,
                "ASSET_NOT_NONEMPTY_REGULAR_FILE:" + name)
        digest = file_hash(file)
        local[name] = {"bytes": details.st_size, "sha256": digest, "digest": "sha256:" + digest}
        initial_stats[name] = (details.st_size, details.st_mtime_ns)
    load = lambda name: json_bytes((directory / name).read_bytes())
    manifest = load("asset-manifest.json")
    expect_fields(manifest, {"schema": 1, "version": VERSION, "sourceCommit": SOURCE}, "ASSET_MANIFEST")
    require(set(manifest["files"]) == set(REQUIRED), "MANIFEST_REQUIRED_11_FILE_SET")
    for name, expected in manifest["files"].items():
        require(expected == {key: local[name][key] for key in ["bytes", "sha256"]}, "MANIFEST_HASH_SIZE:" + name)
    sums = checksums((directory / "SHA256SUMS").read_bytes())
    require(set(sums) == set(REQUIRED + ["asset-manifest.json"]), "SHA256SUMS_EXACT_12_MEMBERS_REQUIRED")
    require(all(local[name]["sha256"] == digest for name, digest in sums.items()), "SHA256SUMS_DIGEST_MISMATCH")
    exe_name = f"VN-code-{VERSION}-Windows-x64.exe"
    require(checksums((directory / f"VN-code-{VERSION}-Windows-x64.sha256").read_bytes()) ==
            {exe_name: local[exe_name]["sha256"]}, "INSTALLER_SHA256_FILE_MISMATCH")
    info = load("build-info.json")
    expect_fields(info, {"version": VERSION, "sourceCommit": SOURCE, "runId": BUILD,
                        "workflow": "publish-vncode-baseline.yml"}, "BUILD_INFO_IDENTITY")
    expect_fields(info["verification"], {"fullJavaSuiteRun": True, "nativeSmokeRun": True,
                                         "baselineUpgradeRun": True}, "BUILD_REQUIRED_VERIFICATION")
    java = info["verification"]["java"]
    expect_fields(java, EXPECTED_JAVA_SUMMARY, "EXACT_BUILD_JAVA_SUMMARY")
    tree = pinned_tree(repo)
    require(len(tree) == EXPECTED_SOURCE_FILES, "PINNED_GIT_SOURCE_FILE_COUNT_MISMATCH")
    with zipfile.ZipFile(directory / f"VN-code-{VERSION}-source.zip") as archive:
        members = zip_files(archive)
        prefix = f"VN-code-{VERSION}-source/"
        require(all(name.startswith(prefix) for name in members), "SOURCE_ARCHIVE_UNEXPECTED_PREFIX")
        archived = {name[len(prefix):]: archive.read(member) for name, member in members.items()}
        require(set(archived) == set(tree), "SOURCE_ARCHIVE_GIT_FILE_SET_MISMATCH")
        normalized = []
        for name, expected in tree.items():
            if text_equivalence(archived[name], expected, "SOURCE_ARCHIVE:" + name) != "exact":
                normalized.append(name)
    inclusion, changed_paths = verify_inclusion(args.source_inclusion, repo, tree, archived)
    notes_match = text_equivalence((directory / "release-notes.md").read_bytes(),
                                  tree[f"docs/releases/VN-code-{VERSION}.md"], "RELEASE_NOTES")
    smokes = verify_smokes(load, info, local, tree)
    runtime = verify_portable(directory / f"VN-code-{VERSION}-Windows-x64.zip", tree, changed_paths,
                              args.linux_reference)
    xml = verify_xml(directory / f"VN-code-{VERSION}-verification.zip", directory, info)
    with (directory / exe_name).open("rb") as stream:
        header = stream.read(64)
        require(header[:2] == b"MZ", "EXE_DOS_HEADER_INVALID")
        stream.seek(int.from_bytes(header[60:64], "little"))
        pe = stream.read(6)
        require(pe[:4] == b"PE\0\0" and int.from_bytes(pe[4:6], "little") == 0x8664,
                "EXE_NOT_WINDOWS_X64")
    with (directory / f"VN-code-{VERSION}-Windows-x64.msi").open("rb") as stream:
        require(stream.read(8) == bytes.fromhex("d0cf11e0a1b11ae1"), "MSI_COMPOUND_CONTAINER_INVALID")
    bundle = directory / f"VN-code-{VERSION}-history.bundle"
    git(repo, "bundle", "verify", str(bundle))
    heads = git(repo, "bundle", "list-heads", str(bundle)).decode().splitlines()
    bundle_retains_source = False
    for row in heads:
        commit = row.split()[0]
        if commit == SOURCE:
            bundle_retains_source = True
            break
        # A valid bundle can also contain newer refs absent from this local repo.
        # Do not mistake an unavailable unrelated ref for a failed source check.
        known = subprocess.run(["git", "-C", str(repo), "cat-file", "-e", commit + "^{commit}"],
                               capture_output=True).returncode == 0
        if known and is_ancestor(repo, SOURCE, commit):
            bundle_retains_source = True
            break
    require(bundle_retains_source, "HISTORY_BUNDLE_DOES_NOT_RETAIN_PINNED_SOURCE")
    metadata = verify_remote_metadata(args.release_json, args.run_json, local)
    for name, initial in initial_stats.items():
        after = (directory / name).lstat()
        require((after.st_size, after.st_mtime_ns) == initial, "ASSET_CHANGED_DURING_VERIFICATION:" + name)
    return {"kind": "PINNED_WINDOWS_PACKAGE_VERIFICATION", "result": "passed", "version": VERSION,
            "sourceCommit": SOURCE, "fullBuildRun": BUILD,
            "verifiedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "helperSha256": file_hash(pathlib.Path(__file__)), "sourceArchiveFilesChecked": len(tree),
            "textLineEndingsNormalizedForComparisonCount": len(normalized),
            "sourceArchiveOnlyExactOrUtf8CrlfDifferences": True, "sourceInclusion": inclusion,
            "manifestFilesChecked": len(REQUIRED), "sha256SumMembersChecked": len(sums),
            "all16LocalDigestsComputed": True, "assets": local, "runtime": runtime,
            "releaseNotesComparison": notes_match, "javaSuites": xml["suites"], "java": xml["totals"],
            "skippedCases": xml["skippedCases"], "tenThousandStressCases": xml["tenThousandStressCases"],
            "requiredCertificateSuites": xml["requiredCertificateSuites"],
            "exactFrozenSourceSuiteVerified": xml["exactFrozenSourceSuiteVerified"],
            "innerSmokeCopiesMatchOuterFiles": xml["innerSmokeCopiesMatchOuterFiles"], "smokes": smokes,
            "exeArchitecture": "windows-x64", "msiContainerVerified": True,
            "historyBundleVerified": True, "historyBundleRetainsSource": True,
            "externalMetadata": metadata, "networkCalls": False,
            "scope": "Local Windows artifact bytes, pinned Git source, production runtime and saved test evidence; no installer execution, Maven, seller API or publication.",
            "limitations": ["Compiler vendor/patch class-byte differences are informational.",
                            "No Authenticode verification or seller-machine execution.",
                            "Remote release digests and CI success require saved metadata inputs; publisher independently enforces the live build gate."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact-dir", type=pathlib.Path, default=WORK / "windows-artifact-certificate-guard")
    parser.add_argument("--repo", type=pathlib.Path, default=ROOT)
    parser.add_argument("--source-inclusion", type=pathlib.Path, default=WORK / "source-inclusion.json")
    parser.add_argument("--linux-reference", type=pathlib.Path, default=ROOT / "target/VNcode-1.6.7.jar")
    parser.add_argument("--release-json", type=pathlib.Path)
    parser.add_argument("--run-json", type=pathlib.Path)
    parser.add_argument("--receipt", type=pathlib.Path, default=WORK / "package-verification-certificate-guard.json")
    args = parser.parse_args()
    receipt_path = args.receipt.resolve()
    require(not receipt_path.is_relative_to(args.repo.resolve()), "RECEIPT_MUST_BE_OUTSIDE_REPOSITORY")
    require(not receipt_path.is_relative_to(args.artifact_dir.resolve()), "RECEIPT_MUST_BE_OUTSIDE_EXACT_ASSET_DIRECTORY")
    try:
        receipt = verify(args)
    except Exception as error:
        code = str(error) if isinstance(error, VerificationError) else type(error).__name__
        receipt = {"kind": "PINNED_WINDOWS_PACKAGE_VERIFICATION", "result": "failed",
                   "version": VERSION, "sourceCommit": SOURCE, "fullBuildRun": BUILD,
                   "verifiedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                   "failureCode": code, "networkCalls": False,
                   "scope": "Local verification failed; no payload/publication approval."}
    receipt_path.parent.mkdir(parents=True, exist_ok=True)
    receipt_path.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"result": receipt["result"], "receipt": str(receipt_path),
                      "receiptSha256": file_hash(receipt_path),
                      "failureCode": receipt.get("failureCode")}, ensure_ascii=False))
    return 0 if receipt["result"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
