#!/usr/bin/env python3
"""Verify the actual, complete VN Code 1.6.10 source ZIP without extracting or running it.

Only the explicit receipt output is written. Git, source, ZIP, app and remote services
are never modified. Actual ZIP and full final/code commit pins are mandatory.
"""
import argparse
import datetime
import hashlib
import json
import pathlib
import posixpath
import re
import stat
import subprocess
import sys
import unicodedata
import xml.etree.ElementTree as ET
import zipfile

VERSION = '1.6.10'
PREFIX = 'VN-code-1.6.10-source/'
BASE = '4ef0f1e2b8ef7b28190de3034b0c67ebf615be0b'
CODE = '75691c68fa14f728856b0418fb6008083787ef7c'
EXPECTED_RUNTIME = 800
EXPECTED_CHANGED = 10
CRITICAL = (
    'pom.xml', 'AGENTS.md', 'README.md', 'docs/memory/CURRENT.md',
    'mvnw', 'mvnw.cmd', '.mvn/wrapper/maven-wrapper.jar',
    '.mvn/wrapper/maven-wrapper.properties', 'build.bat', 'build.sh',
    'src/main/java/com/vncode/app/Launcher.java', 'src/main/resources/app.properties',
    'src/main/resources/com/vncode/app/ui/kizmapping/kiz-mapping-view.fxml',
    'src/main/resources/com/vncode/app/i18n/messages_vi.properties',
)
PROHIBITED_PARTS = {'.git', 'target', 'node_modules', 'out', '.venv', '__pycache__',
                    '.aws', '.ssh', '.gnupg', '.vncode', 'appdata', 'localappdata',
                    'userdata', 'user-data', 'profiles'}
PATTERNS = {
    'github_credential': re.compile(rb'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})'),
    'jwt_literal': re.compile(rb'eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}'),
    'signed_download_url': re.compile(rb'(?:[?&]sig=[A-Za-z0-9%+/]{20,}|X-Amz-Signature=[a-fA-F0-9]{32,})'),
    'private_key_pem': re.compile(rb'-----BEGIN (?:RSA |EC |ENCRYPTED )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{128,}-----END (?:RSA |EC |ENCRYPTED )?PRIVATE KEY-----'),
}
# Reviewed existing fixture: diagnostics-redaction test, not a seller credential.
# The literal has only a sub claim and an invalid 12-byte ES256 signature (64 required).
# Pin the entire test blob; no other test file/pattern is implicitly allowed.
KNOWN_FIXTURE = {
    ('src/test/java/com/vncode/app/ui/StartupProgressTest.java', 'jwt_literal'):
        '3b258a7170f0a590b43ffc6c1b6d17f94eaeff02f1af3dae5a5f142024a69364',
}


class VerificationError(Exception):
    pass


def require(condition, reason):
    if not condition:
        raise VerificationError(reason)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def classify_credential(path, pattern, digest):
    if KNOWN_FIXTURE.get((path, pattern)) == digest:
        return 'CONFIRMED_SYNTHETIC_DIAGNOSTIC_TEST_FIXTURE'
    return 'UNREVIEWED_POTENTIAL_CREDENTIAL_REQUIRES_REVIEW'


