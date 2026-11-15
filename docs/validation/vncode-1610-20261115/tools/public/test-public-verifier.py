#!/usr/bin/env python3
"""Synthetic corruption controls; never claims an actual public release was verified."""
import copy, importlib.util, json, pathlib, tempfile
P=pathlib.Path(__file__).with_name('check-public.py')
spec=importlib.util.spec_from_file_location('public_check',P);v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
old=v.load(v.ROOT/'before/releases.json');tags=v.load(v.ROOT/'before/tags.json')
controls=[]
def fails(label,fn):
    try:fn()
    except (AssertionError,KeyError,ValueError,TypeError):controls.append(label);return
    raise AssertionError('Corruption accepted: '+label)
with tempfile.TemporaryDirectory(prefix='public-verifier-controls-',dir=str(v.ROOT)) as td:
    out=pathlib.Path(td);rid=900000001
    for n in v.NAMES:(out/n).write_text('synthetic:'+n)
    def write(n,data):(out/n).write_text(json.dumps(data))
    exe=v.digest(out/f'VN-code-{v.VERSION}-Windows-x64.exe')
    write('build-info.json',{'version':v.VERSION,'sourceCommit':v.SOURCE,'runId':v.RUN,'installer':{'name':f'VN-code-{v.VERSION}-Windows-x64.exe',**exe},'verification':{'fullJavaSuiteRun':True,'nativeSmokeRun':True,'baselineUpgradeRun':True}})
    (out/f'VN-code-{v.VERSION}-Windows-x64.sha256').write_text(exe['sha256']+'  '+f'VN-code-{v.VERSION}-Windows-x64.exe\n')
    write('asset-manifest.json',{'schema':1,'version':v.VERSION,'sourceCommit':v.SOURCE,'files':{n:v.digest(out/n) for n in v.PRIMARY}})
    (out/'SHA256SUMS').write_text(''.join(v.digest(out/n)['sha256']+'  '+n+'\n' for n in v.PRIMARY+['asset-manifest.json']))
    wanted={n:v.digest(out/n) for n in v.NAMES}
    candidate={'id':rid,'tag_name':'v'+v.VERSION,'target_commitish':v.SOURCE,'immutable':True,'draft':False,'prerelease':False,'published_at':'2026-11-15T12:00:00Z','assets':[{'id':900000000+i,'name':n,'size':wanted[n]['bytes'],'digest':'sha256:'+wanted[n]['sha256'],'state':'uploaded','browser_download_url':f'https://github.com/{v.REPO}/releases/download/v{v.VERSION}/{n}','download_count':0} for i,n in enumerate(v.NAMES)]}
    new=copy.deepcopy(old)+[copy.deepcopy(candidate)];at=copy.deepcopy(tags)+[{'ref':'refs/tags/v'+v.VERSION,'object':{'type':'commit','sha':v.SOURCE}}]
    v.retention(old,new,tags,at,candidate,rid);v.latest_gate(candidate,rid,wanted);v.verify_payloads(out,wanted)
    for field,val in [('body','modified-old-body'),('target_commitish','0'*40),('tag_name','changed-old-tag')]:
        damaged=copy.deepcopy(new);damaged[0][field]=val;fails('old-release-'+field,lambda:v.retention(old,damaged,tags,at,candidate,rid))
    damaged=copy.deepcopy(new);damaged[0]['assets'][0]['digest']='sha256:'+'0'*64;fails('old-asset-digest',lambda:v.retention(old,damaged,tags,at,candidate,rid))
    damaged=copy.deepcopy(new);damaged[0]['assets'][0]['id']+=1;fails('old-asset-id',lambda:v.retention(old,damaged,tags,at,candidate,rid))
    fails('missing-old-release',lambda:v.retention(old,new[1:],tags,at,candidate,rid))
    fails('missing-old-tag',lambda:v.retention(old,new,tags,at[1:],candidate,rid))
    damaged=copy.deepcopy(at);damaged[0]['object']['sha']='0'*40;fails('old-raw-tag-sha',lambda:v.retention(old,new,tags,damaged,candidate,rid))
    damaged=copy.deepcopy(at);damaged[-1]['object']['sha']='0'*40;fails('new-tag-source',lambda:v.retention(old,new,tags,damaged,candidate,rid))
    for field,val in [('immutable',False),('draft',True),('prerelease',True),('target_commitish','0'*40),('id',rid+1)]:
        damaged=copy.deepcopy(candidate);damaged[field]=val;fails('latest-'+field,lambda:v.latest_gate(damaged,rid,wanted))
    for field,val in [('size',0),('digest','sha256:'+'0'*64),('browser_download_url','http://invalid.test/a'),('state','new')]:
        damaged=copy.deepcopy(candidate);damaged['assets'][0][field]=val;fails('latest-asset-'+field,lambda:v.latest_gate(damaged,rid,wanted))
    fails('missing-public-asset',lambda:v.latest_gate({**candidate,'assets':candidate['assets'][:-1]},rid,wanted))
    fails('duplicate-public-asset',lambda:v.latest_gate({**candidate,'assets':candidate['assets']+[candidate['assets'][0]]},rid,wanted))
    original=(out/'release-notes.md').read_bytes();(out/'release-notes.md').write_text('altered');fails('payload-tamper',lambda:v.verify_payloads(out,wanted));(out/'release-notes.md').write_bytes(original)
    (out/'unexpected').write_text('extra');fails('extra-public-file',lambda:v.verify_payloads(out,wanted));(out/'unexpected').unlink()
    original_files={n:(out/n).read_bytes() for n in v.NAMES}
    def restore():
        for n,data in original_files.items():(out/n).write_bytes(data)
    def refresh_manifest_and_checksums():
        write('asset-manifest.json',{'schema':1,'version':v.VERSION,'sourceCommit':v.SOURCE,'files':{n:v.digest(out/n) for n in v.PRIMARY}})
        refresh_checksums()
    def refresh_checksums():(out/'SHA256SUMS').write_text(''.join(v.digest(out/n)['sha256']+'  '+n+'\n' for n in v.PRIMARY+['asset-manifest.json']))
    manifest=v.load(out/'asset-manifest.json');manifest['sourceCommit']='0'*40;write('asset-manifest.json',manifest);refresh_checksums()
    rehashed={k:v.digest(out/k) for k in v.NAMES};fails('coordinated-manifest-source',lambda:v.verify_payloads(out,rehashed));restore()
    sums=(out/'SHA256SUMS').read_text();(out/'SHA256SUMS').write_text('0'*64+sums[64:]);rehashed={k:v.digest(out/k) for k in v.NAMES};fails('coordinated-checksum-value',lambda:v.verify_payloads(out,rehashed));restore()
    for field,bad in [('sourceCommit','0'*40),('runId',v.RUN+1),('version','1.6.99')]:
        info=v.load(out/'build-info.json');info[field]=bad;write('build-info.json',info);refresh_manifest_and_checksums();rehashed={k:v.digest(out/k) for k in v.NAMES};fails('coordinated-build-provenance-'+field,lambda:v.verify_payloads(out,rehashed));restore()
    info=v.load(out/'build-info.json');info['installer']['sha256']='0'*64;write('build-info.json',info);refresh_manifest_and_checksums();rehashed={k:v.digest(out/k) for k in v.NAMES};fails('coordinated-installer-provenance',lambda:v.verify_payloads(out,rehashed));restore()
    info=v.load(out/'build-info.json');info['verification']['baselineUpgradeRun']=False;write('build-info.json',info);refresh_manifest_and_checksums();rehashed={k:v.digest(out/k) for k in v.NAMES};fails('coordinated-upgrade-acceptance',lambda:v.verify_payloads(out,rehashed));restore()
    damaged=copy.deepcopy(new);damaged[0]['assets'][0]['download_count']+=1;v.retention(old,damaged,tags,at,candidate,rid)
    v.retention(old,new,tags,at,candidate,rid);v.latest_gate(candidate,rid,wanted);v.verify_payloads(out,wanted)
receipt={'kind':'VNCODE_1610_PUBLIC_VERIFIER_SYNTHETIC_CONTROLS','result':'PASS','syntheticPositive':True,'negativeControlsPassed':len(controls),'controls':controls,'downloadCounterOnlyAccepted':True,'actualPublicReleaseVerified':False,'helper':v.digest(P)}
target=v.ROOT/'public-verifier-controls.json';target.write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))
