#!/usr/bin/env python3
"""Compare downloaded SARIF as exact semantic-record multisets; no network access.

Run from any directory. Inputs are reference/ and candidate/ beneath this file's
directory. Output includes full normalized findings (with multiplicity), full
warning/error notifications, raw file hashes, and exact multiset differences.
"""

import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import re
from urllib.parse import urljoin


LANGUAGES = {
    "java-kotlin": "java.sarif",
    "javascript-typescript": "javascript.sarif",
}
JS_WHITESPACE = "\u0009\u000a\u000b\u000c\u000d\u0020\u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def whitespace(value):
    return re.sub(r"\s+", " ", value).strip() if isinstance(value, str) else value


def valid_index(value):
    return type(value) is int and value >= 0


def component_for(run, reference):
    driver = run.get("tool", {}).get("driver")
    if not isinstance(driver, dict) or not isinstance(driver.get("rules"), list):
        raise ValueError("Missing SARIF driver rules")
    if "toolComponent" not in reference:
        return driver
    component = reference["toolComponent"]
    if not isinstance(component, dict):
        raise ValueError("Invalid SARIF tool component")
    extensions = run["tool"].get("extensions")
    if not isinstance(extensions, list):
        raise ValueError("Missing SARIF tool extensions")
    if "index" in component:
        index = component["index"]
        if not valid_index(index) or index >= len(extensions):
            raise ValueError("Unknown SARIF tool component")
        source = extensions[index]
        if any(key in component and component[key] != source.get(key) for key in ("name", "guid")):
            raise ValueError("Conflicting SARIF tool component")
        return source
    matches = [item for item in [driver, *extensions] if isinstance(item, dict)
               and all(key not in component or item.get(key) == component[key] for key in ("name", "guid"))]
    if not any(key in component for key in ("name", "guid")) or len(matches) != 1:
        raise ValueError("Unknown or ambiguous SARIF tool component")
    return matches[0]


def result_rule(run, result):
    if not isinstance(result, dict):
        raise ValueError("Invalid SARIF result")
    reference = result.get("rule", {})
    if not isinstance(reference, dict):
        raise ValueError("Invalid SARIF rule reference")
    source = component_for(run, reference)
    rules = source.get("rules")
    if not isinstance(rules, list):
        raise ValueError("Missing SARIF component rules")
    top_id, nested_id = result.get("ruleId"), reference.get("id")
    for key, value in (("ruleId", top_id), ("id", nested_id)):
        if key in (result if key == "ruleId" else reference) and (not isinstance(value, str) or not value):
            raise ValueError("Conflicting SARIF rule identifier")
    if top_id is not None and nested_id is not None and top_id != nested_id:
        raise ValueError("Conflicting SARIF rule identifier")
    if "ruleIndex" in result and "index" in reference and result["ruleIndex"] != reference["index"]:
        raise ValueError("Conflicting SARIF rule index")
    rule_id = top_id if "ruleId" in result else nested_id
    index = result.get("ruleIndex", reference.get("index"))
    if "ruleIndex" in result or "index" in reference:
        if not valid_index(index) or index >= len(rules):
            raise ValueError("SARIF result references an unknown rule")
        rule = rules[index]
        if not isinstance(rule, dict) or not isinstance(rule.get("id"), str) or not rule["id"] or (rule_id is not None and rule_id != rule["id"]):
            raise ValueError("Conflicting SARIF rule reference")
        return rule
    matches = [rule for rule in rules if isinstance(rule, dict) and rule.get("id") == rule_id and isinstance(rule_id, str) and rule_id]
    if len(matches) != 1:
        raise ValueError("SARIF result references an unknown or ambiguous rule")
    return matches[0]


