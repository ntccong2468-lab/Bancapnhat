#!/usr/bin/env python3
"""Verify already-downloaded 1.6.7 certificate-release bytes and preserved history.

Derived from verify_public_preservation.py; the historical verifier is unchanged.
Run ONLY after successful publication and after saving fresh certificate-specific
release/tag snapshots plus all16 actual public downloads. This program performs
no network requests, downloads, installer execution or release mutation.

Usage:
  python3 /workspace/vncode-167-release/verify_public_preservation-certificate-guard.py \
    --directory /workspace/vncode-167-release/public-downloads-certificate-guard

After the unchanged installed166 updater probe has actually run, repeat with:
  --updater-receipt /workspace/vncode-167-release/updater-after-certificate-guard.json

A PASS receipt is written only after every check succeeds. An omitted updater
receipt means updater acceptance remains unverified by this program.
"""
import argparse
import datetime
import hashlib
import json
import pathlib

WORK = pathlib.Path('/workspace/vncode-167-release')
SOURCE = 'd0231cd6913ce3a1764067bad0901b32c535de41'
BUILD_RUN = 42611294387
VERSION = '1.6.7'
RELEASE_ID = 438471378
TAG = 'v1.6.7'
REPOSITORY = 'ntccong2468-lab/Bancapnhat'
BUILD_DIRECTORY = WORK / 'windows-artifact-certificate-guard'
PUBLIC_DIRECTORY = WORK / 'public-downloads-certificate-guard'
PACKAGE_RECEIPT = WORK / 'package-verification-certificate-guard.json'
OUTPUT_RECEIPT = WORK / 'public-preservation-verification-certificate-guard.json'
ASSET_NAMES = {
    'VN-code-1.6.7-Windows-x64.exe', 'VN-code-1.6.7-Windows-x64.msi',
    'VN-code-1.6.7-Windows-x64.sha256', 'VN-code-1.6.7-Windows-x64.zip',
    'VN-code-1.6.7-source.zip', 'VN-code-1.6.7-history.bundle',
    'VN-code-1.6.7-verification.zip', 'build-info.json', 'release-notes.md',
    'native-smoke.json', 'upgrade-baseline-smoke.json', 'asset-manifest.json',
    'SHA256SUMS', 'gs1-smoke.json', 'side-by-side-smoke.json', 'previous-baseline.json',
}
RELEASE_FIELDS = ('id', 'tag_name', 'target_commitish', 'name', 'draft',
                  'prerelease', 'immutable', 'created_at', 'published_at', 'body')
ASSET_FIELDS = ('id', 'name', 'size', 'digest', 'state', 'content_type',
                'created_at', 'updated_at', 'browser_download_url')


def require(ok, label):
    if not ok:
        raise SystemExit('FAILED: ' + label)


def load(path):
    require(path.is_file() and not path.is_symlink(), 'regular JSON input ' + path.name)
    return json.loads(path.read_text(encoding='utf-8'))


def file_digest(path):
    require(path.is_file() and not path.is_symlink(), 'regular asset ' + path.name)
    digest = hashlib.sha256()
    total = 0
    with path.open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
            total += len(chunk)
    return total, digest.hexdigest()


