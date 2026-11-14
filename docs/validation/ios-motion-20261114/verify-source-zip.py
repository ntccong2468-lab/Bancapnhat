#!/usr/bin/env python3
"""Verify the actual, complete VN Code 1.6.10 source ZIP without extracting or running it.

Only the explicit receipt output is written. Git, source, ZIP, app and remote services
are never modified. Actual ZIP and full final/code commit pins are mandatory.
"""
import argparse
import base64
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
PREFIX = 'VN-code-1.6.10-source-floating-glass/'
BASE = '4ef0f1e2b8ef7b28190de3034b0c67ebf615be0b'
CODE = '43b82cca683b29e783a33df995afd675a76aa0dd'
DOCS_ANCHOR = '68f95dfeab1ba487f7063aed424840a6427b2ff5'
PREVIOUS_CODE = 'a86048ec57521bc4d32b0a27c86d2eb1ee70fcb3'
EXPECTED_PREVIOUS_RUNTIME = 801
EXPECTED_NON_UI_BUSINESS_RUNTIME = 603
REVIEWED_UI_MODIFICATIONS = {'src/main/java/com/vncode/app/shared/MotionBoundary.java', 'src/main/java/com/vncode/app/ui/workspace/WorkspaceCommandPalette.java', 'src/main/java/com/vncode/app/ui/znackregistration/RegistrationProvenanceDialog.java', 'src/main/java/com/vncode/app/ui/znackregistration/RegistrationDiagnostics.java', 'src/main/java/com/vncode/app/ui/znackregistration/OzonRegistrationPane.java', 'src/main/java/com/vncode/app/shared/AlertService.java', 'src/main/java/com/vncode/app/shared/WorkspaceMotion.java', 'src/main/resources/com/vncode/app/ui/print/print-template-designer-view.fxml', 'src/main/java/com/vncode/app/shared/ThemeService.java', 'src/main/java/com/vncode/app/ui/listingcopy/ListingCopyPane.java', 'src/main/java/com/vncode/app/ui/znackregistration/ZnackCardRegistrationController.java', 'src/main/java/com/vncode/app/ui/license/LicensePane.java', 'src/main/java/com/vncode/app/ui/workspace/JobCenterDialog.java', 'src/main/resources/com/vncode/app/styles/theme.css', 'src/main/java/com/vncode/app/shared/WorkspaceMotionTokens.java', 'src/main/resources/com/vncode/app/i18n/messages_ru.properties', 'src/main/resources/com/vncode/app/styles/workspace-controls.css', 'src/main/resources/com/vncode/app/i18n/messages_zh.properties', 'src/main/java/com/vncode/app/ui/znackregistration/RegistrationConnectionDialog.java', 'src/main/java/com/vncode/app/ui/workspace/WorkspaceHeaderController.java', 'src/main/java/com/vncode/app/ui/kiz/KizWarehousePane.java', 'src/main/resources/com/vncode/app/i18n/messages_en.properties', 'src/main/java/com/vncode/app/ui/znackregistration/RegistrationRepairHistoryDialog.java', 'src/main/java/com/vncode/app/ui/products/ProductsPane.java', 'src/main/java/com/vncode/app/ui/workspace/HomeController.java', 'src/main/java/com/vncode/app/ui/znackregistration/ZnackMappingReviewDialog.java', 'src/main/resources/com/vncode/app/i18n/messages_vi.properties', 'src/main/java/com/vncode/app/ui/fbo/FboPreflightDialog.java'}
REVIEWED_UI_ADDITIONS = {'src/main/java/com/vncode/app/shared/WorkspaceDialogChrome.java', 'src/main/java/com/vncode/app/shared/WorkspaceRoundedControlClip.java', 'src/main/java/com/vncode/app/shared/WorkspaceInteractionMotion.java', 'src/main/java/com/vncode/app/shared/WorkspaceBackdropMaterial.java'}
EXPECTED_BASE_RUNTIME = 800
PRODUCTION_PREDECESSOR = '830534fdfa2c2374579d415ad4d24100453b9dfa'
CORRECTED_TEST = 'src/test/java/com/vncode/app/shared/WorkspaceRoundedControlClipTest.java'
CORRECTED_TEST_SHA256 = '07e0ce60e0defca821171decaca9b2c570e2c4a3be6250b556d48e83a07c16f1'
CLIP_TEST_CHECKPOINT = '3c4783187a383f612f9a3668dc262188a51bc860'
FOCUS_PREDECESSOR = CLIP_TEST_CHECKPOINT
FOCUS_CSS = 'src/main/resources/com/vncode/app/styles/workspace-controls.css'
FOCUS_TEST = 'src/test/java/com/vncode/app/shared/WorkspaceMaterialRenderingTest.java'
FOCUS_NOTES = 'docs/releases/VN-code-1.6.10.md'
FOCUS_CSS_SHA256 = '26e31d66000f44d8846dfd9253a4a93b7f1ef5d2cc5ebb53334be97df597b33d'
FOCUS_TEST_SHA256 = '8364034b6122c2aee42649dcddec8e4c723939ce708c72ca910df4fe02e7e204'
FOCUS_TEST_ADDITION_SHA256 = 'cef162454882f844de115f9cd451933b071bdd71d659fa5aec4ebf6631abb528'
FOCUS_CHECKPOINT = '3ce0a0ab35089078da480f93a4dddf3adc880da6'
DYNAMIC_PREDECESSOR = '5373f230b36bab763aaaf313d31762e406fc5ac4'
DYNAMIC_HELPER = 'src/main/java/com/vncode/app/shared/WorkspaceBackdropMaterial.java'
DYNAMIC_TEST = 'src/test/java/com/vncode/app/shared/WorkspaceAllSurfaceGlassTest.java'
DYNAMIC_DOC = 'docs/research/2026-11-14-code-quality-proposals.md'
DYNAMIC_HELPER_SHA256 = '6d305574494342cbd36c17c3b828b3b12c3e485b0d645bbec601572b330cda78'
DYNAMIC_TEST_SHA256 = '7da9da7facb693438ce3e340f642aff65064df37703694eb78dc0d134c0a2576'
DYNAMIC_DOC_SHA256 = '77d5eecf801fe4e12ca8697de31b9d51edffd1daa7d818930c27f90640917298'
DYNAMIC_TEST_ADDITION_SHA256 = '69cd834dddd49fd10aab08f4e5b3cd265ba062317f22bc25ae644728bb244aed'
PRESERVATION_SHA256 = 'fec67cc6a2b7f16695d4c256fece558891040711192f675f16fdaef0354b75a4'
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


