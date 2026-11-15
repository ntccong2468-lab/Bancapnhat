#!/usr/bin/env python3
"""GET-only public release collection and independently pinned byte verification."""
import argparse, concurrent.futures, copy, datetime, hashlib, importlib.util, json, os, pathlib, re, subprocess, tempfile

ROOT=pathlib.Path(__file__).resolve().parents[1]
REPO='ntccong2468-lab/Bancapnhat'
SOURCE='b74b5edfd46636e8779c94f467eef01c03c50a14'
RUN=43641520595
VERSION='1.6.10'
PRIMARY=[f'VN-code-{VERSION}-Windows-x64.{e}' for e in ('exe','msi','sha256','zip')]+[f'VN-code-{VERSION}-{e}' for e in ('source.zip','history.bundle','verification.zip')]+['build-info.json','release-notes.md','native-smoke.json','upgrade-baseline-smoke.json']
NAMES=PRIMARY+['asset-manifest.json','SHA256SUMS','gs1-smoke.json','side-by-side-smoke.json','previous-baseline.json']
BEFORE_PINS={
    'releases.json':'af58d39cfba1c95571b1f83182b8ed751b3f5acab7b73837d87fe846f1e4a2b1',
    'tags.json':'237093af9435e35c9839e93cf250ab139f23bd47c8f9a956eb1d6678a901eb56',
    'latest.json':'2a1f4706c390d5d0a86ce0812f72e94d8d0a86aa77f92384becb4714fcde9050',
}
ORIGINAL_PINS={
    'updater-before.json':'1387797c075fd128b853dd057823792a11aabf4a7daa25e0dfc399915c130edd',
    'updater-before-integrity.json':'41d9d0bfb0368f10e2abdd98635014b06310d9ccd39098aca3f5063cc6c61869',
    'original169-jars.json':'0fff09946080c9ac8d4542fb8317eab3d2bc767c266a110dfdf4fb4aeeef76fe',
    'tools/Installed169UpdateProbe.java':'6901618642d4b571ba249f3aeea26214ef59854bb3f72cca0ce6761ab261ae85',
    'tools/run-probe.py':'c8e8251d054bcd2258fcb3a0ea75aba78dafa67853255ca9c656934bb235448d',
    'probe-classes/com/vncode/app/integration/update/Installed169UpdateProbe.class':'f3faee2407a7b65e5c811f2e7dd01f279662eee9f3010f20ac742c0f80126759',
}

def require(c,m):
    if not c:raise AssertionError(m)
def pairs(items):
    out={}
    for k,v in items:require(k not in out,'Duplicate JSON key: '+k);out[k]=v
    return out
def loads(s):return json.loads(s,object_pairs_hook=pairs)
def load(p):return loads(pathlib.Path(p).read_text(encoding='utf-8-sig'))
def digest(p):
    p=pathlib.Path(p);s=p.lstat();require(p.is_file() and not p.is_symlink(),'Not a regular file: '+p.name)
    with p.open('rb') as f:h=hashlib.file_digest(f,'sha256').hexdigest()
    t=p.stat();require((s.st_size,s.st_mtime_ns,s.st_ino)==(t.st_size,t.st_mtime_ns,t.st_ino),'File changed while hashing: '+p.name)
    return {'bytes':s.st_size,'sha256':h}
def save(p,v):
    with pathlib.Path(p).open('x') as f:json.dump(v,f,indent=2);f.write('\n')
def get(path,paginate=False):
    require(path.startswith('repos/'+REPO+'/'),'Unexpected GET path')
    cmd=['gh','api','--method','GET']+(['--paginate'] if paginate else [])+[path]
    raw=subprocess.run(cmd,capture_output=True,text=True,check=True,timeout=90).stdout
    if not paginate:return loads(raw)
    d=json.JSONDecoder(object_pairs_hook=pairs);out=[]
    while raw.strip():v,i=d.raw_decode(raw.lstrip());require(isinstance(v,list),'Paginated response is not an array');out.extend(v);raw=raw.lstrip()[i:]
    return out
