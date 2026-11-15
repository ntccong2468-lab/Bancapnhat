"""Independent, offline, fixed-release verifier for Windows motion evidence.

No application code is imported or executed. Source contract was reviewed at
b74b5edfd46636e8779c94f467eef01c03c50a14. SQLite receives only copies of ZIP
member bytes in an automatically removed temporary directory beside this file.
The public function accepts a path or an in-memory, seekable ZIP stream and
raises MotionVerificationError on any failed invariant.
"""

from __future__ import annotations

import collections
import datetime as dt
import hashlib
import json
import pathlib
import re
import sqlite3
import stat
import tempfile
import zipfile


ROOT = "baseline-motion-43641520595/"
RUN_ID = "43641520595"
SOURCE_COMMIT = "b74b5edfd46636e8779c94f467eef01c03c50a14"
FLAGS = "SPIF_SENDCHANGE (0x2), without SPIF_UPDATEINIFILE"
DATABASE_SHA256 = "ef6768e00a2a03cfc930b73c4e68e6a3b8896f9d59f0043cc5743c5f605aee30"
# Canonical SQLite schema identity, explicitly an observed fixed-release pin.
SCHEMA_SHA256 = "0c2833d70de55447bd117dcaf45a87fe961326a069f7412a207bcecfc53bf1e6"
PROOFS = frozenset({
    "native-original.json", "native-enabled.json", "native-reduced.json",
    "native-normal-before-preflight.json", "native-normal-before-full.json",
    "native-restored.json", "original-motion-state.json",
    "normal-public-preferences.json", "normal-public-preferences.log",
    "reduced-public-preferences.json", "reduced-public-preferences.log",
    "normal-public-before-full.json", "normal-public-before-full.log",
})
TABLES = frozenset("""
app_config config fbo_print_templates gs1_balance gs1_sync_status
gtin_sync_barcode_observations gtin_sync_barcode_read_heads gtin_sync_catalog
gtin_sync_events gtin_sync_items gtin_sync_jobs gtin_sync_mappings
gtin_sync_verified_mappings image_cache kiz_codes kiz_import_batches
kiz_import_entries kiz_orders kiz_ozon_category_sources kiz_rejected_stock
kiz_shipment_sources kiz_stock_categories kiz_stock_category_reservations
kiz_stock_classifications kiz_stock_deleted_categories kiz_stock_product_categories
kiz_warehouse_settings kiz_wb_category_sources kn3_marks kn3_replacement_stock
kn3_replacements listing_copy_articles listing_copy_events listing_copy_jobs
news_translation_settings news_translations ozon_accept_kiz_replacements
ozon_action_log ozon_article_gtin_mappings ozon_card_registration_history
ozon_card_registrations ozon_exemplar_jobs ozon_exemplars ozon_fbo_orders
ozon_fbo_supplies ozon_fbo_supply_items ozon_fbo_sync_state ozon_fbs_print_receipts
ozon_fbs_queue_scans ozon_fbs_status_observations ozon_label_jobs ozon_posting_items
ozon_postings ozon_product_barcodes ozon_product_gtin_mappings
ozon_product_kiz_policies ozon_products ozon_registration_batch_items
ozon_registration_batches ozon_sync_state print_job_items print_jobs print_templates
product_documents product_edit_assets product_edit_events product_edit_items
product_edit_jobs product_snapshots seller_news seller_news_refresh
shop_credential_mirrors shop_credential_tombstones shops sqlite_sequence
sync_gtin_issue_details sync_gtin_issues sync_run_events sync_run_items sync_runs
sync_streams wb_action_log wb_fbw_order_items wb_fbw_orders wb_fbw_sync_state
wb_gtin_price_consents wb_gtin_price_repairs wb_order_meta_requirements
wb_order_offices wb_order_skus wb_orders wb_product_cards wb_product_characteristics
wb_product_photos wb_product_size_skus wb_product_sizes wb_product_tags wb_supplies
wb_supply_membership_versions wb_supply_orders wb_supply_pending_confirmations
wb_sync_runs znack_card_registration_history znack_card_registration_replacements
znack_card_registration_scopes znack_card_registration_signatures
znack_card_registrations znack_documents znack_duplicate_kiz_code_audit
znack_gtin_mapping_rules znack_operation_logs znack_products znack_purchase_pipelines
znack_registration_batch_items znack_registration_batches
znack_registration_marketplace_consents znack_registration_signing_consents
znack_settings znack_v3_allocation_generations znack_v3_dispatches znack_v3_events
znack_v3_jobs znack_v3_remote_operations
""".split())
LIMITATIONS = [
    "Offline evidence validates recorded claims and consistency; it does not re-run Windows or JavaFX.",
    "Source setters use flags=2 with no registry-write API; transientOnly and registryWritten are declarations, not independently measured registry diffs.",
    "Three distinct synthetic profile names do not independently establish three executions; their database bytes are identical.",
    "Logical emptiness, integrity, zero freelist and exact page length are checked. They alone are not a forensic proof of zero historical content in SQLite in-page slack. A fixed-release byte digest is additionally enforced.",
    "The 123-table and canonical schema/whole-database digests are observed fixed-release identity pins; schema version and default rows are additionally grounded in reviewed source.",
]