def reviewed_fixture_shape(data):
    """Only the already hash-pinned diagnostics fixture is synthetic, with an invalid signature."""
    try:
        values = PATTERNS['jwt_literal'].findall(data)
        if len(values) != 1:
            return False
        header,payload,signature = values[0].split(b'.')
        decode = lambda value: base64.urlsafe_b64decode(value + b'=' * (-len(value) % 4))
        return (json.loads(decode(header)) == {'alg':'ES256'}
                and set(json.loads(decode(payload))) == {'sub'}
                and len(decode(signature)) == 12)
    except (ValueError,TypeError,UnicodeError):
        return False


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


def check_preservation(git, before, code, final, preservation_path, expected_sha=PRESERVATION_SHA256):
    require(expected_sha == PRESERVATION_SHA256, 'Unreviewed source-preservation SHA-256 pin')
    require(file_hash(preservation_path) == expected_sha, 'Source-preservation bytes differ from the reviewed SHA-256 pin')
    supplied = json.loads(preservation_path.read_text(encoding='utf-8-sig'))
    require(supplied.get('baseline') == BASE and supplied.get('sourceCommit') == CODE, 'Source-preservation pins differ')
    require(supplied.get('published') is False, 'Source manifest must not imply a publication')
    runtime = lambda tree: {p: entry for p, entry in tree.items() if p.startswith('src/main/')}
    a, b, c = runtime(before), runtime(code), runtime(final)
    require(len(a) == EXPECTED_BASE_RUNTIME, 'Unexpected baseline runtime file count')
    added, removed = sorted(set(b)-set(a)), sorted(set(a)-set(b))
    modified = sorted(p for p in set(a)&set(b) if a[p] != b[p])
    unchanged = sum(a[p] == b[p] for p in set(a)&set(b))
    require(not removed, 'A baseline runtime file was removed or renamed')
    require(added == supplied.get('added') and removed == supplied.get('removed')
            and modified == supplied.get('modified'), 'Runtime additions/removals/modifications differ from the reviewed manifest')
    require(set(b) == set(c) and all(b[p] == c[p] for p in b),
            'Final documentation ref changed, added or removed runtime after the tested code commit')
    require(supplied.get('baselineRuntimeFiles') == len(a) and supplied.get('runtimeFiles') == len(b)
            and len(b) == EXPECTED_BASE_RUNTIME + len(added) and supplied.get('unchanged') == unchanged,
            'Source-preservation runtime counts differ from actual Git objects')
    require(supplied.get('runtimeBlobs') == {p: entry['oid'] for p,entry in b.items()},
            'Reviewed runtime blob inventory differs from the pinned code tree')
    changes = [{'path':p, 'beforeGitBlob':a[p]['oid'], 'afterGitBlob':b[p]['oid'],
                'beforeSha256':git.blob_hash(a[p]['oid']), 'afterSha256':git.blob_hash(b[p]['oid']),
                'beforeMode':a[p]['mode'], 'afterMode':b[p]['mode']} for p in modified]
    additions = [{'path':p,'afterGitBlob':b[p]['oid'],'afterSha256':git.blob_hash(b[p]['oid']),
                  'mode':b[p]['mode']} for p in added]
    require(all(final.get(p) == entry for p, entry in code.items() if p.startswith('src/')), 'Final ref changed or removed tested source/tests')
    require(not any(p.startswith('src/') and p not in code for p in final), 'Final ref added source/tests after the tested commit')
    build_paths = {p for p in code if p.startswith('.mvn/')}
    build_paths.update(('pom.xml','mvnw','mvnw.cmd','build.bat','build.sh'))
    require(not any(p.startswith('.mvn/') and p not in code for p in final), 'Final ref added an untested Maven build input')
    for path in sorted(build_paths):
        require(final.get(path) == code.get(path) and path in code, 'Final ref changed a tested build input: ' + path)
    old_version, old_app, old_pom = root_versions(git.blob(before['pom.xml']['oid']))
    new_version, new_app, new_pom = root_versions(git.blob(code['pom.xml']['oid']))
    require(old_version is not None and old_app is not None and old_version.text == old_app.text == '1.6.9', 'Unexpected baseline POM version')
    require(new_version is not None and new_app is not None and new_version.text == new_app.text == VERSION, 'POM project/app versions are not both 1.6.10')
    old_version.text = old_app.text = VERSION
    require(ET.tostring(old_pom) == ET.tostring(new_pom), 'POM changed beyond the two declared version fields')
    return {'baseline':BASE,'codeCommit':CODE,'runtimeBefore':len(a),'runtimeAfter':len(c),
            'unchanged':unchanged,'modified':changes,'added':additions,'removed':removed,
            'baselinePathsRetained':True,'onlyReviewedRuntimeAdditions':True,
            'finalRuntimeAndTestsExactlyMatchCodeRef':True,'buildInputsExactlyMatchCodeRef':True,
            'pomOnlyTwoVersionFieldsChanged':True,'inputReceiptSha256':file_hash(preservation_path)}


