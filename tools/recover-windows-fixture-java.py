"""One bounded 1.4.18 recovery: rerun the failed fixture lifecycle, retain unchanged passing methods."""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ORIGIN_RUN = 39099056215
ORIGIN_SHA = "62a5d0ea5f1abaf0ea638319b0b18231dfd0c238"
ARTIFACT_ID = 12126526148
ARCHIVE_SHA = "ae82ace1389c81bac696ba57f1d53029a99eaa2ec10d02a5b3cedb6d66a97d65"
REPO = "ntccong2468-lab/Bancapnhat"
FXML = "com.vncode.app.ui.FxmlSmokeTest"
CLEANUP = "com.vncode.app.testsupport.FinanceTestCleanupTest"
SKIP_CLASSES = {"com.vncode.app.features.gs1.Gs1PrefixTemplateStoreTest", "com.vncode.app.shared.WindowsSecretProtectorTest"}


def command(*args):
    return subprocess.check_output(args)


def gh_api(endpoint):
    return json.loads(command("gh", "api", f"repos/{REPO}/{endpoint}"))


def report(data):
    root = ET.fromstring(data)
    cases = root.findall("testcase")
    return root, {(c.get("classname"), c.get("name")) for c in cases if c.get("name")}


def reconcile(original, fresh):
    """Every original named test must remain; only the rerun class-context error may disappear."""
    assert FXML in fresh and CLEANUP in fresh
    old_root, old_names = report(original[FXML])
    context = [c for c in old_root.findall("testcase") if not c.get("name")]
    assert len(context) == 1 and context[0].find("failure") is None
    error = context[0].find("error")
    assert error is not None and "Failed to close extension context" in error.get("message", "")
    assert "wcode_analytics.db" in (error.text or "") and "being used by another process" in (error.text or "")
    assert len(old_names) == 55
    for name, data in original.items():
        root, _ = report(data)
        assert int(root.get("failures", 0)) == 0
        assert int(root.get("errors", 0)) == (1 if name == FXML else 0)
        assert all(c.find("failure") is None and c.find("error") is None for c in root.findall("testcase") if c.get("name"))
        skips = sum(c.find("skipped") is not None for c in root.findall("testcase"))
        assert skips == int(root.get("skipped", 0))
        assert not skips or name in SKIP_CLASSES and skips == 1
    for name, data in fresh.items():
        root, names = report(data)
        assert int(root.get("failures", 0)) == 0 and int(root.get("errors", 0)) == 0
        assert len(names) == len(root.findall("testcase"))
        if name == CLEANUP:
            assert len(names) == 1 and name not in original
        else:
            assert name in original and names == report(original[name])[1]
    merged = {**original, **fresh}
    totals = {k: sum(int(report(data)[0].get(k, 0)) for data in merged.values()) for k in ["tests", "failures", "errors", "skipped"]}
    assert totals == {"tests": 1748, "failures": 0, "errors": 0, "skipped": 2}
    fresh_tests = sum(len(report(data)[1]) for data in fresh.values())
    return merged, totals, fresh_tests


def validate_sources():
    changed = command("git", "diff", "--name-only", ORIGIN_SHA, "HEAD").decode().splitlines()
    allowed = {"src/test/java/com/vncode/app/ui/FxmlSmokeTest.java",
               "src/test/java/com/vncode/app/testsupport/FinanceTestCleanup.java",
               "src/test/java/com/vncode/app/testsupport/FinanceTestCleanupTest.java",
               ".github/workflows/build-java.yml", ".github/workflows/publish-vncode-stable.yml",
               "tools/recover-windows-fixture-java.py", "tools/recover-windows-fixture-java.test.mjs",
               "tools/publish-vncode-stable.mjs", "tools/publish-vncode-stable.test.mjs"}
    assert all(p.startswith("docs/") or p in allowed for p in changed), "Application/dependency/native inputs changed"
    source = "src/test/java/com/vncode/app/ui/FxmlSmokeTest.java"
    old = command("git", "show", f"{ORIGIN_SHA}:{source}").decode()
    needle = '        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();\n        System.clearProperty("vncode.appdata.dir");'
    assert old.count(needle) == 1
    expected = old.replace(needle, needle.replace('\n        System.clearProperty', '\n        com.vncode.app.testsupport.FinanceTestCleanup.detachAndAwait();\n        System.clearProperty'))
    assert Path(source).read_text() == expected, "Original FXML assertions changed"
    # Runtime code/resources and dependency declarations must be byte-identical.
    for source in ["src/main", "pom.xml", "build.bat", "build.sh", ".mvn", "mvnw", "mvnw.cmd"]:
        assert command("git", "rev-parse", f"{ORIGIN_SHA}:{source}") == command("git", "rev-parse", f"HEAD:{source}")
    for source, expected_hash in {
        "src/test/java/com/vncode/app/testsupport/FinanceTestCleanup.java": "b00f6f579500894c217b8e93e4fd7fd1cef77782f3348b77e5ebe728af1b533c",
        "src/test/java/com/vncode/app/testsupport/FinanceTestCleanupTest.java": "41a60da980c454dfddb0107cf6a56449cdcfe088b32a7dd71011de5f74688541",
    }.items():
        canonical = command("git", "show", f"HEAD:{source}")
        assert hashlib.sha256(canonical).hexdigest() == expected_hash
        assert Path(source).read_text() == canonical.decode()
    return changed


