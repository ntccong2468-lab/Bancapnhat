#!/usr/bin/env python3
"""Offline, read-only comparison of saved GitHub metadata and downloaded release bytes.

Run `python3 verify-public.py --self-test` for synthetic negative controls.
Real verification requires separately collected, fully paginated before/after API
snapshots, independently reviewed build artifacts and the actual 1.6.7 updater
receipt. This tool does not fetch URLs, publish, or validate a seller installation.
"""
import argparse,copy,datetime,hashlib,json,os,pathlib,re,stat,sys,tempfile

VERSION='1.6.8'
SOURCE='38906a2108501029d9609ed1d071ee987c8b2637'
RELEASE=440439895
REPO='ntccong2468-lab/Bancapnhat'
CI_RUN=42828436446
REVIEW_HELPER_SHA='f9aa84ff14e11513fcc0079947ca0d884dc3fbabd1152ae30d784887febdeee5'
OLD_RELEASES_SHA='67fd095fe3442806192b7a2ed472f45b8a67e8df5fd70768e47f30ca036f3368'
OLD_TAGS_SHA='933fe02cb4d4b3352b09af72e7f7f89f6d05af838a64508bb561fd8b98ec8f01'
OLD_JAR_SHA='6e888e5402bd3e4b8536fda0e78c423c255fd74f34f6060cf7b21e0fc506c402'
OLD_JAR_MANIFEST_SHA='1a8d9da4e8a37f635590681af0f3d3cc8272fed586deaceb05fe7e7d5b0e9d3a'
PROBE_SOURCE_SHA='348ee21088c264220fd5161baf95c894131d04382ef3eae5be5adcec59675fca'
PROBE_CLASS_SHA='6977a8aedbbb734cdc16a741a6f9f6c3839dbc8adbc010ea1a29fe34b2fa6db4'
BEFORE_UPDATER_SHA='a46fe692401b10e60787c5ad88074d97d61b524d809d303ca93295b0edf3a034'
PROBE_SCOPE='Actual published unmodified 1.6.7 updater through inherited Cloud proxy and verified CA; blank local profile; Windows asset selection only after network; proxy may attach repository credentials; no installation, seller API write or license activation'
AUDIT=pathlib.Path('/tmp/vncode-168-release-audit')
PROBE=pathlib.Path('/tmp/vncode-168-exact167-probe')
REQUIRED=[f'VN-code-{VERSION}-Windows-x64.exe',f'VN-code-{VERSION}-Windows-x64.msi',f'VN-code-{VERSION}-Windows-x64.sha256',f'VN-code-{VERSION}-Windows-x64.zip',f'VN-code-{VERSION}-source.zip',f'VN-code-{VERSION}-history.bundle',f'VN-code-{VERSION}-verification.zip','build-info.json','release-notes.md','native-smoke.json','upgrade-baseline-smoke.json']
ASSETS=REQUIRED+['asset-manifest.json','SHA256SUMS','gs1-smoke.json','side-by-side-smoke.json','previous-baseline.json']

class VerificationError(Exception):pass
def require(condition,message):
    if not condition: raise VerificationError(message)
def pairs(items):
    result={}
    for key,value in items:
        require(key not in result,'duplicate-json-key:'+key);result[key]=value
    return result
def load(path):
    return json.loads(pathlib.Path(path).read_text(encoding='utf-8-sig'),object_pairs_hook=pairs)
def digest(path):
    p=pathlib.Path(path);s=p.lstat();require(stat.S_ISREG(s.st_mode) and not p.is_symlink(),'not-regular-file:'+p.name)
    h=hashlib.sha256()
    with p.open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
    after=p.lstat();require((s.st_size,s.st_mtime_ns,s.st_ino)==(after.st_size,after.st_mtime_ns,after.st_ino),'file-changed:'+p.name)
    return {'bytes':s.st_size,'sha256':h.hexdigest()}
def eq_fields(obj,values,label):
    require(isinstance(obj,dict),label+':not-object')
    for key,want in values.items():
        got=obj.get(key);require(type(got) is type(want) and got==want,label+':'+key)
def iso(value):
    try:
        t=datetime.datetime.fromisoformat(value.replace('Z','+00:00'));require(t.tzinfo is not None,'timestamp-without-offset');return t
    except (TypeError,ValueError,AttributeError): raise VerificationError('bad-timestamp')
def unique(items,key,label):
    require(isinstance(items,list),label+':not-list');result={}
    for item in items:
        require(isinstance(item,dict) and key in item,label+':missing-key');value=item[key]
        require(value not in result,label+':duplicate');result[value]=item
    return result