def check_ui_predecessor(git, code, final):
    """Keep every prior business/runtime blob outside the explicitly reviewed UI scope."""
    previous, _ = git.tree(git.commit(PREVIOUS_CODE))
    runtime = lambda tree: {p:entry for p,entry in tree.items() if p.startswith('src/main/')}
    a,b,c = runtime(previous),runtime(code),runtime(final)
    require(len(a) == EXPECTED_PREVIOUS_RUNTIME, 'Unexpected pre-UI runtime file count')
    require(not (set(a)-set(b)), 'Pre-UI source lost a runtime file')
    added,modified = set(b)-set(a),{p for p in set(a)&set(b) if a[p]!=b[p]}
    business = {p for p in a if p.startswith('src/main/java/')
                and not p.startswith(('src/main/java/com/vncode/app/ui/', 'src/main/java/com/vncode/app/shared/'))}
    require(len(business) == EXPECTED_NON_UI_BUSINESS_RUNTIME, 'Unexpected non-UI business runtime file count')
    require(all(b.get(p)==a[p] for p in business),
            'UI phase changed non-UI business/config/model/integration runtime')
    require(added == REVIEWED_UI_ADDITIONS and modified == REVIEWED_UI_MODIFICATIONS,
            'UI phase changed business/runtime outside the reviewed UI path sets')
    require(len(b) == 805 and len(c) == 805, 'Unexpected final UI runtime count')
    require(previous.get('pom.xml') == code.get('pom.xml') == final.get('pom.xml'),
            'UI phase changed the pre-UI POM or dependency inputs')
    unchanged = sorted(p for p in a if a[p] == b.get(p))
    return {'previousCodeCommit':PREVIOUS_CODE,'previousRuntimeFiles':len(a),
            'runtimeFiles':len(b),'modifiedUiPaths':sorted(modified),'addedUiPaths':sorted(added),
            'unchangedPriorRuntimeFiles':len(unchanged),'priorBusinessRuntimeOutsideUiScopeByteExact':True,
            'previousPomByteExact':True,'removed':[],
            'nonUiBusinessRuntimeFilesVerified':len(business),'nonUiBusinessRuntimeByteExact':True}


