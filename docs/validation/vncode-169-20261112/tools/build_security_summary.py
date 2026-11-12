#!/usr/bin/env python3
"""Build a sanitized release-security receipt from downloaded local evidence."""
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parent
OUT = ROOT.parent


def read(name):
    return json.loads((ROOT / name).read_text())


def write(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


details = read("comparison-details.json")
normalized = read("normalized-records.json")
gate = read("gate-counts.json")
policy = read("security-policy-hashes.json")
sources = {
    "reference": "38906a2108501029d9609ed1d071ee987c8b2637",
    "candidate": "4ef0f1e2b8ef7b28190de3034b0c67ebf615be0b",
}
runs = {side: read(side + "-workflow-sanitized.json") for side in sources}
for side, workflow in runs.items():
    assert workflow["head_sha"] == sources[side]
    assert workflow["workflow_id"] == 403818652
    assert workflow["status"] == "completed"
    assert workflow["conclusion"] == "failure"

artifacts = {side: [] for side in sources}
for side in sources:
    for path in sorted((ROOT / side).glob("*/artifact-metadata.json")):
        metadata = json.loads(path.read_text())
        assert metadata["apiDigestMatches"]
        archive = path.parent / "artifact.zip"
        assert hashlib.sha256(archive.read_bytes()).hexdigest() == metadata["archiveSha256"]
        for report in metadata["sarif"]:
            assert hashlib.sha256(Path(report["path"]).read_bytes()).hexdigest() == report["sha256"]
        artifacts[side].append(metadata)

raw_equal = {}
notifications = {side: [] for side in sources}
language_summary = {}
for language, comparison in details["languages"].items():
    reference = comparison["reference"]
    candidate = comparison["candidate"]
    a = json.loads(Path(reference["path"]).read_text())
    b = json.loads(Path(candidate["path"]).read_text())
    raw_equal[language] = [run["results"] for run in a["runs"]] == [run["results"] for run in b["runs"]]
    language_summary[language] = {
        "reference": reference,
        "candidate": candidate,
        "findings": comparison["findings"],
        "notifications": comparison["notifications"],
    }
    for side, report in (("reference", a), ("candidate", b)):
        for run in report["runs"]:
            for invocation in run.get("invocations", []):
                for kind in ("toolExecutionNotifications", "toolConfigurationNotifications"):
                    for notification in invocation.get(kind, []):
                        if notification.get("level", "warning") in ("warning", "error"):
                            notifications[side].append({
                                "language": language,
                                "kind": kind,
                                "level": notification.get("level", "warning"),
                                "id": notification.get("descriptor", {}).get("id"),
                                "message": notification.get("message", {}).get("text"),
                            })
    for side in sources:
        assert gate[side][language]["total"] == comparison[side]["count"]
        assert gate[side][language]["blocked"] == comparison[side]["blocked"]

summary = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "referenceRelease": "v1.6.8",
    "referenceRun": runs["reference"]["id"],
    "referenceSource": sources["reference"],
    "currentReleaseCandidate": "v1.6.9",
    "currentRun": runs["candidate"]["id"],
    "currentSource": sources["candidate"],
    "workflowId": 403818652,
    "method": "Download actual raw SARIF archives from both workflow runs, verify each archive against the GitHub API SHA-256 digest, then compare canonical normalized record multisets without deduplication. Finding records retain resolved rule ID, numeric security severity, level/kind, all primary and related physical/logical locations with exact regions, both fingerprint maps, whitespace-normalized text/markdown messages, complete codeFlows, thread-flow counts and stacks. Resolve artifact and query-pack rule references. Compare warning/error execution and configuration notifications separately. Preserve line movement as a record change.",
    "referenceSha256": details["languages"]["java-kotlin"]["reference"]["sha256"],
    "currentSha256": details["languages"]["java-kotlin"]["candidate"]["sha256"],
    "referenceCount": details["totals"]["reference"]["findings"],
    "currentCount": details["totals"]["candidate"]["findings"],
    "referenceBlocked": details["totals"]["reference"]["blocked"],
    "currentBlocked": details["totals"]["candidate"]["blocked"],
    "new": [],
    "resolved": [],
    "newFindingCount": details["newFindingCount"],
    "resolvedFindingCount": details["resolvedFindingCount"],
    "identicalNormalizedRecords": details["identicalNormalizedFindingRecords"],
    "rawResultArraysEqualByLanguage": raw_equal,
    "identicalNormalizedWarningErrorNotifications": details["identicalNormalizedWarningErrorNotifications"],
    "referenceNotifications": notifications["reference"],
    "currentNotifications": notifications["candidate"],
    "languages": language_summary,
    "blockedFinding": {
        "rule": "java/potentially-weak-cryptographic-algorithm",
        "securitySeverity": 7.5,
        "path": "src/main/java/com/vncode/app/integration/znack/signature/CryptoProSignedCertificateReader.java",
        "line": 71,
        "status": "Unchanged legacy finding in both actual SARIF reports",
    },
    "workflowConclusions": runs,
    "gatePolicy": {
        "description": "Count each result; block numeric security-severity >= 7 or missing, nonfinite or out-of-range severity; malformed or ambiguous rule references fail closed. Notifications do not enter finding/gate counts.",
        "localVerification": gate,
        "policyHashes": policy,
        "unchangedBetweenReferenceAndCandidate": policy["reference"] == policy["candidate"],
    },
    "scope": {
        "codeql": "CodeQL 2.27.3 security-extended; Java/Kotlin manual compile on Oracle Java 25 and JavaScript/TypeScript without build; SARIF upload disabled by workflow.",
        "coverageLimitation": "Both Java reports retain one java/diagnostics/extraction-errors error for Gs1SignatureInk.java. No assertion of complete Java extraction coverage.",
        "manualDispatchSkips": "OpenQodex and changed-dependencies jobs were skipped by workflow_dispatch conditions; neither contributes scan coverage in these runs.",
        "conclusion": "No new or resolved findings or warning/error notifications versus the published v1.6.8 source. Security workflow and Java gate remain failed; this is not an all-security-green result.",
    },
    "evidence": {
        "artifactManifest": str(OUT / "security-artifact-manifest.json"),
        "workflowMetadata": str(OUT / "security-workflow-final.json"),
        "comparisonDetails": str(ROOT / "comparison-details.json"),
        "normalizationScript": str(ROOT / "compare_sarif.py"),
        "normalizedRecordsLocalOnly": str(ROOT / "normalized-records.json"),
    },
}
assert summary["newFindingCount"] == 0 and summary["resolvedFindingCount"] == 0
assert all(raw_equal.values())
assert summary["identicalNormalizedRecords"]
assert summary["identicalNormalizedWarningErrorNotifications"]
write(OUT / "security-artifact-manifest.json", artifacts)
write(OUT / "security-workflow-final.json", runs)
write(OUT / "security-comparison.json", summary)
print(json.dumps({"path": str(OUT / "security-comparison.json"), "referenceCount": summary["referenceCount"], "currentCount": summary["currentCount"], "referenceBlocked": summary["referenceBlocked"], "currentBlocked": summary["currentBlocked"], "newFindingCount": summary["newFindingCount"], "resolvedFindingCount": summary["resolvedFindingCount"], "identicalNormalizedRecords": summary["identicalNormalizedRecords"]}))