def normalize(value):
    if isinstance(value,dict):
        result={k:normalize(v) for k,v in value.items() if k!='download_count'}
        if 'assets' in result:
            require(isinstance(result['assets'],list),'assets:not-list')
            unique(result['assets'],'id','assets');result['assets']=sorted(result['assets'],key=lambda x:x['id'])
        return result
    if isinstance(value,list):return [normalize(x) for x in value]
    return value
def directory_assets(path):
    path=pathlib.Path(path);require(path.is_dir() and not path.is_symlink(),'asset-directory-invalid')
    entries=list(path.iterdir());require({x.name for x in entries}==set(ASSETS) and len(entries)==len(ASSETS),'asset-directory-set')
    result={p.name:digest(p) for p in entries}
    require(all(x['bytes']>0 for x in result.values()),'empty-asset');return result
def checksum_file(path,expected):
    entries={}
    for line in pathlib.Path(path).read_text(encoding='ascii').splitlines():
        m=re.fullmatch(r'([a-f0-9]{64})  ([A-Za-z0-9._-]+)',line);require(m is not None,'invalid-checksum')
        value,name=m.groups();require(name not in entries,'duplicate-checksum');entries[name]=value
    require(set(entries)==set(expected),'checksum-file-set')
    for name,item in expected.items():require(entries[name]==item['sha256'],'checksum-mismatch:'+name)