def normalize(v):
    if isinstance(v,dict):
        out={k:normalize(x) for k,x in v.items() if k!='download_count'}
        if 'assets' in out:out['assets']=sorted(out['assets'],key=lambda a:a['id'])
        return out
    if isinstance(v,list):return [normalize(x) for x in v]
    return v
def unique(rows,key):
    out={}
    for x in rows:require(x[key] not in out,'Duplicate '+key);out[x[key]]=x
    return out
def retention(before,after,bt,at,latest,release_id):
    old=unique(before,'id');new=unique(after,'id')
    require(len(old)==19 and sum(len(x['assets']) for x in old.values())==306,'Baseline release counts')
    require(release_id not in old and len(new)==20 and set(new)==set(old)|{release_id},'Release set changed unexpectedly')
    require(sum(len(x['assets']) for x in new.values())==322,'Final asset count')
    for k,v in old.items():require(normalize(v)==normalize(new[k]),'Old release changed: '+str(k))
    ot=unique(bt,'ref');nt=unique(at,'ref');tag='refs/tags/v'+VERSION
    require(len(ot)==48 and len(nt)==49 and tag not in ot and set(nt)==set(ot)|{tag},'Raw tag set changed unexpectedly')
    for k,v in ot.items():require(v==nt[k],'Old raw tag changed: '+k)
    require(nt[tag]['object']['type']=='commit' and nt[tag]['object']['sha']==SOURCE,'New raw tag does not point to tested source')
    require(normalize(latest)==normalize(new[release_id]),'Latest disagrees with complete release listing')
    return {'oldReleasesPreserved':19,'oldAssetsPreserved':306,'oldRawTagRefsPreserved':48,'totalReleasesAfter':20,'totalAssetsAfter':322,'totalRawTagRefsAfter':49,'ignoredFields':['download_count']}
def checksums(p,wanted):
    rows={}
    for line in p.read_text(encoding='ascii').splitlines():
        m=re.fullmatch(r'([a-f0-9]{64})  ([A-Za-z0-9._-]+)',line);require(m is not None,'Invalid checksum line')
        sha,name=m.groups();require(name not in rows,'Duplicate checksum');rows[name]=sha
    require(rows=={k:v['sha256'] for k,v in wanted.items()},'Checksum content mismatch: '+p.name)
def verify_payloads(out,wanted):
    require(len(wanted)==16 and set(wanted)==set(NAMES),'Independent reviewed asset set')
    require({p.name for p in out.iterdir()}==set(NAMES),'Public download directory set')
    actual={n:digest(out/n) for n in NAMES};require(actual==wanted,'Public payload bytes differ from independent build review')
    manifest=load(out/'asset-manifest.json')
    require(manifest.get('schema')==1 and manifest.get('version')==VERSION and manifest.get('sourceCommit')==SOURCE,'Public manifest provenance')
    require(manifest.get('files')=={n:actual[n] for n in PRIMARY},'Public manifest file hashes')
    checksums(out/'SHA256SUMS',{n:actual[n] for n in PRIMARY+['asset-manifest.json']})
    checksums(out/f'VN-code-{VERSION}-Windows-x64.sha256',{f'VN-code-{VERSION}-Windows-x64.exe':actual[f'VN-code-{VERSION}-Windows-x64.exe']})
    info=load(out/'build-info.json')
    require(info.get('version')==VERSION and info.get('sourceCommit')==SOURCE and info.get('runId')==RUN,'Public build provenance')
    for k in ('fullJavaSuiteRun','nativeSmokeRun','baselineUpgradeRun'):require(info['verification'].get(k) is True,'Build verification missing: '+k)
    require(info['installer']=={'name':f'VN-code-{VERSION}-Windows-x64.exe',**actual[f'VN-code-{VERSION}-Windows-x64.exe']},'Installer provenance')
    return actual