class MotionVerificationError(ValueError):
    pass


def _need(condition, message):
    if not condition:
        raise MotionVerificationError(message)


def _pairs(pairs):
    obj = {}
    for key, value in pairs:
        _need(key not in obj, f"duplicate JSON key: {key}")
        obj[key] = value
    return obj


def _constant(value):
    raise MotionVerificationError(f"non-finite JSON number: {value}")


def _json(raw, name):
    try:
        value = json.loads(raw.decode("utf-8", errors="strict"),
                           object_pairs_hook=_pairs, parse_constant=_constant)
    except (UnicodeError, ValueError) as failure:
        raise MotionVerificationError(f"{name}: invalid strict JSON: {failure}") from failure
    _need(type(value) is dict, f"{name}: expected JSON object")
    return value


def _keys(obj, expected, name):
    _need(set(obj) == set(expected), f"{name}: unexpected or missing keys")


def _exact(value, expected, name):
    _need(type(value) is type(expected) and value == expected,
          f"{name}: expected {expected!r} with exact type")


def _state(value, name):
    _need(type(value) is dict, f"{name}: expected state object")
    _keys(value, ("clientAreaAnimation", "minimizeAnimation"), name)
    for key in value:
        _need(type(value[key]) is bool, f"{name}.{key}: expected Boolean")
    return value