def check_test_only_successor(git, code, final):
    """Retain the reviewed 830534f -> 3c clip-fixture proof; it is a historical checkpoint."""
    previous,_=git.tree(git.commit(PRODUCTION_PREDECESSOR))
    checkpoint,_=git.tree(git.commit(CLIP_TEST_CHECKPOINT))
    before={p:e for p,e in previous.items() if p.startswith('src/')}
    after={p:e for p,e in checkpoint.items() if p.startswith('src/')}
    changed={p for p in set(before)|set(after) if before.get(p)!=after.get(p)}
    require(changed == {CORRECTED_TEST}, 'Clip-fixture checkpoint changed more than the reviewed control clip test')
    require(CORRECTED_TEST in code and final.get(CORRECTED_TEST)==code[CORRECTED_TEST]==checkpoint[CORRECTED_TEST],
            'Final archive changed or removed the corrected test')
    require(git.blob_hash(code[CORRECTED_TEST]['oid']) == CORRECTED_TEST_SHA256,
            'Corrected control clip test SHA-256 differs from the reviewed input')
    require(previous.get('pom.xml') == checkpoint.get('pom.xml') == code.get('pom.xml') == final.get('pom.xml'),
            'Clip-fixture successor changed production POM input')
    return {'productionPredecessor':PRODUCTION_PREDECESSOR,'clipFixtureCheckpoint':CLIP_TEST_CHECKPOINT,
            'clipCheckpointProductionRuntimeByteExact':True,
            'onlyChangedCheckpointSourcePath':CORRECTED_TEST,'correctedTestSha256':CORRECTED_TEST_SHA256,
            'finalCorrectedTestByteExact':True,
            'scope':'Historical clip-fixture proof; the final focus CSS successor is checked separately.'}


