#!/usr/bin/env python3
"""Manual, read-only WB/Catalog probes. Raw data stays in an owner-only /tmp directory."""
import argparse
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
from urllib.parse import urlparse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["baseline", "policy", "reopen"])
    parser.add_argument("--directory", type=Path)
    parser.add_argument("--jdk-home", type=Path, required=True)
    parser.add_argument("--classpath-file", type=Path, required=True)
    parser.add_argument("--jar", type=Path, default=Path("target/VNcode-1.6.4.jar"))
    args = parser.parse_args()
    if args.mode != "baseline" and args.directory is None:
        parser.error("policy/reopen need the private directory from a successful baseline")
    root = args.directory or Path(tempfile.mkdtemp(prefix="vncode-code-doctor-live-"))
    if not root.is_dir() or stat.S_IMODE(root.stat().st_mode) & 0o077:
        parser.error("directory must exist and be accessible only to its owner (0700)")
    if args.mode == "baseline" and (root / "sdk-profile").exists():
        parser.error("baseline requires a fresh profile; use a new private directory")
    if args.mode != "reopen":
        required = ["VNCODE_WB_TEST_API_KEY"]
        if args.mode == "baseline":
            required.append("VNCODE_CATALOG_TEST_API_KEY")
        if any(not os.environ.get(name) for name in required):
            parser.error("required test credentials are not configured; do not paste them into logs")
    names = {"baseline": "CodeDoctorLiveWbCatalog", "policy": "CodeDoctorWbGtinPolicyProbe", "reopen": "CodeDoctorLiveCacheReopen"}
    summaries = {"baseline": "sdk-summary.json", "policy": "wb-policy-summary.json", "reopen": "sdk-reopen-summary.json"}
    name = names[args.mode]
    source = Path(__file__).resolve().parent / (name + ".java")
    cp = str(args.jar.resolve()) + os.pathsep + str(Path("target/test-classes").resolve()) + os.pathsep + args.classpath_file.read_text().strip()
    classes = root / "probe-classes"
    classes.mkdir(exist_ok=True)
    command = [str(args.jdk_home / "bin/javac"), "-cp", cp, "-d", str(classes), str(source)]
    os.umask(0o077)
    with (root / (args.mode + "-probe.log")).open("w") as log:
        subprocess.run(command, check=True, stdout=log, stderr=subprocess.STDOUT)
        command = [str(args.jdk_home / "bin/java"), "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn"]
        if args.mode != "reopen":
            proxy = urlparse(os.environ.get("HTTPS_PROXY", os.environ.get("https_proxy", "")))
            if not proxy.hostname:
                parser.error("session HTTPS_PROXY is required for live reads")
            command += ["-Dhttps.proxyHost=" + proxy.hostname, "-Dhttps.proxyPort=" + str(proxy.port or 8080), "-Dhttp.proxyHost=" + proxy.hostname, "-Dhttp.proxyPort=" + str(proxy.port or 8080), "-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"]
        command += ["-cp", str(classes) + os.pathsep + cp, "com.vncode.app.integration.znack.registration." + name, str(root)]
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    print("Private directory:", root)
    print("Probe exit:", result.returncode)
    if result.returncode == 0:
        print(json.dumps(json.loads((root / summaries[args.mode]).read_text()), indent=2))
    else:
        print("Inspect the private log locally; do not copy raw responses or credentials into Git.")
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