def _timestamp(value, name):
    _need(type(value) is str and re.fullmatch(
        r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z", value),
        f"{name}: expected UTC timestamp")
    try:
        return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as failure:
        raise MotionVerificationError(f"{name}: invalid timestamp") from failure


def _native(obj, mode, name):
    _keys(obj, ("mode", "before", "after", "transientOnly", "registryWritten",
                "flags", "status", "errors"), name)
    _exact(obj["mode"], mode, name + ".mode")
    _state(obj["before"], name + ".before")
    _state(obj["after"], name + ".after")
    for key, value in (("transientOnly", True), ("registryWritten", False),
                       ("flags", FLAGS), ("status", "PASS"), ("errors", [])):
        _exact(obj[key], value, name + "." + key)


def _public(obj, reduced, native_state, name):
    _keys(obj, ("atUtc", "os", "expectedReduced", "windowsSpiClientAreaAnimation",
                "platformReducedMotion", "savedAppReducedMotion", "effectiveReducedMotion",
                "pressHasSpatialScale", "reportClosingCopies", "businessActionCalls", "status"), name)
    stamp = _timestamp(obj["atUtc"], name + ".atUtc")
    expected = {
        "os": "Windows Server 2022", "expectedReduced": str(reduced).lower(),
        "windowsSpiClientAreaAnimation": native_state["clientAreaAnimation"],
        "platformReducedMotion": reduced, "savedAppReducedMotion": "false",
        "effectiveReducedMotion": reduced, "pressHasSpatialScale": not reduced,
        "reportClosingCopies": 0 if reduced else 1,
        "businessActionCalls": 1, "status": "PASS",
    }
    for key, value in expected.items():
        _exact(obj[key], value, name + "." + key)
    return stamp


def _log(raw, obj, name):
    try:
        lines = raw.decode("utf-8", errors="strict").splitlines()
    except UnicodeError as failure:
        raise MotionVerificationError(f"{name}: invalid UTF-8 log") from failure
    _need(len(lines) == 3, f"{name}: expected known startup warning and one JSON result only")
    _need(re.fullmatch(r"[A-Z][a-z]{2} \d{1,2}, \d{4} \d{1,2}:\d{2}:\d{2} [AP]M com\.sun\.javafx\.application\.PlatformImpl startup", lines[0]),
          f"{name}: unexpected log header")
    _need(re.fullmatch(r"WARNING: Unsupported JavaFX configuration: classes were loaded from 'unnamed module @[0-9a-fA-F]+'", lines[1]),
          f"{name}: unexpected warning, error or failure")
    result = _json(lines[2].encode("utf-8"), name + ":result")
    # Serialize after strict parsing so JSON Boolean and integer aliases cannot compare equal.
    _exact(json.dumps(result, sort_keys=True), json.dumps(obj, sort_keys=True),
           name + ":result must match proof")
    header = lines[0].removesuffix(" com.sun.javafx.application.PlatformImpl startup")
    try:
        log_time = dt.datetime.strptime(header, "%b %d, %Y %I:%M:%S %p").replace(tzinfo=dt.timezone.utc)
    except ValueError as failure:
        raise MotionVerificationError(f"{name}: invalid log timestamp") from failure
    _need(log_time == _timestamp(obj["atUtc"], name).replace(microsecond=0),
          f"{name}: log and proof timestamps differ")


def _database(raw, name):
    _need(raw[:16] == b"SQLite format 3\x00", f"{name}: invalid SQLite header")
    _need(len(raw) >= 100, f"{name}: short SQLite file")
    page_size = int.from_bytes(raw[16:18], "big")
    page_size = 65536 if page_size == 1 else page_size
    _need(page_size == 4096, f"{name}: unexpected page size")
    header_pages = int.from_bytes(raw[28:32], "big")
    _need(header_pages > 0 and len(raw) == header_pages * page_size,
          f"{name}: truncated or trailing pages/bytes")
    _need(int.from_bytes(raw[32:36], "big") == 0 and int.from_bytes(raw[36:40], "big") == 0,
          f"{name}: nonempty freelist header")
    _need(int.from_bytes(raw[60:64], "big") == 6, f"{name}: header user_version must be 6")
    _need(int.from_bytes(raw[68:72], "big") == 0, f"{name}: application_id must be zero")
    # All writes are bounded copies under the verifier directory, never evidence paths.
    with tempfile.TemporaryDirectory(prefix=".motion-db-", dir=pathlib.Path(__file__).resolve().parent) as directory:
        copied = pathlib.Path(directory) / "database.db"
        copied.write_bytes(raw)
        copied.chmod(0o400)
        before = hashlib.sha256(copied.read_bytes()).hexdigest()
        connection = sqlite3.connect(copied.as_uri() + "?mode=ro&immutable=1", uri=True)
        try:
            connection.execute("PRAGMA query_only=ON")
            connection.execute("PRAGMA trusted_schema=OFF")
            pragmas = {key: connection.execute("PRAGMA " + key).fetchone()[0]
                       for key in ("user_version", "application_id", "page_size", "page_count", "freelist_count")}
            _need(pragmas == {"user_version": 6, "application_id": 0, "page_size": page_size,
                              "page_count": header_pages, "freelist_count": 0},
                  f"{name}: SQLite metadata mismatch")
            _need(connection.execute("PRAGMA integrity_check").fetchall() == [("ok",)],
                  f"{name}: SQLite integrity_check failed")
            _need(connection.execute("PRAGMA foreign_key_check").fetchall() == [],
                  f"{name}: foreign key violations")
            objects = connection.execute("SELECT type,name,tbl_name,sql FROM sqlite_schema ORDER BY type,name").fetchall()
            tables = {row[1] for row in objects if row[0] == "table"}
            _need(len(TABLES) == 123 and tables == TABLES,
                  f"{name}: expected exact 123-table set")
            counts = collections.Counter(row[0] for row in objects)
            _need(dict(counts) == {"index": 196, "table": 123, "trigger": 4},
                  f"{name}: unexpected schema objects")
            defaults = {
                "config": [(1, 1)],
                "app_config": [("ui_reduced_motion", "false")],
                "sqlite_sequence": [("config", 1), ("kiz_codes", 0)],
            }
            nonempty = {}
            for table in sorted(tables):
                quoted = '"' + table.replace('"', '""') + '"'
                count = connection.execute("SELECT count(*) FROM " + quoted).fetchone()[0]
                if table in defaults:
                    _need(count == len(defaults[table]), f"{name}: unexpected {table} row count")
                    rows = connection.execute("SELECT * FROM " + quoted + " ORDER BY 1").fetchall()
                    _need(rows == defaults[table], f"{name}: unexpected {table} default/configuration rows")
                else:
                    _need(count == 0, f"{name}: business rows present in {table}")
                if count:
                    nonempty[table] = count
            schema_digest = hashlib.sha256(json.dumps(objects, ensure_ascii=False, separators=(",", ":")).encode("utf-8")).hexdigest()
            _exact(schema_digest, SCHEMA_SHA256, name + ": canonical schema identity")
        finally:
            connection.close()
        _need(copied.read_bytes() == raw, f"{name}: temporary DB was mutated")
        _need(sorted(p.name for p in pathlib.Path(directory).iterdir()) == ["database.db"],
              f"{name}: temporary WAL/SHM/sidecar created")
    digest = hashlib.sha256(raw).hexdigest()
    _exact(digest, before, name + ": temporary-copy identity")
    _exact(digest, DATABASE_SHA256, name + ": fixed-release byte identity")
    return {"path": name, "bytes": len(raw), "sha256": digest, "userVersion": 6,
            "applicationId": 0, "tableCount": len(tables), "businessRows": 0,
            "nonemptyTables": nonempty, "integrityCheck": "ok", "foreignKeyViolations": 0,
            "pageSize": page_size, "pageCount": header_pages, "freelistCount": 0,
            "trailingBytes": 0, "schemaSha256": schema_digest,
            "temporaryCopyUnchanged": True, "temporarySidecars": []}


def verify_motion(zip_path):
    """Return a JSON-serializable PASS receipt, or raise MotionVerificationError."""
    try:
        with zipfile.ZipFile(zip_path, "r") as archive:
            entries = [entry for entry in archive.infolist()
                       if entry.filename.startswith("baseline-motion-")]
            _need(len(entries) == 16, "motion scope must contain exactly 13 proofs and 3 DBs")
            names = [entry.filename for entry in entries]
            _need(len(names) == len(set(names)), "duplicate motion ZIP member")
            _need(len(names) == len({name.casefold() for name in names}), "case-colliding motion ZIP members")
            roots, db_names = set(), []
            for entry in entries:
                name = entry.filename
                _need(name.startswith(ROOT), "unexpected motion run directory")
                _need(not entry.is_dir(), f"{name}: unexpected directory entry")
                _need(not (entry.flag_bits & 1), f"{name}: encrypted member")
                mode = entry.external_attr >> 16
                _need(stat.S_IFMT(mode) in (0, stat.S_IFREG), f"{name}: nonregular member")
                local = name[len(ROOT):]
                if "/" not in local:
                    roots.add(local)
                else:
                    _need(re.fullmatch(r"synthetic-motion-profile-[0-9]+/database\.db", local),
                          f"{name}: unexpected nested file or WAL/SHM")
                    db_names.append(name)
                limit = 2 * 1024 * 1024 if name in db_names else 65536
                _need(0 < entry.file_size <= limit, f"{name}: unexpected member size")
            _need(roots == PROOFS, "unexpected or missing root motion proofs")
            _need(len(db_names) == 3 and len({name.split('/')[1] for name in db_names}) == 3,
                  "expected three distinct synthetic database profiles")
            # Read every byte, including logs, with ZIP CRC validation.
            members = {name: archive.read(name) for name in names}
        proofs = {name: _json(members[ROOT + name], name)
                  for name in sorted(PROOFS) if name.endswith(".json")}
        original = proofs["original-motion-state.json"]
        _keys(original, ("schema", "original", "runId", "createdAtUtc"), "original-motion-state.json")
        _exact(original["schema"], 1, "original schema")
        _exact(original["runId"], RUN_ID, "original runId")
        _state(original["original"], "original state")
        created = _timestamp(original["createdAtUtc"], "original createdAtUtc")
        phases = [
            ("native-original.json", "Snapshot"),
            ("native-enabled.json", "Enable"),
            ("native-reduced.json", "Disable"),
            ("native-normal-before-preflight.json", "Enable"),
            ("native-normal-before-full.json", "Enable"),
            ("native-restored.json", "Restore"),
        ]
        previous = original["original"]
        for name, mode in phases:
            obj = proofs[name]
            _native(obj, mode, name)
            _exact(obj["before"], previous, name + ": before/after phase continuity")
            target = original["original"] if mode in ("Snapshot", "Restore") else {
                "clientAreaAnimation": mode == "Enable", "minimizeAnimation": mode == "Enable"}
            _exact(obj["after"], target, name + ": requested policy readback")
            previous = obj["after"]
        times = [created]
        for name, reduced, native_name in (
            ("normal-public-preferences.json", False, "native-enabled.json"),
            ("reduced-public-preferences.json", True, "native-reduced.json"),
            ("normal-public-before-full.json", False, "native-normal-before-full.json"),
        ):
            obj = proofs[name]
            times.append(_public(obj, reduced, proofs[native_name]["after"], name))
            log_name = name.removesuffix(".json") + ".log"
            _log(members[ROOT + log_name], obj, log_name)
        _need(all(left < right for left, right in zip(times, times[1:])),
              "snapshot/public probe timestamps not strictly ordered")
        databases = [_database(members[name], name) for name in sorted(db_names)]
        return {
            "status": "PASS", "scope": ROOT.rstrip("/"), "sourceContractCommit": SOURCE_COMMIT,
            "rootProofCount": len(roots), "jsonProofCount": len(proofs), "logProofCount": 3,
            "syntheticDatabaseCount": len(databases), "allMembersRead": len(members),
            "checks": {key: "PASS" for key in (
                "exactInventory", "strictJson", "nativeModesAndPolicies", "nativePhaseContinuity",
                "transientFlagsAndNoRegistryClaim", "originalSnapshotRestored", "publicOsAuthority",
                "publicSpatialFeedbackAndSingleAction", "publicLogAgreement", "utcTimeOrder",
                "syntheticDatabaseSchemaAndEmptyBusinessRows", "immutableTemporaryCopies",
                "databaseIntegrityAndNoFreeOrTrailingPages", "fixedReleaseDatabaseIdentity")},
            "members": [{"path": name, "bytes": len(members[name]),
                         "sha256": hashlib.sha256(members[name]).hexdigest()} for name in sorted(members)],
            "databases": databases, "limitations": LIMITATIONS,
        }
    except MotionVerificationError:
        raise
    except (OSError, zipfile.BadZipFile, sqlite3.Error, RuntimeError) as failure:
        raise MotionVerificationError(f"motion verification could not complete: {failure}") from failure


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zip_path")
    args = parser.parse_args()
    print(json.dumps(verify_motion(args.zip_path), indent=2, ensure_ascii=False))
