"""Run actual-package audit plus independent in-memory corruption controls."""

from __future__ import annotations

import contextlib
import datetime as dt
import hashlib
import io
import json
import pathlib
import sqlite3
import subprocess
import warnings
import zipfile

from verify_motion import ROOT, SOURCE_COMMIT, MotionVerificationError, verify_motion


HERE = pathlib.Path(__file__).resolve().parent
ACTUAL = pathlib.Path("/workspace/release-1610-20261115/build/assets/VN-code-1.6.10-verification.zip")
SOURCE = pathlib.Path("/workspace/vncode-ios-motion-20261114")
SOURCE_FILES = {
    "tools/diagnostics/windows-motion-environment.ps1": "347039ea0ba1c2b24e9476aeeb069e4eb2ca6c4d63037e539115d890680c3a09",
    "tools/diagnostics/WindowsMotionPreferenceProbe.java": "d0477068a1b02779496b0532ba760ce1ac39f429cfbdf42f1d25f072add22775",
    "src/main/java/com/vncode/app/shared/ThemeService.java": "c6054ed71ce3fc11ba91c4ab863c9b76166b0767ca94906fe05d7c88334d8740",
    "src/main/java/com/vncode/app/shared/WorkspaceInteractionMotion.java": "3769f7bea8006d76117b75e0a9b8cc2094365c6a58009a41a01bbe4832576f10",
    "src/main/java/com/vncode/app/shared/WorkspaceMotion.java": "32871dad246b4e0947b409f389a2674f58f608df3fc7bcdfc15a486956311315",
    "src/main/java/com/vncode/app/config/Database.java": "0689d2b91b6957ed25142c495dc1b9d03f54b457d109579fc4612a8cd38d90d1",
    "src/main/java/com/vncode/app/shared/ConfigService.java": "933a3340ee9c39a8d7ad77de907e2b1ceef86545a83ada16d7436d43a7747d51",
}


def digest_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def packed(members, extra=()):
    stream = io.BytesIO()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_STORED) as archive:
            for name, raw in list(members.items()) + list(extra):
                archive.writestr(name, raw)
    stream.seek(0)
    return stream


def change_json(members, name, change):
    members = dict(members)
    path = ROOT + name
    obj = json.loads(members[path])
    change(obj)
    members[path] = json.dumps(obj).encode()
    return members


def change_db(members, name, statements):
    members = dict(members)
    # Deliberately corrupt only a memory database populated from ZIP member bytes.
    connection = sqlite3.connect(":memory:")
    try:
        copied = bytearray(members[name])
        # SQLite deserialize cannot open a WAL-mode image in :memory:. Change
        # only this control copy's read/write versions to rollback mode.
        copied[18:20] = b"\x01\x01"
        connection.deserialize(bytes(copied))
        for sql in statements:
            connection.execute(sql)
        connection.commit()
        members[name] = connection.serialize()
    finally:
        connection.close()
    return members