def check_focus_successor(git, code, final):
    """Retain the exact 3c->3ce focus checkpoint, separate from the later attachment fix."""
    previous,_=git.tree(git.commit(FOCUS_PREDECESSOR))
    checkpoint,_=git.tree(git.commit(FOCUS_CHECKPOINT))
    changed={p for p in set(previous)|set(checkpoint) if previous.get(p)!=checkpoint.get(p)}
    require(changed == {FOCUS_CSS,FOCUS_TEST,FOCUS_NOTES},
            'Focus successor changed paths outside the reviewed CSS, additive test and release notes')
    for path in (FOCUS_CSS,FOCUS_TEST,FOCUS_NOTES):
        require(path in previous and path in checkpoint, 'Reviewed focus checkpoint input missing: '+path)
        require(previous[path]['mode']==checkpoint[path]['mode']=='100644', 'Focus successor changed an input mode')
    for path in (FOCUS_CSS,FOCUS_TEST):
        require(path in code and final.get(path)==code[path],
                'Final archive changed or removed a reviewed focus input: '+path)
        require(code[path]['mode']==checkpoint[path]['mode']=='100644', 'Focus successor changed an input mode')
    require(git.blob_hash(code[FOCUS_CSS]['oid']) == FOCUS_CSS_SHA256
            and code[FOCUS_CSS]==checkpoint[FOCUS_CSS],
            'Focused field CSS SHA-256 differs from the reviewed input')
    require(git.blob_hash(code[FOCUS_TEST]['oid']) == FOCUS_TEST_SHA256
            and code[FOCUS_TEST]==checkpoint[FOCUS_TEST],
            'Focused field regression test SHA-256 differs from the reviewed input')
    later={p for p in set(checkpoint)|set(code) if checkpoint.get(p)!=code.get(p)}
    require(all(p in (DYNAMIC_HELPER,DYNAMIC_TEST) or p.startswith('docs/')
                or p in ('DESIGN.md','PRODUCT.md','SOURCE-1.6.10.md') for p in later),
            'Focus successor changed paths outside the reviewed CSS checkpoint and explicit later attachment scope')
    before_css=git.blob(previous[FOCUS_CSS]['oid']); after_css=git.blob(checkpoint[FOCUS_CSS]['oid'])
    old_line=b'.workspace-theme .spinner:focused { -fx-border-color: -focus; -fx-effect: innershadow(gaussian,-focus,1,1,0,0); }'
    new_line=b'.workspace-theme .spinner:focused { -fx-border-color: -focus; }'
    require(before_css.count(old_line)==1 and before_css.replace(old_line,new_line,1)==after_css,
            'Focus CSS change is not exactly the reviewed inner-shadow removal')
    before_test=git.blob(previous[FOCUS_TEST]['oid']); after_test=git.blob(checkpoint[FOCUS_TEST]['oid'])
    begin=b'    @Test void focusedShopSelectorKeepsNativeContrastDuringAndAfterMaterialRefresh()'
    end=b'    private static void checkSettingsAt940'
    require(begin not in before_test and after_test.count(begin)==1 and before_test.count(end)==after_test.count(end)==1,
            'Focus native test addition has unexpected boundaries')
    old_prefix,old_suffix=before_test.split(end,1); new_prefix,new_suffix=after_test.split(begin,1)
    addition,new_suffix=new_suffix.split(end,1)
    require(old_prefix==new_prefix and old_suffix==new_suffix
            and sha256(begin+addition)==FOCUS_TEST_ADDITION_SHA256,
            'Focus successor weakened or changed pre-existing material assertions')
    require(previous.get('pom.xml')==checkpoint.get('pom.xml')==code.get('pom.xml')==final.get('pom.xml'),
            'Focus successor changed production POM input')
    return {'focusPredecessor':FOCUS_PREDECESSOR,'focusCheckpoint':FOCUS_CHECKPOINT,
            'changedCheckpointPaths':sorted(changed),'onlyCheckpointRuntimeChange':FOCUS_CSS,
            'focusCssSha256':FOCUS_CSS_SHA256,'focusTestSha256':FOCUS_TEST_SHA256,
            'exactInnerShadowRemoval':True,'oldMaterialTestAssertionsRetained':True,
            'additiveNativeFocusTestBlockSha256':FOCUS_TEST_ADDITION_SHA256,
            'allOtherCheckpointInputsByteExactToPredecessor':True,'finalFocusSourceInputsByteExact':True,
            'scope':'Historical focus checkpoint; the later logical attachment successor is checked independently.'}