def numeric_severity(rule):
    properties = rule.get("properties")
    value = properties.get("security-severity") if isinstance(properties, dict) else None
    if type(value) in (int, float):
        number = value
    elif isinstance(value, str) and value.strip(JS_WHITESPACE):
        value = value.strip(JS_WHITESPACE)
        try:
            if re.fullmatch(r"0[xX][0-9a-fA-F]+|0[bB][01]+|0[oO][0-7]+", value):
                number = int(value, 0)
            elif re.fullmatch(r"[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?", value):
                number = float(value)
            else:
                return None
        except ValueError:
            return None
    else:
        return None
    try:
        number = float(number)
    except OverflowError:
        return None
    if not math.isfinite(number):
        return None
    return int(number) if number.is_integer() else number


class Normalizer:
    def __init__(self, run):
        self.run = run

    def artifact_location(self, location):
        if not isinstance(location, dict):
            raise ValueError("Invalid SARIF artifact location")
        result = {}
        if "index" in location:
            index = location["index"]
            artifacts = self.run.get("artifacts", [])
            if not valid_index(index) or index >= len(artifacts):
                raise ValueError("Unknown SARIF artifact index")
            result.update(artifacts[index].get("location", {}))
        result.update(location)
        result.pop("index", None)
        # Resolve an available base recursively. An unresolved symbolic base is
        # preserved, so two different unresolved roots cannot compare equal.
        base_id = result.get("uriBaseId")
        bases = self.run.get("originalUriBaseIds", {})
        seen = set()
        while base_id in bases:
            if base_id in seen:
                raise ValueError("Cyclic SARIF originalUriBaseIds")
            seen.add(base_id)
            base = bases[base_id]
            result["uri"] = urljoin(base.get("uri", ""), result.get("uri", ""))
            base_id = base.get("uriBaseId")
            if base_id is None:
                result.pop("uriBaseId", None)
                break
            result["uriBaseId"] = base_id
        return self.generic(result)

    def logical_location(self, location, seen=None):
        if not isinstance(location, dict):
            raise ValueError("Invalid SARIF logical location")
        seen = set() if seen is None else seen
        result = {}
        if "index" in location:
            index = location["index"]
            logical = self.run.get("logicalLocations", [])
            if not valid_index(index) or index >= len(logical) or index in seen:
                raise ValueError("Unknown or cyclic SARIF logical location index")
            seen = seen | {index}
            result.update(logical[index])
        result.update(location)
        result.pop("index", None)
        if "parentIndex" in result:
            result["parent"] = self.logical_location({"index": result.pop("parentIndex")}, seen)
        return self.generic(result)

    def thread_flow_location(self, location):
        if not isinstance(location, dict):
            raise ValueError("Invalid SARIF thread-flow location")
        result = {}
        if "index" in location:
            index = location["index"]
            locations = self.run.get("threadFlowLocations", [])
            if not valid_index(index) or index >= len(locations):
                raise ValueError("Unknown SARIF thread-flow location index")
            result.update(locations[index])
        result.update(location)
        result.pop("index", None)
        return self.generic(result)

    def generic(self, value, key=None):
        if isinstance(value, list):
            if key == "logicalLocations":
                return [self.logical_location(item) for item in value]
            return [self.generic(item) for item in value]
        if not isinstance(value, dict):
            return whitespace(value) if key in ("text", "markdown") else value
        if key == "artifactLocation":
            return self.artifact_location(value)
        result = {}
        for child_key, item in value.items():
            if child_key == "threadFlows":
                result[child_key] = []
                for flow in item:
                    normalized = self.generic({k: v for k, v in flow.items() if k != "locations"})
                    normalized["locations"] = [self.thread_flow_location(location) for location in flow.get("locations", [])]
                    result[child_key].append(normalized)
            else:
                result[child_key] = self.generic(item, child_key)
        return result

    def message(self, message, descriptor=None):
        message = dict(message or {})
        # Resolve message-string references while preserving id and arguments.
        if "id" in message and "text" not in message and "markdown" not in message:
            candidates = []
            if descriptor:
                candidates.append(descriptor.get("messageStrings", {}))
            candidates.append(self.run.get("tool", {}).get("driver", {}).get("globalMessageStrings", {}))
            for strings in candidates:
                if message["id"] in strings:
                    text = strings[message["id"]]
                    if isinstance(text, str):
                        message["text"] = text
                    elif isinstance(text, dict):
                        message.update(text)
                    break
            if "text" not in message and "markdown" not in message:
                raise ValueError("Unresolved SARIF message string")
        return self.generic(message)

    def finding(self, result):
        rule = result_rule(self.run, result)
        severity = numeric_severity(rule)
        code_flows = self.generic(result.get("codeFlows", []))
        return {
            "rule": rule["id"],
            "securitySeverity": severity,
            "level": result.get("level", rule.get("defaultConfiguration", {}).get("level", "warning")),
            "kind": result.get("kind", "fail"),
            "message": self.message(result.get("message"), rule),
            "locations": self.generic(result.get("locations", [])),
            "relatedLocations": self.generic(result.get("relatedLocations", [])),
            "fingerprints": self.generic(result.get("fingerprints", {})),
            "partialFingerprints": self.generic(result.get("partialFingerprints", {})),
            "codeFlows": code_flows,
            "codeFlowCount": len(code_flows),
            "threadFlowCount": sum(len(flow.get("threadFlows", [])) for flow in code_flows),
            "threadFlowLocationCount": sum(len(thread.get("locations", [])) for flow in code_flows for thread in flow.get("threadFlows", [])),
            "stacks": self.generic(result.get("stacks", [])),
        }

    def notification(self, notification, kind):
        descriptor_ref = notification.get("descriptor", {})
        descriptor = {}
        if descriptor_ref:
            source = component_for(self.run, descriptor_ref)
            descriptors = source.get("notifications", [])
            if "index" in descriptor_ref:
                index = descriptor_ref["index"]
                if not valid_index(index) or index >= len(descriptors):
                    raise ValueError("Unknown SARIF notification descriptor")
                descriptor = descriptors[index]
                if "id" in descriptor_ref and descriptor_ref["id"] != descriptor.get("id"):
                    raise ValueError("Conflicting SARIF notification descriptor")
            else:
                matches = [item for item in descriptors if item.get("id") == descriptor_ref.get("id")]
                if len(matches) == 1:
                    descriptor = matches[0]
                elif descriptor_ref.get("id") is None:
                    raise ValueError("Unresolved SARIF notification descriptor")
        normalized = self.generic({key: value for key, value in notification.items()
                                   if key not in ("descriptor", "message")})
        normalized["kind"] = kind
        normalized["descriptorId"] = descriptor_ref.get("id", descriptor.get("id"))
        normalized["level"] = notification.get("level", "warning")
        normalized["message"] = self.message(notification.get("message"), descriptor)
        return normalized


