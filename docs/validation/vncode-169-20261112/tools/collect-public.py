#!/usr/bin/env python3
"""Collect immutable release metadata and download all 16 public payloads; GET only."""
import concurrent.futures, datetime, hashlib, json, os, pathlib, urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
REPO = 'ntccong2468-lab/Bancapnhat'
BASE = 'https://api.github.com/repos/' + REPO
SOURCE = '4ef0f1e2b8ef7b28190de3034b0c67ebf615be0b'
HEAD = 'b9635f80f0b125ad33830f71430088358ccc671e'
RELEASE = 442540592
PUBLISHER_RUN = 43163588699
HEADERS = {'Accept': 'application/vnd.github+json', 'User-Agent': 'VNCode-release-169', 'Cache-Control': 'no-cache'}

def get(path):
    with urllib.request.urlopen(urllib.request.Request(BASE + path, headers=HEADERS), timeout=45) as response:
        return json.load(response)

def save(name, value):
    (ROOT / name).write_text(json.dumps(value, indent=2) + '\n')

def hashfile(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()

def main():
    run = get(f'/actions/runs/{PUBLISHER_RUN}')
    assert run['status'] == 'completed' and run['conclusion'] == 'success' and run['head_sha'] == HEAD
    save('publisher-run-final.json', run)
    latest = get('/releases/latest')
    assert latest['id'] == RELEASE and latest['tag_name'] == 'v1.6.9' and latest['target_commitish'] == SOURCE
    assert latest['immutable'] and not latest['draft'] and not latest['prerelease']
    save('latest-after.json', latest)
    tag = get('/git/ref/tags/v1.6.9')
    assert tag['object']['type'] == 'commit' and tag['object']['sha'] == SOURCE
    save('tag-169-after.json', tag)
    review = json.loads((ROOT / 'package-review.json').read_text())
    assert review['result'] == 'PASS' and review['sourceCommit'] == SOURCE
    wanted = review['assets']
    assert len(wanted) == 16 and len(latest['assets']) == 16
    assert {item['name'] for item in latest['assets']} == set(wanted)
    out = ROOT / 'public-artifacts'
    out.mkdir(exist_ok=True)

    def download(asset):
        name = asset['name']
        expected = wanted[name]
        url = f'https://github.com/{REPO}/releases/download/v1.6.9/{name}'
        assert asset['browser_download_url'] == url and asset['state'] == 'uploaded'
        assert asset['size'] == expected['bytes'] and asset['digest'] == 'sha256:' + expected['sha256']
        target = out / name
        reused = target.exists()
        if not reused:
            temporary = target.with_name(name + '.part')
            request = urllib.request.Request(url, headers={'User-Agent': 'VNCode-release-169'})
            with urllib.request.urlopen(request, timeout=45) as response, temporary.open('wb') as stream:
                for chunk in iter(lambda: response.read(1024 * 1024), b''):
                    stream.write(chunk)
            os.replace(temporary, target)
        actual = {'bytes': target.stat().st_size, 'sha256': hashfile(target)}
        assert actual == expected, f'Public payload differs: {name}'
        return name, {**actual, 'url': url, 'assetId': asset['id'], 'reusedExistingPublicDownload': reused,
                      'verifiedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat()}

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        downloads = dict(pool.map(download, latest['assets']))
    save('public-downloads.json', {'result': 'PASS', 'releaseId': RELEASE, 'source': SOURCE, 'files': downloads})
    releases = []
    for page in range(1, 1001):
        rows = get(f'/releases?per_page=100&page={page}')
        releases.extend(rows)
        if len(rows) < 100:
            break
    else:
        raise AssertionError('Release listing did not terminate')
    tags = get('/git/matching-refs/tags/')
    save('releases-after.json', releases)
    save('tag-refs-after.json', tags)
    latest_again = get('/releases/latest')
    assert latest_again['id'] == RELEASE and latest_again['immutable']
    state_path = ROOT / 'STATE.json'
    state = json.loads(state_path.read_text())
    state.update({'updatedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  'status': 'PUBLISHED_ALL_PUBLIC_BYTES_MATCH_UPDATER_AND_PRESERVATION_PENDING',
                  'releasePublished': True, 'releaseId': RELEASE, 'publishedAtUtc': latest['published_at'],
                  'publisherRun': PUBLISHER_RUN, 'publicBytesVerified': True, 'publicFiles': len(downloads)})
    save('STATE.json', state)
    print(json.dumps({'result': 'PASS', 'releaseId': RELEASE, 'publishedAt': latest['published_at'],
                      'files': len(downloads), 'publishedReleases': len([r for r in releases if not r['draft']]),
                      'assets': sum(len(r['assets']) for r in releases if not r['draft']), 'tagRefs': len(tags),
                      'url': latest['html_url']}, indent=2))

if __name__ == '__main__':
    main()