def file_hash(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def validate_member_name(name, prefix=PREFIX):
    require(isinstance(name, str) and '\x00' not in name and '\\' not in name,
            'ZIP name contains NUL/backslash or is not text')
    require(not name.startswith('/') and name.startswith(prefix), 'ZIP member outside the exact source prefix')
    directory = name.endswith('/')
    relative = name[len(prefix):].rstrip('/') if directory else name[len(prefix):]
    if not relative:
        require(name == prefix and directory, 'Invalid empty ZIP member')
        return '', True
    require(not relative.startswith('/'), 'Absolute ZIP member below prefix')
    components = relative.split('/')
    reserved = {'CON', 'PRN', 'AUX', 'NUL'} | {f'{p}{i}' for p in ('COM', 'LPT') for i in range(1, 10)}
    for part in components:
        require(part not in ('', '.', '..') and ':' not in part, 'ZIP member has traversal/drive/alternate-stream component')
        require(not any(ord(c) < 32 for c in part), 'ZIP member contains control characters')
        require(part == part.rstrip(' .'), 'ZIP member has a Windows-ambiguous trailing dot/space')
        require(part.split('.')[0].upper() not in reserved, 'ZIP member uses a Windows reserved name')
        require(part.lower() not in PROHIBITED_PARTS, 'ZIP contains generated output or a user-profile/credential directory')
    require(components[-1].lower() not in {'.env', '.env.local', '.env.production'}, 'ZIP contains a runtime environment secret file')
    return relative, directory


def validate_entries(infos, expected, expected_sizes):
    files = {}
    directories = set()
    seen = set()
    windows_names = set()
    allowed_directories = {''}
    for path in expected:
        parts = pathlib.PurePosixPath(path).parts
        allowed_directories.update('/'.join(parts[:i]) for i in range(1, len(parts)))
    for info in infos:
        require(info.orig_filename == info.filename, 'ZIP member name was truncated or changed by the ZIP parser')
        require(info.filename not in seen, 'Duplicate ZIP member name')
        seen.add(info.filename)
        relative, directory = validate_member_name(info.filename)
        normalized = unicodedata.normalize('NFC', relative).casefold()
        require(normalized not in windows_names, 'ZIP has a case/Unicode-normalization path collision')
        windows_names.add(normalized)
        require(not info.comment, 'Unexpected per-file ZIP comment outside the Git source blobs')
        position = 0
        seen_extra = set()
        while position < len(info.extra):
            require(position + 4 <= len(info.extra), 'Truncated ZIP extra-field header')
            kind = int.from_bytes(info.extra[position:position + 2], 'little')
            size = int.from_bytes(info.extra[position + 2:position + 4], 'little')
            position += 4
            require(position + size <= len(info.extra) and kind not in seen_extra, 'Truncated or duplicate ZIP extra field')
            seen_extra.add(kind)
            extra = info.extra[position:position + size]
            position += size
            require(kind in (0x0001, 0x5455), 'Unexpected ZIP extra field, including alternate Unicode path metadata')
            if kind == 0x5455:
                require(size in (5, 9, 13) and extra[0] & ~7 == 0, 'Invalid ZIP timestamp extra field')
            else:
                require(size in (8, 16, 24, 28), 'Invalid ZIP64 extra-field size')
        require(not info.flag_bits & 1, 'Encrypted ZIP member is not a complete ordinary source payload')
        require(info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED), 'Unsupported ZIP compression for ordinary Windows extraction')
        mode_type = stat.S_IFMT(info.external_attr >> 16)
        require(mode_type in (0, stat.S_IFREG, stat.S_IFDIR, stat.S_IFLNK), 'ZIP contains a special device/FIFO/socket entry')
        if directory:
            require(relative in allowed_directories and info.file_size == 0, 'Unexpected or nonempty ZIP directory entry')
            require(mode_type in (0, stat.S_IFDIR), 'ZIP directory has conflicting file type metadata')
            directories.add(relative)
            continue
        require(relative in expected, 'ZIP contains a file that is not tracked at the final commit')
        require(info.file_size == expected_sizes[expected[relative]['oid']], 'ZIP declared uncompressed size differs from the Git blob')
        mode = expected[relative]['mode']
        if mode == '120000':
            require(mode_type == stat.S_IFLNK, 'Tracked symlink lost its symlink metadata')
        else:
            require(mode_type in (0, stat.S_IFREG), 'Regular source file became a symlink or directory')
            if mode == '100755':
                require(info.external_attr >> 16 & 0o111, 'Executable source wrapper/script lost execute bits')
        files[relative] = info
    require(set(files) == set(expected), 'ZIP is missing one or more final-commit tracked source files')
    return files, directories