def inspect_file(path):
    raw = path.read_bytes()
    report = json.loads(raw)
    if report.get("version") != "2.1.0" or not isinstance(report.get("runs"), list) or not report["runs"]:
        raise ValueError(f"Missing or invalid SARIF runs: {path}")
    records, notifications, invocations, tools = [], [], [], []
    for run in report["runs"]:
        if not isinstance(run.get("tool", {}).get("driver", {}).get("rules"), list) or not isinstance(run.get("results"), list):
            raise ValueError(f"Missing SARIF rules or results: {path}")
        normalizer = Normalizer(run)
        records.extend(normalizer.finding(result) for result in run["results"])
        driver = run["tool"]["driver"]
        tools.append({key: driver[key] for key in ("name", "version", "semanticVersion", "informationUri") if key in driver})
        for invocation in run.get("invocations", []):
            invocations.append({key: invocation[key] for key in ("executionSuccessful", "exitCode", "processStartFailureMessage") if key in invocation})
            for kind in ("toolExecutionNotifications", "toolConfigurationNotifications"):
                for notification in invocation.get(kind, []):
                    if notification.get("level", "warning") in ("warning", "error"):
                        notifications.append(normalizer.notification(notification, kind))
    records.sort(key=canonical)
    notifications.sort(key=canonical)
    blocked = [record for record in records if record["securitySeverity"] is None or not 0 <= record["securitySeverity"] < 7]
    return {
        "path": str(path), "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw),
        "tools": tools, "invocations": invocations, "records": records, "notifications": notifications,
        "count": len(records), "blocked": len(blocked), "blockedRecords": blocked,
        "gateConclusion": "failure" if blocked else "success",
        "rules": dict(sorted(Counter(record["rule"] for record in records).items())),
        "severityCounts": dict(sorted(Counter(str(record["securitySeverity"]) for record in records).items())),
        "notificationCounts": dict(sorted(Counter(notification["level"] for notification in notifications).items())),
    }


