#!/usr/bin/env python3
"""Read the pinned GitHub build; optionally collect completed artifacts. No writes to GitHub."""
import argparse, datetime, hashlib, json, os, pathlib, stat, urllib.request, zipfile

ROOT = pathlib.Path(__file__).resolve().parent
BASE = 'https://api.github.com/repos/ntccong2468-lab/Bancapnhat'
RUN = 43131000128
SOURCE = '4ef0f1e2b8ef7b28190de3034b0c67ebf615be0b'
VERSION = '1.6.9'
NAMES = {
    f'VN-code-{VERSION}-Windows-x64.exe', f'VN-code-{VERSION}-Windows-x64.msi',
    f'VN-code-{VERSION}-Windows-x64.sha256', f'VN-code-{VERSION}-Windows-x64.zip',
    f'VN-code-{VERSION}-source.zip', f'VN-code-{VERSION}-history.bundle',
    f'VN-code-{VERSION}-verification.zip', 'build-info.json', 'release-notes.md',
    'native-smoke.json', 'upgrade-baseline-smoke.json', 'asset-manifest.json',
    'SHA256SUMS', 'gs1-smoke.json', 'side-by-side-smoke.json', 'previous-baseline.json',
}

def request(path):
    return urllib.request.Request(BASE + path, headers={
        'Accept': 'application/vnd.github+json', 'User-Agent': 'VNCode-release-169'})

def get(path):
    with urllib.request.urlopen(request(path), timeout=45) as response:
        return json.load(response)

def save(name, data):
    (ROOT / name).write_text(json.dumps(data, indent=2) + '\n')

def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()

def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--download', action='store_true')
    args = cli.parse_args()
    run = get(f'/actions/runs/{RUN}')
    jobs = get(f'/actions/runs/{RUN}/jobs?per_page=100')
    assert run['head_sha'] == SOURCE and run['workflow_id'] == 392732536
    assert jobs['total_count'] == len(jobs['jobs'])
    save('windows-run-current.json', run)
    save('windows-jobs-current.json', jobs)
    print(json.dumps({
        'checkedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'status': run['status'], 'conclusion': run['conclusion'], 'attempt': run['run_attempt'],
        'active': [s['name'] for j in jobs['jobs'] for s in j['steps'] if s['status'] == 'in_progress'],
        'failed': [s['name'] for j in jobs['jobs'] for s in j['steps'] if s.get('conclusion') == 'failure'],
    }), flush=True)
    if not args.download:
        return
    assert run['status'] == 'completed' and run['conclusion'] == 'success', 'Full Windows build has not passed'
    listing = get(f'/actions/runs/{RUN}/artifacts?per_page=100')
    assert listing['total_count'] == len(listing['artifacts'])
    matches = [a for a in listing['artifacts'] if a['name'] == f'VN-code-{VERSION}-complete-baseline']
    assert len(matches) == 1
    artifact = matches[0]
    assert not artifact['expired'] and artifact['workflow_run']['head_sha'] == SOURCE
    assert artifact['workflow_run']['id'] == RUN
    save('windows-run-final.json', run)
    save('windows-jobs-final.json', jobs)
    save('artifacts-listing.json', listing)
    save('artifact-metadata.json', artifact)
    archive = ROOT / 'complete-baseline-actions.zip'
    if not archive.exists():
        part = archive.with_suffix('.part')
        with urllib.request.urlopen(request(f"/actions/artifacts/{artifact['id']}/zip"), timeout=120) as response, part.open('wb') as stream:
            for block in iter(lambda: response.read(1024 * 1024), b''):
                stream.write(block)
        os.replace(part, archive)
    assert archive.stat().st_size == artifact['size_in_bytes']
    assert 'sha256:' + digest(archive) == artifact['digest']
    out = ROOT / 'reviewed-artifacts'
    assert not out.exists() or not any(out.iterdir()), 'Refuse to replace extracted artifacts'
    out.mkdir(exist_ok=True)
    with zipfile.ZipFile(archive) as bundle:
        members = bundle.infolist()
        assert len(members) == len(NAMES) and {m.filename for m in members} == NAMES
        for member in members:
            assert not member.is_dir() and not stat.S_ISLNK(member.external_attr >> 16)
            assert pathlib.PurePosixPath(member.filename).name == member.filename
        bundle.extractall(out)
    after = get(f'/actions/artifacts/{artifact["id"]}')
    for key in ['id', 'name', 'digest', 'size_in_bytes', 'expired']:
        assert after[key] == artifact[key], f'Artifact metadata changed: {key}'
    print(json.dumps({'downloadedArtifact': artifact['id'], 'digest': artifact['digest'], 'bytes': archive.stat().st_size,
                      'files': len(NAMES), 'buildInfo': json.loads((out / 'build-info.json').read_text(encoding='utf-8-sig'))}), flush=True)

if __name__ == '__main__':
    main()