def asset_map(release):
    assets = release['assets']
    result = {asset['id']: asset for asset in assets}
    require(len(result) == len(assets), 'unique asset IDs ' + str(release['id']))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True, type=pathlib.Path)
    parser.add_argument('--updater-receipt', type=pathlib.Path)
    args = parser.parse_args()
    directory = args.directory
    require(not directory.is_symlink() and directory.resolve() == PUBLIC_DIRECTORY.resolve(),
            'certificate-specific public directory')
    require(directory.is_dir() and not BUILD_DIRECTORY.is_symlink(), 'asset directories')
    require(directory.resolve() != BUILD_DIRECTORY.resolve(), 'public and build directories differ')
    require({entry.name for entry in directory.iterdir()} == ASSET_NAMES, 'exact16 public files')
    require({entry.name for entry in BUILD_DIRECTORY.iterdir()} == ASSET_NAMES, 'exact16 certificate build files')

    package = load(PACKAGE_RECEIPT)
    require(package['result'] == 'passed' and package['version'] == VERSION
            and package['sourceCommit'] == SOURCE and package['fullBuildRun'] == BUILD_RUN,
            'passed certificate package receipt source/run/version')
    require(package['all16LocalDigestsComputed'] is True and set(package['assets']) == ASSET_NAMES,
            'package receipt exact16 asset hashes')

    before = load(WORK / 'releases-before.json')
    after = load(WORK / 'releases-after-certificate-guard.json')
    latest = load(WORK / 'latest-after-certificate-guard.json')
    require(latest['id'] == RELEASE_ID and latest['tag_name'] == TAG, 'Latest identity')
    require(latest['target_commitish'] == SOURCE, 'Latest certificate source')
    require(latest['draft'] is False and latest['prerelease'] is False
            and latest['immutable'] is True, 'published immutable stable')
    require(len(before) == 16 and len({r['id'] for r in before}) == 16
            and sum(len(r['assets']) for r in before) == 258, 'before16 releases/258 assets')
    require(len(after) == 17 and len({r['id'] for r in after}) == 17
            and {r['id'] for r in after} == {r['id'] for r in before} | {RELEASE_ID},
            'exact17 published releases')
    published = next(r for r in after if r['id'] == RELEASE_ID)
    for field in RELEASE_FIELDS:
        require(published.get(field) == latest.get(field), 'Latest/release-list ' + field)
    published_assets, latest_assets = asset_map(published), asset_map(latest)
    require(published_assets.keys() == latest_assets.keys(), 'Latest/release-list asset IDs')
    for asset_id, asset in published_assets.items():
        for field in ASSET_FIELDS:
            require(asset.get(field) == latest_assets[asset_id].get(field),
                    'Latest/release-list asset ' + str(asset_id) + ' ' + field)

    preserved = []
    for original in before:
        current = next(r for r in after if r['id'] == original['id'])
        for field in RELEASE_FIELDS:
            require(original.get(field) == current.get(field),
                    'old release ' + str(original['id']) + ' ' + field)
        old_assets, current_assets = asset_map(original), asset_map(current)
        require(old_assets.keys() == current_assets.keys(), 'old asset IDs')
        for asset_id, asset in old_assets.items():
            for field in ASSET_FIELDS:
                require(asset.get(field) == current_assets[asset_id].get(field),
                        'old asset ' + str(asset_id) + ' ' + field)
        preserved.append({'releaseId': original['id'], 'tag': original['tag_name'],
                          'assets': len(old_assets)})

    pre_tags = load(WORK / 'tags-before-final.json')
    post_tags = load(WORK / 'tags-after-certificate-guard.json')
    old_refs = {entry['ref']: entry['object'] for entry in pre_tags}
    new_refs = {entry['ref']: entry['object'] for entry in post_tags}
    require(len(pre_tags) == len(old_refs) == 45 and 'refs/tags/' + TAG not in old_refs,
            'before45 unique old tags')
    require(len(post_tags) == len(new_refs) == 46 and set(new_refs) == set(old_refs) | {'refs/tags/' + TAG},
            'exact46 final tags')
    for ref, value in old_refs.items():
        require(new_refs.get(ref) == value, 'old tag ' + ref)
    require(new_refs['refs/tags/' + TAG].get('sha') == SOURCE, 'new tag exact source')

    assets = latest['assets']
    require(len(assets) == 16 and len({a['id'] for a in assets}) == 16
            and {a['name'] for a in assets} == ASSET_NAMES, 'sixteen unique named public assets')
    checks = {}
    for asset in assets:
        name = asset['name']
        public_size, public_sha = file_digest(directory / name)
        build_size, build_sha = file_digest(BUILD_DIRECTORY / name)
        expected = package['assets'][name]
        require(public_size == build_size == expected['bytes'] == asset['size']
                and public_sha == build_sha == expected['sha256']
                and asset['digest'] == 'sha256:' + public_sha
                and expected['digest'] == 'sha256:' + public_sha,
                'public/build/package/GitHub bytes ' + name)
        require(asset['state'] == 'uploaded'
                and asset['browser_download_url'] == 'https://github.com/' + REPOSITORY
                + '/releases/download/' + TAG + '/' + name, 'public asset URL/state ' + name)
        checks[name] = {'bytes': public_size, 'sha256': public_sha, 'assetId': asset['id'],
                        'matchesWindowsArtifact': True, 'matchesCertificatePackageReceipt': True,
                        'matchesGitHubDigest': True}

    updater = {'verified': False, 'scope': 'No updater receipt supplied; updater acceptance remains separate.'}
    if args.updater_receipt is not None:
        require(args.updater_receipt.resolve() == (WORK / 'updater-after-certificate-guard.json').resolve(),
                'certificate-specific updater receipt')
        proof = load(args.updater_receipt)
        msi = checks['VN-code-1.6.7-Windows-x64.msi']
        require(proof['installedBinaryVersion'] == '1.6.6' and proof['checkStatus'] == 'AVAILABLE'
                and proof['latestVersion'] == VERSION, 'actual166 updater AVAILABLE167')
        require(proof['selectedDownload'] == 'https://github.com/' + REPOSITORY
                + '/releases/download/' + TAG + '/VN-code-1.6.7-Windows-x64.msi'
                and proof['sha256'].lower() == msi['sha256'] and proof['size'] == msi['bytes'],
                'updater selects exact NEW MSI hash/size')
        require(proof['explicitAuthorizationHeaderInApp'] is False, 'updater app authorization header absent')
        updater = {'verified': True, 'receiptFile': args.updater_receipt.name,
                   'receiptSha256': file_digest(args.updater_receipt)[1],
                   'installedBinaryVersion': '1.6.6', 'latestVersion': VERSION,
                   'selectedDownload': proof['selectedDownload'], 'sha256': msi['sha256'], 'size': msi['bytes'],
                   'sellerPopupObserved': proof.get('sellerPopupObserved'),
                   'anonymousNetworkAccessIndependentlyProven': False,
                   'scope': 'Actual166 probe receipt compared to NEW167 MSI; inherited Cloud proxy/CA, '
                            'no installation or seller-popup acceptance inferred.'}

    input_paths = [PACKAGE_RECEIPT, WORK / 'releases-before.json', WORK / 'releases-after-certificate-guard.json',
                   WORK / 'latest-after-certificate-guard.json', WORK / 'tags-before-final.json',
                   WORK / 'tags-after-certificate-guard.json']
    receipt = {'verifiedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
               'kind': 'VNCODE_CERTIFICATE_GUARD_RELEASE_PUBLIC_PRESERVATION', 'version': VERSION,
               'sourceCommit': SOURCE, 'fullWindowsBuildRun': BUILD_RUN, 'releaseId': RELEASE_ID,
               'tag': TAG, 'publishedAt': latest['published_at'], 'immutable': True, 'result': 'PASS',
               'publicDirectory': str(directory.resolve()), 'buildDirectory': str(BUILD_DIRECTORY),
               'packageReceipt': PACKAGE_RECEIPT.name,
               'inputMetadataSha256': {path.name: file_digest(path)[1] for path in input_paths},
               'publicAssets': checks, 'allSixteenActualDownloadsMatchWindowsArtifact': True,
               'allSixteenMatchNewCertificatePackageReceipt': True,
               'previousReleases': preserved, 'previousReleaseCount': 16, 'previousAssetCount': 258,
               'previousTagCount': 45, 'finalReleaseCount': 17, 'finalTagCount': 46,
               'previousTagsUnchanged': True, 'oldDownloadCountersExcluded': True,
               'updater': updater,
               'networkScope': 'Offline comparison of actual public downloads saved by release tooling; '
                               'no independent anonymous-network proof claimed.',
               'sellerApiMutations': False, 'licenseActivation': False,
               'releaseMutationsByThisVerifier': False}
    temporary = OUTPUT_RECEIPT.with_suffix('.json.tmp')
    temporary.write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    temporary.replace(OUTPUT_RECEIPT)
    print(json.dumps({'result': 'PASS', 'assets': len(checks), 'oldReleases': 16, 'oldAssets': 258,
                      'oldTags': 45, 'updaterVerified': updater['verified'], 'receipt': str(OUTPUT_RECEIPT)}))


if __name__ == '__main__':
    main()