def multiset_delta(reference, candidate):
    old, new = Counter(map(canonical, reference)), Counter(map(canonical, candidate))
    def expand(counter):
        return [{"multiplicity": count, "record": json.loads(record)} for record, count in sorted(counter.items())]
    return {"identical": old == new, "newCount": sum((new - old).values()),
            "resolvedCount": sum((old - new).values()), "unchangedCount": sum((old & new).values()),
            "new": expand(new - old), "resolved": expand(old - new)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent)
    args = parser.parse_args()
    root = args.root
    normalized = {"method": {
        "findings": "Exact canonical JSON record multiset (Counter, no deduplication): resolved rule ID, numeric security severity, result level/kind, message, all primary and related physical/logical locations, both fingerprint maps, full code flows and counts, stacks.",
        "locations": "Resolve artifact/logical/thread-flow index references and available URI bases; preserve unresolved symbolic URI bases, complete regions, snippets, logical hierarchy, all array order and movement changes. Do not strip line numbers or runner paths.",
        "text": "Collapse whitespace only in SARIF text/markdown values. Preserve message IDs/arguments and resolve message-string text when needed. Canonicalize object key order.",
        "notifications": "Exact warning/error notification multisets from all invocation toolExecutionNotifications and toolConfigurationNotifications; resolved descriptor IDs, full normalized content, locations, exceptions and properties; preserve multiplicity.",
        "gate": "Equivalent to repository inspectSarif: every result counts; block missing/nonfinite/out-of-range security-severity or numeric severity >=7; fail malformed/ambiguous rule references. Notifications are reported separately and not included in blocked finding counts.",
    }, "reference": {}, "candidate": {}}
    details = {"languages": {}}
    for language, filename in LANGUAGES.items():
        for side in ("reference", "candidate"):
            path = root / side / f"codeql-{language}" / filename
            if not path.is_file():
                raise FileNotFoundError(f"Required actual raw SARIF is not available: {path}")
            normalized[side][language] = inspect_file(path)
        old, new = (normalized[side][language] for side in ("reference", "candidate"))
        details["languages"][language] = {
            "reference": {key: value for key, value in old.items() if key not in ("records", "notifications", "blockedRecords")},
            "candidate": {key: value for key, value in new.items() if key not in ("records", "notifications", "blockedRecords")},
            "findings": multiset_delta(old["records"], new["records"]),
            "notifications": multiset_delta(old["notifications"], new["notifications"]),
            "referenceBlockedRecords": old["blockedRecords"], "candidateBlockedRecords": new["blockedRecords"],
        }
    details["totals"] = {
        side: {"findings": sum(report["count"] for report in normalized[side].values()),
               "blocked": sum(report["blocked"] for report in normalized[side].values()),
               "warningErrorNotifications": sum(len(report["notifications"]) for report in normalized[side].values())}
        for side in ("reference", "candidate")
    }
    details["identicalNormalizedFindingRecords"] = all(item["findings"]["identical"] for item in details["languages"].values())
    details["identicalNormalizedWarningErrorNotifications"] = all(item["notifications"]["identical"] for item in details["languages"].values())
    details["newFindingCount"] = sum(item["findings"]["newCount"] for item in details["languages"].values())
    details["resolvedFindingCount"] = sum(item["findings"]["resolvedCount"] for item in details["languages"].values())
    for filename, data in (("normalized-records.json", normalized), ("comparison-details.json", details)):
        (root / filename).write_text(json.dumps(data, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + "\n")
    print(json.dumps({key: value for key, value in details.items() if key != "languages"}, sort_keys=True))


if __name__ == "__main__":
    main()