def verify(a):
    require(digest(a.before_releases)['sha256']==OLD_RELEASES_SHA,'before-release-snapshot-hash')
    require(digest(a.before_tags)['sha256']==OLD_TAGS_SHA,'before-tag-snapshot-hash')
    old=unique(load(a.before_releases),'id','old-releases');new=unique(load(a.after_releases),'id','new-releases')
    require(len(old)==17 and sum(len(x['assets']) for x in old.values())==274,'old-release-snapshot-count')
    require(RELEASE not in old and len(new)==18 and set(new)==set(old)|{RELEASE},'release-id-set')
    for key,value in old.items():require(normalize(value)==normalize(new[key]),'old-release-modified:'+str(key))
    before_tags=unique(load(a.before_tags),'ref','old-tags');after_tags=unique(load(a.after_tags),'ref','new-tags')
    tag='refs/tags/v'+VERSION
    require(len(before_tags)==46 and tag not in before_tags and len(after_tags)==47 and set(after_tags)==set(before_tags)|{tag},'tag-set')
    for key,value in before_tags.items():require(value==after_tags[key],'old-tag-modified:'+key)
    eq_fields(after_tags[tag]['object'],{'type':'commit','sha':SOURCE},'new-tag')
    latest=load(a.latest);release=new[RELEASE]
    eq_fields(latest,{'id':RELEASE,'tag_name':'v'+VERSION,'target_commitish':SOURCE,'draft':False,'prerelease':False,'immutable':True},'latest')
    require(isinstance(latest.get('published_at'),str) and bool(latest['published_at']),'latest-not-published')
    require(normalize(latest)==normalize(release),'latest-and-releases-disagree')
    remote=unique(latest.get('assets'),'name','public-assets');require(set(remote)==set(ASSETS) and len(remote)==16,'public-asset-set')
    reviewed=directory_assets(a.reviewed_dir);public=directory_assets(a.public_dir)
    require(reviewed==public,'public-bytes-not-identical-to-reviewed-artifacts')
    receipt=load(a.review_receipt)
    eq_fields(receipt,{'kind':'VNCODE_168_OFFLINE_CANDIDATE_VERIFICATION','result':'PASS','version':VERSION,'sourceCommit':SOURCE,'ciRun':CI_RUN,'workflowId':392732536,'helperSha256':REVIEW_HELPER_SHA},'independent-review')
    require(receipt.get('assets')==reviewed,'review-receipt-asset-hashes')
    for name,metadata in remote.items():
        eq_fields(metadata,{'state':'uploaded','size':public[name]['bytes'],'digest':'sha256:'+public[name]['sha256'],'browser_download_url':f'https://github.com/{REPO}/releases/download/v{VERSION}/{name}'},'public-asset:'+name)
    manifest=load(pathlib.Path(a.public_dir)/'asset-manifest.json')
    eq_fields(manifest,{'schema':1,'version':VERSION,'sourceCommit':SOURCE},'manifest')
    require(manifest.get('files')=={n:public[n] for n in REQUIRED},'manifest-files')
    checksum_file(pathlib.Path(a.public_dir)/'SHA256SUMS',{n:public[n] for n in REQUIRED+['asset-manifest.json']})
    checksum_file(pathlib.Path(a.public_dir)/f'VN-code-{VERSION}-Windows-x64.sha256',{f'VN-code-{VERSION}-Windows-x64.exe':public[f'VN-code-{VERSION}-Windows-x64.exe']})
    info=load(pathlib.Path(a.public_dir)/'build-info.json');eq_fields(info,{'version':VERSION,'sourceCommit':SOURCE,'runId':CI_RUN},'build-info')
    eq_fields(info.get('installer'),{'name':f'VN-code-{VERSION}-Windows-x64.exe','bytes':public[f'VN-code-{VERSION}-Windows-x64.exe']['bytes'],'sha256':public[f'VN-code-{VERSION}-Windows-x64.exe']['sha256']},'build-installer')
    eq_fields(info.get('verification'),{'fullJavaSuiteRun':True,'nativeSmokeRun':True,'baselineUpgradeRun':True},'build-verification')
    require(digest(a.probe_jar)['sha256']==OLD_JAR_SHA,'updater-installed-jar-hash')
    require(digest(a.probe_manifest)['sha256']==OLD_JAR_MANIFEST_SHA,'updater-jar-manifest-hash')
    jar_manifest=load(a.probe_manifest);require(jar_manifest.get('jarCount')==75 and len(jar_manifest.get('files',[]))==75,'updater-jar-manifest-count')
    jars=unique(jar_manifest['files'],'file','updater-jars');jar_dir=pathlib.Path(a.probe_jar).parent
    require({p.name for p in jar_dir.glob('*.jar')}==set(jars),'updater-jar-set')
    for name,wanted in jars.items():
        require('/' not in name and '\\' not in name and name not in ('.','..'),'invalid-updater-jar-name')
        require(digest(jar_dir/name)=={'bytes':wanted['bytes'],'sha256':wanted['sha256']},'updater-jar-modified:'+name)
    require(digest(a.probe_source)['sha256']==PROBE_SOURCE_SHA and digest(a.probe_class)['sha256']==PROBE_CLASS_SHA,'updater-probe-hash')
    require(digest(a.updater_before)['sha256']==BEFORE_UPDATER_SHA,'updater-before-hash')
    before=load(a.updater_before);eq_fields(before,{'installedBinaryVersion':'1.6.7','checkStatus':'CURRENT','latestVersion':'1.6.7','explicitAuthorizationHeaderInApp':False},'updater-before')
    after=load(a.updater_after);msi=public[f'VN-code-{VERSION}-Windows-x64.msi']
    eq_fields(after,{'installedBinaryVersion':'1.6.7','configuredDefaultSource':f'https://github.com/{REPO}','normalizedSource':f'https://api.github.com/repos/{REPO}','manifestPublicKeyPinned':False,'checkStatus':'AVAILABLE','errorCode':'','latestVersion':VERSION,'selectedDownload':f'https://github.com/{REPO}/releases/download/v{VERSION}/VN-code-{VERSION}-Windows-x64.msi','sha256':msi['sha256'],'size':msi['bytes'],'explicitAuthorizationHeaderInApp':False,'anonymousNetworkAccessIndependentlyProven':False,'sellerPopupObserved':False,'scope':PROBE_SCOPE},'updater-after')
    require(iso(before.get('verifiedAtUtc'))<iso(latest['published_at']),'updater-baseline-observation-not-before-publication')
    require(iso(after.get('verifiedAtUtc'))>=iso(latest['published_at']),'updater-observation-predates-publication')
    return {'kind':'VN_CODE_168_PUBLIC_PRESERVATION','result':'PASS','version':VERSION,'sourceCommit':SOURCE,'releaseId':RELEASE,'oldReleasesPreserved':len(old),'oldAssetsPreserved':274,'oldTagsPreserved':len(before_tags),'newPublicAssets':len(public),'assets':public,'actual167UpdaterAvailable168':True,'updaterMsi':msi,'verifiedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Offline comparison of supplied snapshots, independently reviewed build bytes, downloaded public files and actual 1.6.7 updater receipt. No network, installation, seller data or publication.','limits':['Snapshot completeness and GitHub provenance depend on the collector, TLS/proxy and input integrity; this helper performs no network calls.','Updater probe uses original 1.6.7 application JAR on Linux with Windows asset selection simulated; Cloud proxy may attach credentials. Anonymous access, seller popup and actual installation are not established.','Independent build-verifier receipt is an input, not cryptographically signed; compiled-code/source linkage and native MSI containment rely on its CI/smoke evidence.']}