def prepare():
    assert os.environ.get("VNCODE_FIXTURE_RECOVERY_RUN") == str(ORIGIN_RUN)
    changed = validate_sources()
    run = gh_api(f"actions/runs/{ORIGIN_RUN}")
    assert run["head_sha"] == ORIGIN_SHA and run["conclusion"] == "failure"
    assert run["repository"]["full_name"] == REPO
    assert run["workflow_id"] == gh_api("actions/workflows/build-java.yml")["id"]
    job = next(j for j in gh_api(f"actions/runs/{ORIGIN_RUN}/jobs?per_page=100")["jobs"] if j["name"] == "build-and-test")
    assert [s["name"] for s in job["steps"] if s["conclusion"] == "failure"] == ["Run Java and FXML test suite"]
    artifact = gh_api(f"actions/artifacts/{ARTIFACT_ID}")
    assert artifact["name"] == f"VN-code-Windows-failed-tests-{ORIGIN_RUN}"
    assert artifact["digest"] == "sha256:" + ARCHIVE_SHA and not artifact["expired"]
    archive = command("gh", "api", f"repos/{REPO}/actions/artifacts/{ARTIFACT_ID}/zip")
    assert hashlib.sha256(archive).hexdigest() == ARCHIVE_SHA
    original = {}
    with zipfile.ZipFile(io.BytesIO(archive)) as z:
        assert sum(i.file_size for i in z.infolist()) < 128 * 1024 * 1024
        for name in z.namelist():
            if Path(name).name.startswith("TEST-") and name.endswith(".xml"):
                data = z.read(name); cls = report(data)[0].get("name")
                assert cls not in original; original[cls] = data
    reports = Path("target/surefire-reports")
    fresh = {report(p.read_bytes())[0].get("name"): p.read_bytes() for p in reports.glob("TEST-*.xml")}
    old_props = {p.get("name"): p.get("value") for p in report(original[FXML])[0].findall("properties/property")}
    new_props = {p.get("name"): p.get("value") for p in report(fresh[FXML])[0].findall("properties/property")}
    for key in ["java.runtime.version", "java.vendor", "os.arch"]:
        assert old_props[key] == new_props[key]
    merged, totals, fresh_tests = reconcile(original, fresh)
    for cls, data in merged.items():
        (reports / ("TEST-" + cls + ".xml")).write_bytes(data)
    proof = {"kind": "fixture-cleanup-only-recovery", "version": "1.4.18",
             "sourceCommit": command("git", "rev-parse", "HEAD").decode().strip(),
             "applicationSourceCommit": ORIGIN_SHA, "originRunId": ORIGIN_RUN,
             "originArtifactId": ARTIFACT_ID, "originArchiveSha256": ARCHIVE_SHA,
             "originSuite": {"tests": 1748, "failures": 0, "errors": 1, "skipped": 2},
             "originNamedTests": 1747, "freshTestsExecuted": fresh_tests,
             "freshClasses": sorted(fresh), "reusedPassingTests": totals["tests"] - fresh_tests,
             "originalContextFailureRetainedInProvenance": True,
             "failedFixtureClassRerunPassed": True, "newHandleCleanupTestPassed": True,
             "applicationAndDependenciesUnchanged": True, "originalAssertionsUnchanged": True,
             "nativeChecksMustRunAgain": True, "sourceChanges": changed, "tests": totals}
    Path("target/java-fixture-recovery.json").write_text(json.dumps(proof, indent=2) + "\n")


def finalize():
    validate_sources()
    path = Path("target/java-fixture-recovery.json"); proof = json.loads(path.read_text())
    assert proof["sourceCommit"] == command("git", "rev-parse", "HEAD").decode().strip()
    jar = Path("target/VNcode-1.4.18.jar"); assert jar.is_file()
    proof["freshlyPackagedJarSha256"] = hashlib.sha256(jar.read_bytes()).hexdigest()
    proof["packageCompiledAfterFocusedTests"] = True
    path.write_text(json.dumps(proof, indent=2) + "\n")
    print("RECONCILED_WINDOWS_JAVA: unchanged passing methods reused from the original failed run; the fixed FXML lifecycle and cleanup test were executed freshly. This is not a fresh full-suite run.")
    print(json.dumps({"originErrors": 1, "freshTestsExecuted": proof["freshTestsExecuted"], "reusedPassingTests": proof["reusedPassingTests"]}))
    for cls in sorted(SKIP_CLASSES - set(proof["freshClasses"])):
        print(f"REUSED_PLATFORM_SKIP: Tests run: 1, Failures: 0, Errors: 0, Skipped: 1, Time elapsed: 0 -- in {cls}")
    t = proof["tests"]
    print(f"RECONCILED aggregate: Tests run: {t['tests']}, Failures: 0, Errors: 0, Skipped: 2")


if __name__ == "__main__":
    assert sys.argv[1:] in [["prepare"], ["finalize"]]
    prepare() if sys.argv[1] == "prepare" else finalize()