class Git:
    def __init__(self, repo):
        self.repo = repo
        self.process = None
        self.hash_cache = {}

    def command(self, *args, data=None):
        process = subprocess.run(['git', '--no-replace-objects', *args], cwd=self.repo,
                                 input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        require(process.returncode == 0, 'A required read-only Git command failed: ' + args[0])
        return process.stdout

    def commit(self, ref):
        require(re.fullmatch('[a-f0-9]{40}', ref) is not None, 'Provide an exact full 40-character commit, not a mutable ref')
        actual = self.command('rev-parse', '--verify', ref + '^{commit}').decode().strip()
        require(actual == ref, 'Commit pin did not resolve exactly')
        return actual

    def tree(self, ref):
        entries = {}
        directories = set()
        for record in self.command('ls-tree', '-r', '-t', '-z', '--full-tree', ref).split(b'\0'):
            if not record:
                continue
            header, raw_path = record.split(b'\t', 1)
            mode, kind, oid = header.decode('ascii').split(' ')
            path = raw_path.decode('utf-8')
            validate_member_name(PREFIX + path + ('/' if kind == 'tree' else ''))
            if kind == 'tree':
                directories.add(path)
            else:
                entries[path] = {'mode': mode, 'kind': kind, 'oid': oid}
        return entries, directories

    def blob_sizes(self, entries):
        oids = sorted({entry['oid'] for entry in entries.values()})
        result = self.command('cat-file', '--batch-check=%(objectname) %(objecttype) %(objectsize)',
                              data=('\n'.join(oids) + '\n').encode('ascii'))
        sizes = {}
        for line in result.decode('ascii').splitlines():
            oid, kind, size = line.split(' ')
            require(kind == 'blob', 'Tracked source includes a non-blob object')
            sizes[oid] = int(size)
        require(set(sizes) == set(oids), 'Git blob size inventory is incomplete')
        return sizes

    def blob(self, oid):
        if self.process is None:
            self.process = subprocess.Popen(['git', '--no-replace-objects', 'cat-file', '--batch'],
                                            cwd=self.repo, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                            stderr=subprocess.DEVNULL)
        self.process.stdin.write((oid + '\n').encode('ascii'))
        self.process.stdin.flush()
        header = self.process.stdout.readline().decode('ascii').rstrip('\n').split(' ')
        require(len(header) == 3 and header[0] == oid and header[1] == 'blob', 'Unexpected Git cat-file response')
        size = int(header[2])
        data = self.process.stdout.read(size)
        require(len(data) == size and self.process.stdout.read(1) == b'\n', 'Truncated Git blob read')
        return data

    def blob_hash(self, oid):
        if oid not in self.hash_cache:
            self.hash_cache[oid] = sha256(self.blob(oid))
        return self.hash_cache[oid]

    def attributes(self, ref, paths):
        values = self.command('check-attr', '--source=' + ref, '-z', '--stdin',
                              'export-ignore', 'export-subst', 'filter',
                              data=b'\0'.join(path.encode('utf-8') for path in sorted(paths)) + b'\0').split(b'\0')
        return {(values[i].decode(), values[i + 1].decode()): values[i + 2].decode()
                for i in range(0, len(values) - 2, 3)}

    def close(self):
        if self.process is not None:
            self.process.stdin.close()
            self.process.stdout.close()
            self.process.wait(timeout=10)


def root_versions(data):
    root = ET.fromstring(data)
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    return root.find('m:version', ns), root.find('m:properties/m:app.version', ns), root


def check_preservation(git, before, code, final, preservation_path):
    supplied = json.loads(preservation_path.read_text(encoding='utf-8-sig'))
    require(supplied.get('kind') == 'VN_CODE_169_TO_1610_SOURCE_PRESERVATION' and supplied.get('result') == 'PASS', 'Wrong source-preservation receipt')
    require(supplied.get('published169Source') == BASE and supplied.get('candidateCodeSource') == CODE, 'Source-preservation pins differ')
    runtime = lambda tree: {p: entry for p, entry in tree.items() if p.startswith('src/main/')}
    a, b, c = runtime(before), runtime(code), runtime(final)
    require(len(a) == EXPECTED_RUNTIME and set(a) == set(b) == set(c), 'Baseline runtime file set changed, was reduced, or is incomplete')
    require(all(b[p] == c[p] for p in b), 'Final documentation ref changed runtime after the tested code commit')
    changes = []
    for path in sorted(a):
        if a[path] != b[path]:
            changes.append({'path': path, 'beforeGitBlob': a[path]['oid'], 'afterGitBlob': b[path]['oid'],
                            'beforeSha256': git.blob_hash(a[path]['oid']), 'afterSha256': git.blob_hash(b[path]['oid'])})
    require(len(changes) == EXPECTED_CHANGED, 'Unexpected runtime patch scope; expected exactly ten modified files')
    require(supplied.get('runtimeFileCountBefore') == EXPECTED_RUNTIME and supplied.get('runtimeFileCountAfter') == EXPECTED_RUNTIME,
            'Runtime count in source-preservation receipt differs')
    require(supplied.get('runtimeUnchangedFiles') == EXPECTED_RUNTIME - EXPECTED_CHANGED and supplied.get('runtimeAdded') == [] and supplied.get('runtimeRemoved') == [],
            'Source-preservation unchanged/add/delete counts differ')
    require(supplied.get('runtimeChanged') == changes, 'Source-preservation paths/blob IDs/SHA-256 differ from actual Git objects')
    require(all(final.get(p) == entry for p, entry in code.items() if p.startswith('src/')), 'Final ref changed or removed tested source/tests')
    require(not any(p.startswith('src/') and p not in code for p in final), 'Final ref added source/tests after the tested commit')
    for path in ('pom.xml', 'mvnw', 'mvnw.cmd', '.mvn/wrapper/maven-wrapper.jar', '.mvn/wrapper/maven-wrapper.properties', 'build.bat', 'build.sh'):
        require(final.get(path) == code.get(path) and path in code, 'Final ref changed a tested build input: ' + path)
    old_version, old_app, old_pom = root_versions(git.blob(before['pom.xml']['oid']))
    new_version, new_app, new_pom = root_versions(git.blob(code['pom.xml']['oid']))
    require(old_version is not None and old_app is not None and old_version.text == old_app.text == '1.6.9', 'Unexpected baseline POM version')
    require(new_version is not None and new_app is not None and new_version.text == new_app.text == VERSION, 'POM project/app versions are not both 1.6.10')
    old_version.text = old_app.text = VERSION
    require(ET.tostring(old_pom) == ET.tostring(new_pom), 'POM changed beyond the two declared version fields')
    require(supplied.get('dependencyAndBuildPluginSectionsUnchanged') is True, 'Source-preservation build sections not marked unchanged')
    return {'baseline': BASE, 'codeCommit': CODE, 'runtimeBefore': len(a), 'runtimeAfter': len(c),
            'unchanged': EXPECTED_RUNTIME - EXPECTED_CHANGED, 'modified': changes, 'added': [], 'removed': [],
            'finalRuntimeAndTestsExactlyMatchCodeRef': True, 'buildInputsExactlyMatchCodeRef': True,
            'pomOnlyTwoVersionFieldsChanged': True, 'inputReceiptSha256': file_hash(preservation_path)}


def verify(args, diagnostics):
    repo, archive = args.repo.resolve(), args.zip.resolve()
    require(repo.is_dir() and archive.is_file(), 'Actual repository and actual ZIP are required')
    require(args.code_ref == CODE, 'This verifier is pinned to the reviewed code commit ' + CODE)
    git = Git(repo)
    try:
        final_ref, code_ref, baseline = git.commit(args.ref), git.commit(args.code_ref), git.commit(BASE)
        git.command('merge-base', '--is-ancestor', baseline, code_ref)
        git.command('merge-base', '--is-ancestor', code_ref, final_ref)
        before, _ = git.tree(baseline)
        code, _ = git.tree(code_ref)
        final, directories = git.tree(final_ref)
        links = [p for p, x in final.items() if x['kind'] == 'commit' or x['mode'] == '160000']
        diagnostics['gitlinks'] = links
        require(not links, 'Git submodules are not a self-contained complete Git archive; include their pinned content explicitly')
        require(all(x['kind'] == 'blob' and x['mode'] in ('100644', '100755', '120000') for x in final.values()), 'Unsupported Git object/mode in source tree')
        attrs = git.attributes(final_ref, set(final) | directories)
        active = lambda value: value not in ('unspecified', 'unset', 'false')
        ignored_nodes = {p for (p, key), value in attrs.items() if key == 'export-ignore' and active(value)}
        ignored_files = sorted(p for p in final if p in ignored_nodes or any(str(parent) in ignored_nodes for parent in pathlib.PurePosixPath(p).parents if str(parent) != '.'))
        diagnostics['exportIgnoredNodes'] = sorted(ignored_nodes)
        diagnostics['exportIgnoredTrackedFiles'] = ignored_files
        diagnostics['exportSubstPaths'] = sorted(p for (p, key), value in attrs.items() if key == 'export-subst' and active(value))
        diagnostics['lfsPaths'] = sorted(p for (p, key), value in attrs.items() if key == 'filter' and value == 'lfs')
        require(not ignored_files, 'export-ignore would omit Git-tracked blobs; this ZIP cannot be called complete')
        require(not diagnostics['lfsPaths'], 'Tracked LFS pointers require their actual content; not a complete self-contained source ZIP')
        require(all(p in final for p in CRITICAL), 'A critical source/build/documentation file is missing from the final Git tree')
        require(any(p.startswith('src/main/resources/') and p.endswith('.fxml') for p in final), 'JavaFX resources missing')
        require(args.preservation.is_file(), 'The actual source-preservation.json receipt is required')
        preservation = check_preservation(git, before, code, final, args.preservation)
        sizes = git.blob_sizes(final)
        scans, manifest, critical, executable = [], [], {}, []
        with zipfile.ZipFile(archive) as bundle:
            files, zip_directories = validate_entries(bundle.infolist(), final, sizes)
            require(bundle.testzip() is None, 'ZIP CRC verification failed')
            comment = bundle.comment
            require(comment == final_ref.encode(), 'Git archive ZIP comment must bind the exact final commit')
            for name, expression in PATTERNS.items():
                if expression.search(comment):
                    scans.append({'path': 'ZIP_COMMENT', 'pattern': name, 'classification': 'REQUIRES_REVIEW_OUTSIDE_TEST_FIXTURES'})
            symlinks = [p for p, e in final.items() if e['mode'] == '120000']
            for path in symlinks:
                require(not any(other.startswith(path + '/') for other in final), 'ZIP writes entries below a symlink ancestor')
            for path in sorted(final):
                entry = final[path]
                source, data = git.blob(entry['oid']), bundle.read(files[path])
                require(data == source, 'ZIP bytes differ from the pinned Git blob: ' + path)
                digest = sha256(data)
                if entry['mode'] == '120000':
                    target = data.decode('utf-8')
                    require(target and not target.startswith('/') and '\\' not in target and ':' not in target and '\x00' not in target, 'Unsafe Git symlink target')
                    normalized = posixpath.normpath(posixpath.join(posixpath.dirname(path), target))
                    require(normalized != '..' and not normalized.startswith('../'), 'Git symlink escapes source prefix')
                if entry['mode'] == '100755':
                    executable.append(path)
                for name, expression in PATTERNS.items():
                    if expression.search(data):
                        scans.append({'path': path, 'pattern': name,
                                      'classification': classify_credential(path, name, digest),
                                      'sourceBlobSha256': digest})
                metadata = {'path': path, 'mode': entry['mode'], 'gitBlob': entry['oid'], 'bytes': len(data), 'sha256': digest}
                manifest.append(metadata)
                if path in CRITICAL:
                    critical[path] = metadata
            diagnostics['credentialPatternFindings'] = scans
            require(all(x['classification'] == 'CONFIRMED_SYNTHETIC_DIAGNOSTIC_TEST_FIXTURE' for x in scans),
                    'Unreviewed potential credential material needs manual review; values are not logged')
        return {'kind': 'VN_CODE_1610_COMPLETE_SOURCE_ZIP_VERIFICATION',
                'result': 'PASS_WITH_CONFIRMED_TEST_FIXTURE' if scans else 'PASS',
                'verifiedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'version': VERSION, 'finalCommit': final_ref, 'codeCommit': code_ref, 'baseline': baseline,
                'zip': {'path': str(archive), 'bytes': archive.stat().st_size, 'sha256': file_hash(archive),
                        'prefix': PREFIX, 'crcPassed': True, 'fileCount': len(files), 'directoryEntryCount': len(zip_directories),
                        'allGitTrackedBlobsIncluded': True, 'allBlobBytesExactlyMatchFinalRef': True,
                        'gitArchiveCommentMatchesFinalRef': comment == final_ref.encode()},
                'sourcePreservation': preservation, 'criticalFiles': critical, 'executableGitScriptsPreserved': executable,
                'symlinkCount': len(symlinks), 'gitlinkCount': len(links), 'exportAttributes': diagnostics,
                'fileManifestSha256': sha256(json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()),
                'credentialPatternScan': {'patterns': list(PATTERNS), 'findings': scans,
                                          'limitation': 'Generic byte patterns do not prove absence of every possible credential. Only the exact hash-pinned, reviewed existing diagnostics test fixture is allowed and reported; any other match, including in tests, requires review. Values are never printed or silently removed.'},
                'helperSha256': file_hash(pathlib.Path(__file__)),
                'scope': 'Read-only Git/ZIP bytes and source-preservation receipt; no extraction, compile, app/API/signing/purchase execution.',
                'limitations': ['Source byte preservation does not prove absence of indirect behavior changes. Functional/build results are separate evidence.',
                               'No actual credential values or fixture values are included in this receipt.']}
    finally:
        git.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=pathlib.Path, required=True)
    parser.add_argument('--zip', type=pathlib.Path, required=True)
    parser.add_argument('--ref', required=True, help='Exact final documentation commit used by git archive')
    parser.add_argument('--code-ref', required=True, help='Pinned tested code commit, exactly ' + CODE)
    parser.add_argument('--preservation', type=pathlib.Path, default=pathlib.Path(__file__).resolve().parent / 'source-preservation.json')
    parser.add_argument('--receipt', type=pathlib.Path, required=True)
    args = parser.parse_args()
    require(not args.receipt.exists(), 'Refuse to overwrite an existing receipt; preserve previous outcomes')
    output = args.receipt.resolve()
    require(output not in {args.zip.resolve(), args.preservation.resolve(), pathlib.Path(__file__).resolve()}, 'Receipt would overwrite an input')
    require(not output.is_relative_to(args.repo.resolve()), 'Receipt must be outside the source repository to keep verification read-only')
    diagnostics = {}
    try:
        result = verify(args, diagnostics)
    except Exception as error:
        result = {'kind': 'VN_CODE_1610_COMPLETE_SOURCE_ZIP_VERIFICATION', 'result': 'FAIL',
                  'verifiedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  'requestedFinalRef': args.ref, 'requestedCodeRef': args.code_ref,
                  'zipPath': str(args.zip.resolve()),
                  'reason': str(error), 'exceptionType': type(error).__name__, 'diagnostics': diagnostics,
                  'scope': 'No successful ZIP or functional acceptance is implied by this failure receipt.'}
        args.receipt.parent.mkdir(parents=True, exist_ok=True)
        args.receipt.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({'result': 'FAIL', 'reason': str(error), 'receipt': str(output)}))
        return 1
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    args.receipt.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'result': result['result'], 'finalCommit': result['finalCommit'], 'files': result['zip']['fileCount'],
                      'zipSha256': result['zip']['sha256'], 'receipt': str(output)}))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except VerificationError as error:
        print('Verification input error: ' + str(error), file=sys.stderr)
        sys.exit(2)