def self_test():
    with tempfile.TemporaryDirectory(prefix='vncode-public-test-',dir='/tmp') as td:
        root=pathlib.Path(td);review=root/'reviewed';public=root/'public';review.mkdir();public.mkdir()
        for name in REQUIRED:(review/name).write_bytes(('fixture-'+name).encode())
        (review/'asset-manifest.json').write_text(json.dumps({'schema':1,'version':VERSION,'sourceCommit':SOURCE,'files':{n:digest(review/n) for n in REQUIRED}}))
        (review/'SHA256SUMS').write_text(''.join(digest(review/n)['sha256']+'  '+n+'\n' for n in REQUIRED+['asset-manifest.json']))
        (review/f'VN-code-{VERSION}-Windows-x64.sha256').write_text(digest(review/f'VN-code-{VERSION}-Windows-x64.exe')['sha256']+'  '+f'VN-code-{VERSION}-Windows-x64.exe\n')
        # Build info/checksum/manifest depend on each other; finalize them in order.
        exe=digest(review/f'VN-code-{VERSION}-Windows-x64.exe')
        (review/'build-info.json').write_text(json.dumps({'version':VERSION,'sourceCommit':SOURCE,'runId':CI_RUN,'installer':{'name':f'VN-code-{VERSION}-Windows-x64.exe',**exe},'verification':{'fullJavaSuiteRun':True,'nativeSmokeRun':True,'baselineUpgradeRun':True}}))
        for n in ['gs1-smoke.json','side-by-side-smoke.json','previous-baseline.json']:(review/n).write_bytes(('fixture-'+n).encode())
        (review/'asset-manifest.json').write_text(json.dumps({'schema':1,'version':VERSION,'sourceCommit':SOURCE,'files':{n:digest(review/n) for n in REQUIRED}}))
        (review/'SHA256SUMS').write_text(''.join(digest(review/n)['sha256']+'  '+n+'\n' for n in REQUIRED+['asset-manifest.json']))
        for n in ASSETS:(public/n).write_bytes((review/n).read_bytes())
        assets=directory_assets(review);base_releases=load(AUDIT/'releases-before.json');base_tags=load(AUDIT/'tags-before.json')
        candidate={'id':RELEASE,'tag_name':'v'+VERSION,'target_commitish':SOURCE,'draft':False,'prerelease':False,'immutable':True,'published_at':'2026-11-10T14:00:00Z','assets':[{'id':1000000+i,'name':n,'state':'uploaded','size':assets[n]['bytes'],'digest':'sha256:'+assets[n]['sha256'],'browser_download_url':f'https://github.com/{REPO}/releases/download/v{VERSION}/{n}','download_count':1} for i,n in enumerate(ASSETS)]}
        tag={'ref':'refs/tags/v'+VERSION,'object':{'type':'commit','sha':SOURCE}}
        def save(name,obj):p=root/name;p.write_text(json.dumps(obj));return p
        releasepath=save('after.json',base_releases+[candidate]);tagpath=save('tags.json',base_tags+[tag]);latestpath=save('latest.json',candidate)
        receiptpath=save('review.json',{'kind':'VNCODE_168_OFFLINE_CANDIDATE_VERIFICATION','result':'PASS','version':VERSION,'sourceCommit':SOURCE,'ciRun':CI_RUN,'workflowId':392732536,'helperSha256':REVIEW_HELPER_SHA,'assets':assets})
        msi=assets[f'VN-code-{VERSION}-Windows-x64.msi'];after=save('updater.json',{'verifiedAtUtc':'2026-11-10T15:00:00Z','installedBinaryVersion':'1.6.7','configuredDefaultSource':f'https://github.com/{REPO}','normalizedSource':f'https://api.github.com/repos/{REPO}','manifestPublicKeyPinned':False,'checkStatus':'AVAILABLE','errorCode':'','latestVersion':VERSION,'selectedDownload':f'https://github.com/{REPO}/releases/download/v{VERSION}/VN-code-{VERSION}-Windows-x64.msi','sha256':msi['sha256'],'size':msi['bytes'],'explicitAuthorizationHeaderInApp':False,'anonymousNetworkAccessIndependentlyProven':False,'sellerPopupObserved':False,'scope':PROBE_SCOPE})
        a=argparse.Namespace(before_releases=AUDIT/'releases-before.json',before_tags=AUDIT/'tags-before.json',after_releases=releasepath,after_tags=tagpath,latest=latestpath,reviewed_dir=review,public_dir=public,review_receipt=receiptpath,probe_jar=PROBE/'app/VNcode-1.6.7.jar',probe_manifest=PROBE/'jar-manifest.json',probe_source=PROBE/'Installed167UpdateProbe.java',probe_class=PROBE/'classes/com/vncode/app/integration/update/Installed167UpdateProbe.class',updater_before=PROBE/'before.json',updater_after=after)
        verify(a);controls=0
        def expect_fail():
            nonlocal controls
            try:verify(a)
            except (VerificationError,KeyError,TypeError,ValueError):controls+=1;return
            raise AssertionError('negative control unexpectedly passed')
        original=base_releases[0]['assets'][0]['digest'];base_releases[0]['assets'][0]['digest']='sha256:'+'0'*64;save('after.json',base_releases+[candidate]);expect_fail();base_releases[0]['assets'][0]['digest']=original;save('after.json',base_releases+[candidate])
        save('tags.json',base_tags[:-1]+[tag]);expect_fail();save('tags.json',base_tags+[tag])
        candidate['immutable']=False;save('after.json',base_releases+[candidate]);save('latest.json',candidate);expect_fail();candidate['immutable']=True;save('after.json',base_releases+[candidate]);save('latest.json',candidate)
        (public/'release-notes.md').write_text('changed');expect_fail();(public/'release-notes.md').write_bytes((review/'release-notes.md').read_bytes())
        changed=load(after);changed['size']+=1;save('updater.json',changed);expect_fail();changed['size']-=1;save('updater.json',changed)
        changed=load(receiptpath);changed['sourceCommit']='0'*40;save('review.json',changed);expect_fail();changed['sourceCommit']=SOURCE;save('review.json',changed)
        save('after.json',base_releases[1:]+[candidate]);expect_fail();save('after.json',base_releases+[candidate])
        changed=load(after);changed['checkStatus']='CURRENT';save('updater.json',changed);expect_fail();changed['checkStatus']='AVAILABLE';save('updater.json',changed)
        changed=load(after);changed['verifiedAtUtc']='2026-11-10T13:59:00Z';save('updater.json',changed);expect_fail();changed['verifiedAtUtc']='2026-11-10T15:00:00Z';save('updater.json',changed)
        changed=copy.deepcopy(tag);changed['object']['sha']='0'*40;save('tags.json',base_tags+[changed]);expect_fail();save('tags.json',base_tags+[tag])
        old_count=base_releases[0]['assets'][0]['download_count'];base_releases[0]['assets'][0]['download_count']=old_count+1;save('after.json',base_releases+[candidate]);verify(a)
        print(json.dumps({'selfTest':'PASS','syntheticPositive':True,'negativeControlsPassed':controls,'downloadCounterIgnored':True,'actualReleaseVerified':False}))

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--self-test',action='store_true')
    p.add_argument('--before-releases',type=pathlib.Path,default=AUDIT/'releases-before.json');p.add_argument('--before-tags',type=pathlib.Path,default=AUDIT/'tags-before.json')
    for key in ['after-releases','after-tags','latest','reviewed-dir','public-dir','review-receipt','updater-after']:p.add_argument('--'+key,type=pathlib.Path)
    p.add_argument('--probe-jar',type=pathlib.Path,default=PROBE/'app/VNcode-1.6.7.jar');p.add_argument('--probe-manifest',type=pathlib.Path,default=PROBE/'jar-manifest.json');p.add_argument('--probe-source',type=pathlib.Path,default=PROBE/'Installed167UpdateProbe.java');p.add_argument('--probe-class',type=pathlib.Path,default=PROBE/'classes/com/vncode/app/integration/update/Installed167UpdateProbe.class');p.add_argument('--updater-before',type=pathlib.Path,default=PROBE/'before.json')
    p.add_argument('--output',type=pathlib.Path,default=AUDIT/'public-preservation-verified.json');a=p.parse_args()
    if a.self_test:self_test();return
    for name in ['after_releases','after_tags','latest','reviewed_dir','public_dir','review_receipt','updater_after']:require(getattr(a,name) is not None,'missing-input:'+name)
    require(a.output.resolve().parent==AUDIT.resolve(),'output-must-stay-in-audit-directory')
    require(not a.output.exists() and not a.output.is_symlink(),'output-already-exists')
    report=verify(a);report['helperSha256']=digest(__file__)['sha256']
    with a.output.open('x',encoding='utf-8') as stream:stream.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'result':'PASS','output':str(a.output),'sha256':digest(a.output)['sha256']}))
if __name__=='__main__':
    try:main()
    except (VerificationError,OSError,KeyError,TypeError,ValueError,json.JSONDecodeError) as e:print('PUBLIC_VERIFICATION_FAILED:',str(e),file=sys.stderr);sys.exit(1)