def main():
    before = digest_file(ACTUAL)
    actual = verify_motion(ACTUAL)
    with zipfile.ZipFile(ACTUAL) as archive:
        base = {entry.filename: archive.read(entry) for entry in archive.infolist()
                if entry.filename.startswith(ROOT)}
    # A passing in-memory positive control isolates the scope verifier from unrelated ZIP files.
    assert verify_motion(packed(base))["status"] == "PASS"
    db_name = sorted(name for name in base if name.endswith("/database.db"))[0]
    controls = []

    def reject(label, members, reason, extra=()):
        try:
            verify_motion(packed(members, extra))
        except MotionVerificationError as failure:
            text = str(failure)
            assert reason in text, (label, reason, text)
            controls.append({"name": label, "result": "REJECTED", "reason": text})
        else:
            raise AssertionError("corrupt negative control unexpectedly passed: " + label)

    def altered(name, field, value):
        return change_json(base, name, lambda obj: obj.__setitem__(field, value))

    members = dict(base)
    del members[ROOT + "native-original.json"]
    reject("missing_root_proof", members, "exactly 13 proofs")
    members = dict(base)
    members[ROOT + "unexpected.json"] = members.pop(ROOT + "native-original.json")
    reject("unexpected_root_proof", members, "root motion proofs")
    reject("duplicate_zip_member", base, "exactly 13 proofs",
           [(ROOT + "native-original.json", base[ROOT + "native-original.json"])])
    members = dict(base)
    members[db_name + "-wal"] = members.pop(db_name)
    reject("nested_wal_file", members, "nested file or WAL/SHM")
    members = dict(base)
    members[db_name + "-shm"] = members.pop(db_name)
    reject("nested_shm_file", members, "nested file or WAL/SHM")
    members = dict(base)
    members[ROOT + "synthetic-motion-profile-123/secret.txt"] = members.pop(db_name)
    reject("unexpected_nested_file", members, "nested file or WAL/SHM")
    members = dict(base)
    members[ROOT.replace("43641520595", "43641520596") + "native-original.json"] = members.pop(ROOT + "native-original.json")
    reject("different_run_directory", members, "unexpected motion run directory")

    members = dict(base)
    name = ROOT + "native-enabled.json"
    members[name] = members[name].replace(b'"mode": "Enable"', b'"mode": "Enable", "mode": "Disable"')
    reject("duplicate_json_key", members, "duplicate JSON key")
    members = dict(base)
    members[name] = members[name].replace(b'"transientOnly": true', b'"transientOnly": NaN')
    reject("nonfinite_json", members, "non-finite JSON number")
    members = dict(base)
    members[name] = b'\xef\xbb\xbf' + members[name]
    reject("json_bom", members, "invalid strict JSON")
    reject("numeric_boolean", altered("native-enabled.json", "transientOnly", 1), "exact type")
    reject("native_wrong_mode", altered("native-enabled.json", "mode", "Disable"), ".mode")
    reject("native_failed_status", altered("native-enabled.json", "status", "FAIL"), ".status")
    reject("native_errors_present", altered("native-enabled.json", "errors", ["synthetic failure"]), ".errors")
    reject("persistent_flags", altered("native-enabled.json", "flags", "SPIF_UPDATEINIFILE (0x1)"), ".flags")
    reject("registry_write_claim", altered("native-enabled.json", "registryWritten", True), ".registryWritten")
    reject("nontransient_claim", altered("native-enabled.json", "transientOnly", False), ".transientOnly")
    members = change_json(base, "native-reduced.json", lambda obj: obj["before"].__setitem__("clientAreaAnimation", False))
    reject("native_phase_chain_broken", members, "phase continuity")
    members = change_json(base, "native-restored.json", lambda obj: obj["after"].__setitem__("minimizeAnimation", True))
    reject("restore_does_not_match_original", members, "requested policy readback")
    reject("snapshot_different_run", altered("original-motion-state.json", "runId", "1"), "original runId")
    reject("snapshot_numeric_schema_boolean", altered("original-motion-state.json", "schema", True), "original schema")
    reject("public_os_authority_violation", altered("reduced-public-preferences.json", "effectiveReducedMotion", False), ".effectiveReducedMotion")
    reject("public_native_policy_mismatch", altered("reduced-public-preferences.json", "windowsSpiClientAreaAnimation", True), ".windowsSpiClientAreaAnimation")
    reject("public_spatial_scale_when_reduced", altered("reduced-public-preferences.json", "pressHasSpatialScale", True), ".pressHasSpatialScale")
    reject("public_action_refired", altered("normal-public-preferences.json", "businessActionCalls", 2), ".businessActionCalls")
    reject("public_boolean_action_count", altered("normal-public-preferences.json", "businessActionCalls", True), ".businessActionCalls")
    reject("public_closing_copy_when_reduced", altered("reduced-public-preferences.json", "reportClosingCopies", 1), ".reportClosingCopies")
    reject("public_failure_field", altered("normal-public-preferences.json", "failure", "synthetic failure"), "unexpected or missing keys")
    members = dict(base)
    log_name = ROOT + "normal-public-preferences.log"
    members[log_name] = members[log_name].replace(b'"businessActionCalls":1', b'"businessActionCalls":2')
    reject("log_json_mismatch", members, "result must match proof")
    members = dict(base)
    members[log_name] += b"Exception: synthetic failure\n"
    reject("log_failure_line", members, "known startup warning")
    members = altered("reduced-public-preferences.json", "atUtc", "2026-11-14T21:14:05.000000000Z")
    # Keep the log internally matching so the chronological guard is actually reached.
    obj = json.loads(members[ROOT + "reduced-public-preferences.json"])
    lines = members[ROOT + "reduced-public-preferences.log"].decode().splitlines()
    lines[0] = "Nov 14, 2026 9:14:05 PM com.sun.javafx.application.PlatformImpl startup"
    lines[2] = json.dumps(obj, separators=(",", ":"))
    members[ROOT + "reduced-public-preferences.log"] = ("\n".join(lines) + "\n").encode()
    reject("probe_time_order_broken", members, "not strictly ordered")

    reject("database_wrong_schema_version", change_db(base, db_name, ["PRAGMA user_version=7"]), "header user_version")
    reject("database_application_id_nonzero", change_db(base, db_name, ["PRAGMA application_id=123"]), "application_id")
    reject("database_business_row", change_db(base, db_name, ["INSERT INTO shops(id,name,api_key) VALUES(77,'synthetic negative control','placeholder')"]), "business rows present in shops")
    reject("database_extra_config_secret", change_db(base, db_name, ["INSERT INTO app_config(key,value) VALUES('synthetic_secret','placeholder')"]), "app_config row count")
    reject("database_saved_preference_wrong", change_db(base, db_name, ["UPDATE app_config SET value='true' WHERE key='ui_reduced_motion'"]), "app_config default/configuration")
    reject("database_config_default_wrong", change_db(base, db_name, ["UPDATE config SET type=2 WHERE id=1"]), "config default/configuration")
    reject("database_sequence_unexpected", change_db(base, db_name, ["INSERT INTO sqlite_sequence(name,seq) VALUES('shops',1)"]), "sqlite_sequence row count")
    reject("database_table_missing", change_db(base, db_name, ["DROP TABLE image_cache"]), "nonempty freelist header")
    reject("database_schema_same_count_changed_index", change_db(base, db_name, ["DROP INDEX idx_image_cache_last_used_at", "CREATE INDEX idx_image_cache_last_used_at ON image_cache(updated_at)"]), "canonical schema identity")
    members = dict(base)
    members[db_name] += b"\0" * 4096
    reject("database_trailing_page", members, "truncated or trailing pages")
    members = dict(base)
    raw = bytearray(members[db_name])
    raw[32:36], raw[36:40] = (1).to_bytes(4, "big"), (1).to_bytes(4, "big")
    members[db_name] = bytes(raw)
    reject("database_free_pages", members, "nonempty freelist header")
    members = dict(base)
    memory = sqlite3.connect(":memory:")
    try:
        copied = bytearray(base[db_name])
        copied[18:20] = b"\x01\x01"
        memory.deserialize(bytes(copied))
        page = memory.execute("SELECT rootpage FROM sqlite_schema WHERE name='app_config'").fetchone()[0]
    finally:
        memory.close()
    raw = bytearray(members[db_name])
    raw[(page - 1) * 4096] = 0xFF
    members[db_name] = bytes(raw)
    reject("database_corrupt_btree_page", members, "motion verification could not complete")
    members = dict(base)
    raw = bytearray(members[db_name])
    # SQLite unused database header bytes do not affect logical integrity.
    raw[72] ^= 1
    members[db_name] = bytes(raw)
    reject("database_hidden_header_byte_changed", members, "fixed-release byte identity")

    source_review = []
    for relative, expected in SOURCE_FILES.items():
        blob = subprocess.run(["git", "-C", str(SOURCE), "show", SOURCE_COMMIT + ":" + relative],
                              check=True, capture_output=True).stdout
        source_digest = hashlib.sha256(blob).hexdigest()
        assert source_digest == expected, (relative, source_digest)
        source_review.append({"path": relative, "sha256": source_digest,
                              "exactPinnedGitBlobReviewed": True,
                              "worktreeByteIdentical": (SOURCE / relative).read_bytes() == blob})
    after = digest_file(ACTUAL)
    assert after == before, "actual evidence ZIP changed during offline audit"
    receipt = {
        "status": "PASS", "createdAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "actualZip": str(ACTUAL), "actualZipSha256Before": before, "actualZipSha256After": after,
        "actualEvidenceUnchanged": True, "actual": actual,
        "inMemoryPositiveControl": "PASS", "negativeControlCount": len(controls),
        "allNegativeControlsRejected": True, "negativeControls": controls,
        "sourceReview": {"commit": SOURCE_COMMIT, "files": source_review,
                         "findings": [
                             "Native setters use literal flags=2, omit SPIF_UPDATEINIFILE and invoke no registry writer; same-run snapshot and SPI readbacks are required.",
                             "Public probe creates a fresh synthetic profile, sets saved motion false and compares effective preference to public JavaFX OS preference; it checks scale, action exactly once, and reduced-motion closing-copy suppression.",
                             "ThemeService combines saved reduced-motion preference with OS preference using logical OR and cancels motion on reduced mode; interaction motion guards use this effective preference.",
                             "Database.initDatabase sets schema/user version 6, config default (1,1); ConfigService upserts the saved preference, and the diagnostic ends with ui_reduced_motion='false'.",
                             "Normal reportClosingCopies=1 is observed fixed-release evidence; source explicitly asserts zero copies only in reduced mode.",
                         ]},
        "execution": {"offline": True, "applicationOrMavenExecuted": False,
                      "networkUsed": False, "productionSourceModified": False,
                      "actualDatabaseFilesOpenedBySQLite": False,
                      "sqliteActualInspection": "ZIP bytes copied to removed immutable/read-only temporary DBs under verifier directory",
                      "sqliteNegativeControls": "Only :memory: databases and in-memory ZIP mutations"},
        "helperSha256": digest_file(HERE / "verify_motion.py"),
        "runnerSha256": digest_file(pathlib.Path(__file__)),
    }
    (HERE / "motion-receipt.json").write_text(json.dumps(receipt, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({"status": receipt["status"], "negativeControlsRejected": len(controls),
                      "actualZipSha256": before, "receipt": str(HERE / "motion-receipt.json")}))


if __name__ == "__main__":
    main()