def check_dynamic_successor(git, code, final):
    """Accept the exact null-Scene lifetime fix and two additive native regression cases."""
    focus,_=git.tree(git.commit(FOCUS_CHECKPOINT))
    previous,_=git.tree(git.commit(DYNAMIC_PREDECESSOR))
    docs_changed={p for p in set(focus)|set(previous) if focus.get(p)!=previous.get(p)}
    require(docs_changed=={DYNAMIC_DOC} and previous[DYNAMIC_DOC]['mode']==focus[DYNAMIC_DOC]['mode']=='100644'
            and git.blob_hash(previous[DYNAMIC_DOC]['oid'])==DYNAMIC_DOC_SHA256,
            'Intermediate focus-to-attachment predecessor differs beyond the pinned research documentation correction')
    changed={p for p in set(previous)|set(code) if previous.get(p)!=code.get(p)}
    require(changed=={DYNAMIC_HELPER,DYNAMIC_TEST},
            'Attachment successor changed paths outside the reviewed helper and additive native tests')
    for path in (DYNAMIC_HELPER,DYNAMIC_TEST):
        require(path in previous and path in code and final.get(path)==code[path],
                'Final archive changed or removed a reviewed attachment input: '+path)
        require(previous[path]['mode']==code[path]['mode']=='100644', 'Attachment successor changed an input mode')
    require(git.blob_hash(code[DYNAMIC_HELPER]['oid'])==DYNAMIC_HELPER_SHA256,
            'Logical attachment helper SHA-256 differs from the reviewed input')
    require(git.blob_hash(code[DYNAMIC_TEST]['oid'])==DYNAMIC_TEST_SHA256,
            'Logical attachment native test SHA-256 differs from the reviewed input')
    old=git.blob(previous[DYNAMIC_HELPER]['oid']); new=git.blob(code[DYNAMIC_HELPER]['oid'])
    old_line=b'                if (entry.getKey().getScene() != scene) { entry.getValue().dispose(); surfaces.remove(entry.getKey()); }'
    new_lines=(b'                Scene attached = entry.getKey().getScene();\n'
               b'                // Logical ScrollPane/Tab content may precede its native skin\'s Scene attachment.\n'
               b'                // Keep its scene listener until attachment; logical removal already rebuilds the tree.\n'
               b'                if (attached == null) continue;\n'
               b'                if (attached != scene) { entry.getValue().dispose(); surfaces.remove(entry.getKey()); }')
    require(old.count(old_line)==1 and old.replace(old_line,new_lines,1)==new,
            'Attachment helper is not exactly the reviewed null-Scene retention change')
    before_test=git.blob(previous[DYNAMIC_TEST]['oid']); after_test=git.blob(code[DYNAMIC_TEST]['oid'])
    imports=(b'import com.vncode.app.ui.workspace.WorkspaceViewport;\n',
             b'import javafx.scene.control.skin.ScrollPaneSkin;\n')
    require(all(value not in before_test and after_test.count(value)==1 for value in imports),
            'Attachment native test imports differ from the reviewed additions')
    normalized=after_test
    for value in imports: normalized=normalized.replace(value,b'',1)
    begin=b'    @Test void reducedNavigationMaterializesNewViewportWithoutThemeChange()'
    end=b'    private static TableView<String> table()'
    require(begin not in before_test and normalized.count(begin)==1
            and before_test.count(end)==normalized.count(end)==1,
            'Attachment native test addition has unexpected boundaries')
    old_prefix,old_suffix=before_test.split(end,1); new_prefix,new_suffix=normalized.split(begin,1)
    addition,new_suffix=new_suffix.split(end,1); block=begin+addition
    require(old_prefix==new_prefix and old_suffix==new_suffix and sha256(block)==DYNAMIC_TEST_ADDITION_SHA256
            and before_test.count(b'@Test void ')==5 and after_test.count(b'@Test void ')==7,
            'Attachment successor weakened or changed the five pre-existing material tests')
    require(len(after_test.splitlines())-len(before_test.splitlines())==49,
            'Attachment regression addition is not the reviewed49 lines')
    require(previous.get('pom.xml')==code.get('pom.xml')==final.get('pom.xml'),
            'Attachment successor changed production POM input')
    return {'attachmentPredecessor':DYNAMIC_PREDECESSOR,'attachmentCodeCommit':CODE,
            'intermediateDocumentationCorrection':DYNAMIC_DOC,'intermediateDocumentationSha256':DYNAMIC_DOC_SHA256,
            'changedCodePaths':sorted(changed),'onlyRuntimeChange':DYNAMIC_HELPER,
            'helperSha256':DYNAMIC_HELPER_SHA256,'testSha256':DYNAMIC_TEST_SHA256,
            'exactNullSceneRetentionChange':True,'allOtherTrackedInputsByteExactToPredecessor':True,
            'oldFiveMaterialTestsByteRetained':True,'nativeRegressionTestsBefore':5,'nativeRegressionTestsAfter':7,
            'additiveNativeRegressionLines':49,'additiveNativeTestBlockSha256':DYNAMIC_TEST_ADDITION_SHA256,
            'finalAttachmentSourceInputsByteExact':True}