def latest_gate(latest,release_id,wanted):
    for k,v in {'id':release_id,'tag_name':'v'+VERSION,'target_commitish':SOURCE,'immutable':True,'draft':False,'prerelease':False}.items():require(latest.get(k)==v and type(latest.get(k)) is type(v),'Latest field '+k)
    require(bool(latest.get('published_at')),'Latest is not published')
    remote=unique(latest['assets'],'name');require(set(remote)==set(NAMES),'Latest asset set')
    for n,row in remote.items():
        require(row['size']==wanted[n]['bytes'] and row.get('digest')=='sha256:'+wanted[n]['sha256'],'API payload digest/size: '+n)
        require(row['state']=='uploaded' and row['browser_download_url']==f'https://github.com/{REPO}/releases/download/v{VERSION}/{n}','API payload state/URL: '+n)
    return remote
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--release-id',required=True,type=int);p.add_argument('--source',required=True);p.add_argument('--run',required=True,type=int);p.add_argument('--review',type=pathlib.Path,required=True);p.add_argument('--review-sha256',required=True)
    a=p.parse_args();require(a.release_id>0 and a.release_id!=442540592,'Explicit new positive release ID required');require(a.source==SOURCE and a.run==RUN,'Explicit tested source/run required')
    require(digest(a.review)['sha256']==a.review_sha256,'Independent receipt changed')
    review=load(a.review);require(review.get('result')=='PASS' and review.get('sourceCommit')==SOURCE and review.get('ciRun')==RUN,'Independent review provenance')
    wanted=review['assets'];require(set(wanted)==set(NAMES) and len(wanted)==16,'Independent asset count')
    for n,v in wanted.items():require(set(v)=={'bytes','sha256'} and type(v['bytes']) is int and v['bytes']>0 and re.fullmatch('[0-9a-f]{64}',v['sha256']),'Independent digest format '+n)
    before=load(ROOT/'before/capture-receipt.json')
    for n,h in before['sha256'].items():require(digest(ROOT/'before'/n)['sha256']==h,'Before snapshot changed: '+n)
    for n,h in BEFORE_PINS.items():require(digest(ROOT/'before'/n)['sha256']==h,'Pinned before snapshot changed: '+n)
    for n,h in ORIGINAL_PINS.items():require(digest(ROOT/n)['sha256']==h,'Original updater proof changed: '+n)
    stage=ROOT/'after';stage.mkdir();latest=get('repos/'+REPO+'/releases/latest');latest_gate(latest,a.release_id,wanted);save(stage/'latest-initial.json',latest)
    out=stage/'payloads';out.mkdir()
    def download(item):
        n=item['name'];target=out/n;part=out/(n+'.part')
        require(n in wanted and not target.exists(),'Unexpected payload target')
        subprocess.run(['curl','--fail','--location','--retry','3','--connect-timeout','20','--max-time','600','--silent','--show-error',item['browser_download_url'],'--output',str(part)],check=True,timeout=650)
        require(digest(part)==wanted[n],'Downloaded public bytes differ: '+n);part.rename(target)
        return n,{**wanted[n],'assetId':item['id'],'url':item['browser_download_url'],'verifiedAtUtc':datetime.datetime.now(datetime.UTC).isoformat()}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:downloads=dict(pool.map(download,latest['assets']))
    save(stage/'public-downloads.json',downloads);actual=verify_payloads(out,wanted)
    after=get('repos/'+REPO+'/releases?per_page=100',True);tags=get('repos/'+REPO+'/git/matching-refs/tags/?per_page=100',True)
    save(stage/'releases.json',after);save(stage/'tags.json',tags)
    preserved=retention(load(ROOT/'before/releases.json'),after,load(ROOT/'before/tags.json'),tags,latest,a.release_id)
    subprocess.run(['python3',str(ROOT/'tools/run-probe.py'),'--stage','after','--latest-json',str(stage/'latest-initial.json'),'--review',str(a.review)],check=True,timeout=180)
    updater=load(ROOT/'updater-after.json');integrity=load(ROOT/'updater-after-integrity.json');msi=actual[f'VN-code-{VERSION}-Windows-x64.msi']
    require(updater['installedBinaryVersion']=='1.6.9' and updater['checkStatus']=='AVAILABLE' and updater['latestVersion']==VERSION,'Actual original updater result')
    require(updater['sha256']==msi['sha256'] and updater['size']==msi['bytes'] and updater['selectedDownload']==f'https://github.com/{REPO}/releases/download/v{VERSION}/VN-code-{VERSION}-Windows-x64.msi','Actual updater selected wrong MSI')
    require(integrity['all75OriginalJarHashesUnchanged'] is True and integrity['result']=='PASS','Original updater JAR integrity')
    for n,h in ORIGINAL_PINS.items():require(digest(ROOT/n)['sha256']==h,'Original updater proof changed after execution: '+n)
    for k,path in {'originalJarManifest':ROOT/'original169-jars.json','afterJarManifest':ROOT/'original169-jars-after-after.json','originalPublicZip':ROOT/'VN-code-1.6.9-Windows-x64.zip','originalAppJar':ROOT/'original169/VNcode-1.6.9.jar','probeSource':ROOT/'tools/Installed169UpdateProbe.java','probeClass':ROOT/'probe-classes/com/vncode/app/integration/update/Installed169UpdateProbe.class','probeRunner':ROOT/'tools/run-probe.py','updaterReceipt':ROOT/'updater-after.json'}.items():require(integrity[k]==digest(path),'After updater integrity reference: '+k)
    require(integrity['afterJarManifest']==integrity['originalJarManifest'],'Original JAR manifest changed after execution')
    for k,w in {'configuredDefaultSource':f'https://github.com/{REPO}','normalizedSource':f'https://api.github.com/repos/{REPO}','manifestPublicKeyPinned':False,'errorCode':'','networkPlatform':'Linux','windowsSelectionOnlyAfterNetwork':True,'blankLocalProfile':True,'loadedOriginalAppJar':'VNcode-1.6.9.jar','originalAppClassesLoadedFromPublishedJar':True,'explicitAuthorizationHeaderInApp':False,'anonymousNetworkAccessIndependentlyProven':False,'sellerPopupObserved':False}.items():require(updater.get(k)==w and type(updater.get(k)) is type(w),'Actual updater field: '+k)
    reads=updater['networkReads'];require(type(updater['networkReadCount']) is int and len(reads)==updater['networkReadCount'] and len(reads)>=1,'Actual updater network count')
    for r in reads:require(r.get('method')=='GET' and r.get('url')==f'https://api.github.com/repos/{REPO}/releases/latest' and r.get('status')==200 and r.get('tlsVersion') in ('TLSv1.2','TLSv1.3'),'Actual updater verified TLS GET')
    require(datetime.datetime.fromisoformat(load(ROOT/'updater-before.json')['verifiedAtUtc'].replace('Z','+00:00'))<datetime.datetime.fromisoformat(latest['published_at'].replace('Z','+00:00'))<=datetime.datetime.fromisoformat(updater['verifiedAtUtc'].replace('Z','+00:00')),'Actual before/after chronology')
    final=get('repos/'+REPO+'/releases/latest');latest_gate(final,a.release_id,wanted);require(normalize(final)==normalize(latest),'Latest changed during verification');save(stage/'latest-final.json',final)
    require(digest(a.review)['sha256']==a.review_sha256,'Independent review receipt changed during public verification')
    receipt={'kind':'VNCODE_1610_PUBLIC_PRESERVATION','result':'PASS','verifiedAtUtc':datetime.datetime.now(datetime.UTC).isoformat(),'version':VERSION,'sourceCommit':SOURCE,'ciRun':RUN,'releaseId':a.release_id,'publishedAtUtc':latest['published_at'],**preserved,'publicAssetsIndependentlyCompared':16,'assets':actual,'actualOriginal169Updater':'AVAILABLE 1.6.10','updaterMsi':msi,'independentReview':digest(a.review),'helper':digest(pathlib.Path(__file__)),'updaterIntegrity':digest(ROOT/'updater-after-integrity.json'),'scope':'Actual GitHub GET metadata and public payloads, independently compared to reviewed CI hashes; original unchanged published 1.6.9 updater executed on Linux through inherited Cloud proxy/verified CA with Windows MSI selection after network. No publication/seller API/license/signing/paid actions. Anonymous access or customer Windows GUI/installation are not established.'}
    save(ROOT/'public-readback-receipt.json',receipt);print(json.dumps(receipt,indent=2))
if __name__=='__main__':main()