def verify(args, diagnostics):
    repo, archive = args.repo.resolve(), args.zip.resolve()
    require(repo.is_dir() and archive.is_file(), 'Actual repository and actual ZIP are required')
    require(args.code_ref == CODE, 'This verifier is pinned to the reviewed code commit ' + CODE)
    git = Git(repo)
    try:
        final_ref, code_ref, baseline = git.commit(args.ref), git.commit(args.code_ref), git.commit(BASE)
        git.command('merge-base', '--is-ancestor', baseline, code_ref)
        git.command('merge-base', '--is-ancestor', git.commit(DOCS_ANCHOR), code_ref)
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
        preservation = check_preservation(git, before, code, final, args.preservation, args.preservation_sha256)
        ui_preservation = check_ui_predecessor(git, code, final)
        corrected_test = check_test_only_successor(git, code, final)
        focus_successor = check_focus_successor(git, code, final)
        dynamic_successor = check_dynamic_successor(git, code, final)
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
                        classification = classify_credential(path, name, digest)
                        if classification == 'CONFIRMED_SYNTHETIC_DIAGNOSTIC_TEST_FIXTURE':
                            require(reviewed_fixture_shape(data), 'Reviewed diagnostics fixture has an unexpected JWT shape')
                        scans.append({'path': path, 'pattern': name,
                                      'classification': classification,
                                      'sourceBlobSha256': digest})
                metadata = {'path': path, 'mode': entry['mode'], 'gitBlob': entry['oid'], 'bytes': len(data), 'sha256': digest}
                manifest.append(metadata)
                if path in CRITICAL:
                    critical[path] = metadata
            diagnostics['credentialPatternFindings'] = scans
            require(all(x['classification'] == 'CONFIRMED_SYNTHETIC_DIAGNOSTIC_TEST_FIXTURE' for x in scans),
                    'Unreviewed potential credential material needs manual review; values are not logged')
        return {'kind': 'VN_CODE_1610_FLOATING_GLASS_COMPLETE_SOURCE_ZIP_VERIFICATION',
                'result': 'PASS_WITH_CONFIRMED_TEST_FIXTURE' if scans else 'PASS',
                'verifiedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'version': VERSION, 'finalCommit': final_ref, 'codeCommit': code_ref, 'baseline': baseline,
                'zip': {'path': str(archive), 'bytes': archive.stat().st_size, 'sha256': file_hash(archive),
                        'prefix': PREFIX, 'crcPassed': True, 'fileCount': len(files), 'directoryEntryCount': len(zip_directories),
                        'allGitTrackedBlobsIncluded': True, 'allBlobBytesExactlyMatchFinalRef': True,
                        'gitArchiveCommentMatchesFinalRef': comment == final_ref.encode()},
                'sourcePreservation': preservation, 'uiPredecessorPreservation':ui_preservation, 'clipFixtureCheckpointProof':corrected_test, 'focusCheckpointProof':focus_successor, 'logicalAttachmentSuccessor':dynamic_successor, 'criticalFiles': critical, 'executableGitScriptsPreserved': executable,
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
    parser.add_argument('--preservation-sha256', default=PRESERVATION_SHA256, help='Reviewed immutable manifest SHA-256 (not a newly generated pin)')
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
        result = {'kind': 'VN_CODE_1610_FLOATING_GLASS_COMPLETE_SOURCE_ZIP_VERIFICATION', 'result': 'FAIL',
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
