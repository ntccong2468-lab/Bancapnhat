#!/usr/bin/env python3
"""Offline, read-only comparison of saved GitHub metadata and downloaded release bytes.

Adapted from the archived 1.6.8 offline verifier; the archive is unchanged.
Run `python3 verify-public.py --self-test` for synthetic negative controls.
Actual acceptance requires an explicit positive --release-id and all real inputs.
Real verification requires separately collected, fully paginated before/after API
snapshots, independently reviewed build artifacts and the actual 1.6.8 updater
receipt. This tool does not fetch URLs, publish, or validate a seller installation.
"""
import argparse,copy,datetime,hashlib,json,os,pathlib,re,stat,sys,tempfile

VERSION='1.6.9'
SOURCE='4ef0f1e2b8ef7b28190de3034b0c67ebf615be0b'
REPO='ntccong2468-lab/Bancapnhat'
CI_RUN=43131000128
REVIEW_HELPER_SHA='14a790dc658ffdb3f4cdf30259e2b8ef15898b2abd89d7d107f3f6054a1c66b9'
OLD_RELEASES_SHA='0aaa3523e42be7a1d0708b83b9c35c1f9590bf7b2de5c069b9a76afc1db18788'
OLD_TAGS_SHA='677d9dc518f676ad40bc59761e6a2551017fb6d1b0583866d35928f64fc10293'
OLD_JAR_SHA='50debe166d5d09dc31724cf61d48d851abf2e1eb43bb2e3b9828c2462445022a'
OLD_JAR_MANIFEST_SHA='bfedb457aca9c010204841f044fafdc2d0862f154d8d265bd164df928a679cac'
PROBE_SOURCE_SHA='bd4f7c0e2843f6bae841eaaed26cdcb0db26bd1e0f1ae9b2d2994b7dca96e5dc'
PROBE_CLASS_SHA='da96e2c79c9f23e70e5e00253f10c8f045f5c68855359eea52a2a05c006feae6'
BEFORE_UPDATER_SHA='cd449c3611587fa1ebcbb0bd91b41343f9db3cb254e5f4d2ca91838c78bb4fb3'
PROBE_RUNNER_SHA='4ab378c93c3b084d7b9578432f83efd1f63f3a4390ec7fdb2c96216549dff1ca'
OLD_ZIP_SHA='5e4a8ac20439df64a61b847571f9cd8581b817f661749481d29fd6c76126e141'
BEFORE_INTEGRITY_SHA='4609cbded8634ded1ee1926768f21cea152e396a52499a8466a7906a18ffe9ae'
PROBE_SCOPE='Actual published unmodified 1.6.8 updater through inherited Cloud proxy and verified CA; blank local profile; Windows asset selection only after network; proxy may attach repository credentials; no installation, seller API write or license activation'
AUDIT=pathlib.Path('/tmp/vncode-169-release-audit')
PROBE=pathlib.Path('/tmp/vncode-169-exact168-probe')
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
    release_id=a.release_id
    require(type(release_id) is int and release_id>0,'positive-explicit-release-id-required')
    require(digest(a.before_releases)['sha256']==OLD_RELEASES_SHA,'before-release-snapshot-hash')
    require(digest(a.before_tags)['sha256']==OLD_TAGS_SHA,'before-tag-snapshot-hash')
    old=unique(load(a.before_releases),'id','old-releases');new=unique(load(a.after_releases),'id','new-releases')
    require(len(old)==18 and sum(len(x['assets']) for x in old.values())==290,'old-release-snapshot-count')
    require(release_id not in old and len(new)==19 and set(new)==set(old)|{release_id},'release-id-set')
    require(sum(len(x['assets']) for x in new.values())==306,'new-release-snapshot-asset-count')
    for key,value in old.items():require(normalize(value)==normalize(new[key]),'old-release-modified:'+str(key))
    before_tags=unique(load(a.before_tags),'ref','old-tags');after_tags=unique(load(a.after_tags),'ref','new-tags')
    tag='refs/tags/v'+VERSION
    require(len(before_tags)==47 and tag not in before_tags and len(after_tags)==48 and set(after_tags)==set(before_tags)|{tag},'tag-set')
    for key,value in before_tags.items():require(value==after_tags[key],'old-tag-modified:'+key)
    eq_fields(after_tags[tag]['object'],{'type':'commit','sha':SOURCE},'new-tag')
    latest=load(a.latest);release=new[release_id]
    eq_fields(latest,{'id':release_id,'tag_name':'v'+VERSION,'target_commitish':SOURCE,'draft':False,'prerelease':False,'immutable':True},'latest')
    require(isinstance(latest.get('published_at'),str) and bool(latest['published_at']),'latest-not-published')
    require(normalize(latest)==normalize(release),'latest-and-releases-disagree')
    remote=unique(latest.get('assets'),'name','public-assets');require(set(remote)==set(ASSETS) and len(remote)==16,'public-asset-set')
    reviewed=directory_assets(a.reviewed_dir);public=directory_assets(a.public_dir)
    require(reviewed==public,'public-bytes-not-identical-to-reviewed-artifacts')
    require(digest(a.review_helper)['sha256']==REVIEW_HELPER_SHA,'independent-review-helper-hash')
    receipt=load(a.review_receipt)
    eq_fields(receipt,{'kind':'VNCODE_169_OFFLINE_CANDIDATE_VERIFICATION','result':'PASS','version':VERSION,'sourceCommit':SOURCE,'ciRun':CI_RUN,'workflowId':392732536,'helperSha256':REVIEW_HELPER_SHA},'independent-review')
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
    require(digest(a.probe_zip)=={'bytes':148326903,'sha256':OLD_ZIP_SHA},'updater-public-zip-hash')
    require(digest(a.probe_manifest)['sha256']==OLD_JAR_MANIFEST_SHA,'updater-jar-manifest-hash')
    require(digest(a.probe_after_manifest)['sha256']==OLD_JAR_MANIFEST_SHA,'updater-after-jar-manifest-hash')
    jar_manifest=load(a.probe_manifest);require(jar_manifest.get('jarCount')==75 and len(jar_manifest.get('files',[]))==75,'updater-jar-manifest-count')
    eq_fields(jar_manifest,{'kind':'VNCODE_ORIGINAL_PUBLIC_168_JAR_MANIFEST','assetUrl':f'https://github.com/{REPO}/releases/download/v1.6.8/VN-code-1.6.8-Windows-x64.zip','assetBytes':148326903,'assetSha256':OLD_ZIP_SHA},'updater-original-public-package')
    jars=unique(jar_manifest['files'],'path','updater-jars');probe_root=pathlib.Path(a.probe_manifest).parent
    require({p.relative_to(probe_root).as_posix() for p in (probe_root/'app').rglob('*.jar')}==set(jars),'updater-jar-set')
    require(pathlib.Path(a.probe_jar).resolve()==(probe_root/'app/VNcode-1.6.8.jar').resolve(),'updater-app-jar-location')
    for name,wanted in jars.items():
        rel=pathlib.PurePosixPath(name)
        require(not rel.is_absolute() and '\\' not in name and '..' not in rel.parts and '.' not in rel.parts and rel.parts[0]=='app' and rel.suffix=='.jar','invalid-updater-jar-path')
        target=probe_root/name;require(target.resolve().is_relative_to(probe_root.resolve()),'updater-jar-path-escape')
        require(digest(target)=={'bytes':wanted['bytes'],'sha256':wanted['sha256']},'updater-jar-modified:'+name)
    require(digest(a.probe_source)['sha256']==PROBE_SOURCE_SHA and digest(a.probe_class)['sha256']==PROBE_CLASS_SHA and digest(a.probe_runner)['sha256']==PROBE_RUNNER_SHA,'updater-probe-hash')
    classes=pathlib.Path(a.probe_class).parents[5]
    require(classes.name=='classes' and {p.relative_to(classes).as_posix() for p in classes.rglob('*.class')}=={'com/vncode/app/integration/update/Installed168UpdateProbe.class'},'updater-probe-class-set')
    require(digest(a.updater_before)['sha256']==BEFORE_UPDATER_SHA,'updater-before-hash')
    require(digest(a.updater_before_integrity)['sha256']==BEFORE_INTEGRITY_SHA,'updater-before-integrity-hash')
    before=load(a.updater_before);eq_fields(before,{'installedBinaryVersion':'1.6.8','checkStatus':'CURRENT','latestVersion':'1.6.8','explicitAuthorizationHeaderInApp':False},'updater-before')
    after=load(a.updater_after);msi=public[f'VN-code-{VERSION}-Windows-x64.msi']
    eq_fields(after,{'installedBinaryVersion':'1.6.8','configuredDefaultSource':f'https://github.com/{REPO}','normalizedSource':f'https://api.github.com/repos/{REPO}','manifestPublicKeyPinned':False,'checkStatus':'AVAILABLE','errorCode':'','latestVersion':VERSION,'selectedDownload':f'https://github.com/{REPO}/releases/download/v{VERSION}/VN-code-{VERSION}-Windows-x64.msi','sha256':msi['sha256'],'size':msi['bytes'],'networkPlatform':'Linux','windowsSelectionOnlyAfterNetwork':True,'blankLocalProfile':True,'loadedOriginalAppJar':'VNcode-1.6.8.jar','originalAppClassesLoadedFromPublishedJar':True,'explicitAuthorizationHeaderInApp':False,'anonymousNetworkAccessIndependentlyProven':False,'sellerPopupObserved':False,'scope':PROBE_SCOPE},'updater-after')
    reads=after.get('networkReads');require(isinstance(reads,list) and type(after.get('networkReadCount')) is int and len(reads)==after['networkReadCount'] and len(reads)>=1,'updater-network-read-count')
    for read in reads:
        eq_fields(read,{'method':'GET','url':f'https://api.github.com/repos/{REPO}/releases/latest','status':200},'updater-network-read')
        require(read.get('tlsVersion') in ('TLSv1.2','TLSv1.3'),'updater-network-tls')
    integrity=load(a.updater_after_integrity)
    eq_fields(integrity,{'kind':'VNCODE_ORIGINAL_PUBLIC_168_UPDATER_INTEGRITY','stage':'after','installedBinaryVersion':'1.6.8','all75OriginalJarHashesUnchanged':True,'status':'AVAILABLE','latestVersion':VERSION,'selectedMsiUrl':after['selectedDownload'],'selectedMsiSha256':msi['sha256'],'selectedMsiBytes':msi['bytes']},'updater-after-integrity')
    for name,path in {'originalJarManifest':a.probe_manifest,'afterJarManifest':a.probe_after_manifest,'originalPublicZip':a.probe_zip,'originalAppJar':a.probe_jar,'probeSource':a.probe_source,'probeClass':a.probe_class,'probeRunner':a.probe_runner,'updaterReceipt':a.updater_after}.items():
        require(integrity.get(name)==digest(path),'updater-after-integrity-digest:'+name)
    require(iso(integrity.get('verifiedAtUtc'))>=iso(after.get('verifiedAtUtc')),'updater-integrity-predates-observation')
    require(iso(before.get('verifiedAtUtc'))<iso(latest['published_at']),'updater-baseline-observation-not-before-publication')
    require(iso(after.get('verifiedAtUtc'))>=iso(latest['published_at']),'updater-observation-predates-publication')
    return {'kind':'VN_CODE_169_PUBLIC_PRESERVATION','result':'PASS','version':VERSION,'sourceCommit':SOURCE,'releaseId':release_id,'oldReleasesPreserved':len(old),'oldAssetsPreserved':290,'totalReleasesAfter':19,'totalAssetsAfter':306,'totalRawTagRefsAfter':48,'oldTagsPreserved':len(before_tags),'newPublicAssets':len(public),'assets':public,'actual168UpdaterAvailable169':True,'updaterMsi':msi,'verifiedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Offline comparison of supplied snapshots, independently reviewed build bytes, downloaded public files and actual 1.6.8 updater receipt. No network, installation, seller data or publication.','limits':['Snapshot completeness and GitHub provenance depend on the collector, TLS/proxy and input integrity; this helper performs no network calls.','Updater probe uses original 1.6.8 application JAR on Linux with Windows asset selection simulated; Cloud proxy may attach credentials. Anonymous access, seller popup and actual installation are not established.','Independent build-verifier receipt is an input, not cryptographically signed; compiled-code/source linkage and native MSI containment rely on its CI/smoke evidence.']}

def self_test():
    with tempfile.TemporaryDirectory(prefix='vncode169-public-test-',dir='/tmp') as td:
        root=pathlib.Path(td);review=root/'reviewed';public=root/'public';review.mkdir();public.mkdir()
        release_id=900000001
        for name in ASSETS:(review/name).write_bytes(('synthetic-fixture-'+name).encode())
        exe=digest(review/f'VN-code-{VERSION}-Windows-x64.exe')
        (review/f'VN-code-{VERSION}-Windows-x64.sha256').write_text(exe['sha256']+'  '+f'VN-code-{VERSION}-Windows-x64.exe\n')
        (review/'build-info.json').write_text(json.dumps({'version':VERSION,'sourceCommit':SOURCE,'runId':CI_RUN,'installer':{'name':f'VN-code-{VERSION}-Windows-x64.exe',**exe},'verification':{'fullJavaSuiteRun':True,'nativeSmokeRun':True,'baselineUpgradeRun':True}}))
        (review/'asset-manifest.json').write_text(json.dumps({'schema':1,'version':VERSION,'sourceCommit':SOURCE,'files':{n:digest(review/n) for n in REQUIRED}}))
        (review/'SHA256SUMS').write_text(''.join(digest(review/n)['sha256']+'  '+n+'\n' for n in REQUIRED+['asset-manifest.json']))
        for n in ASSETS:(public/n).write_bytes((review/n).read_bytes())
        assets=directory_assets(review);base_releases=load(AUDIT/'releases-before.json');base_tags=load(AUDIT/'tag-refs-before.json')
        publication=iso(load(AUDIT/'updater-before.json')['verifiedAtUtc'])+datetime.timedelta(minutes=1)
        candidate={'id':release_id,'tag_name':'v'+VERSION,'target_commitish':SOURCE,'draft':False,'prerelease':False,'immutable':True,'published_at':publication.isoformat(),'assets':[{'id':1000000+i,'name':n,'state':'uploaded','size':assets[n]['bytes'],'digest':'sha256:'+assets[n]['sha256'],'browser_download_url':f'https://github.com/{REPO}/releases/download/v{VERSION}/{n}','download_count':1} for i,n in enumerate(ASSETS)]}
        tag={'ref':'refs/tags/v'+VERSION,'object':{'type':'commit','sha':SOURCE}}
        def save(name,obj):p=root/name;p.write_text(json.dumps(obj));return p
        releasepath=save('after.json',base_releases+[candidate]);tagpath=save('tags.json',base_tags+[tag]);latestpath=save('latest.json',candidate)
        receiptpath=save('review.json',{'kind':'VNCODE_169_OFFLINE_CANDIDATE_VERIFICATION','result':'PASS','version':VERSION,'sourceCommit':SOURCE,'ciRun':CI_RUN,'workflowId':392732536,'helperSha256':REVIEW_HELPER_SHA,'assets':assets})
        msi=assets[f'VN-code-{VERSION}-Windows-x64.msi']
        after=save('updater.json',{'verifiedAtUtc':(publication+datetime.timedelta(minutes=1)).isoformat(),'installedBinaryVersion':'1.6.8','configuredDefaultSource':f'https://github.com/{REPO}','normalizedSource':f'https://api.github.com/repos/{REPO}','manifestPublicKeyPinned':False,'checkStatus':'AVAILABLE','errorCode':'','latestVersion':VERSION,'selectedDownload':f'https://github.com/{REPO}/releases/download/v{VERSION}/VN-code-{VERSION}-Windows-x64.msi','sha256':msi['sha256'],'size':msi['bytes'],'networkPlatform':'Linux','windowsSelectionOnlyAfterNetwork':True,'blankLocalProfile':True,'loadedOriginalAppJar':'VNcode-1.6.8.jar','originalAppClassesLoadedFromPublishedJar':True,'networkReadCount':1,'networkReads':[{'method':'GET','url':f'https://api.github.com/repos/{REPO}/releases/latest','status':200,'tlsVersion':'TLSv1.3'}],'explicitAuthorizationHeaderInApp':False,'anonymousNetworkAccessIndependentlyProven':False,'sellerPopupObserved':False,'scope':PROBE_SCOPE})
        a=argparse.Namespace(release_id=release_id,before_releases=AUDIT/'releases-before.json',before_tags=AUDIT/'tag-refs-before.json',after_releases=releasepath,after_tags=tagpath,latest=latestpath,reviewed_dir=review,public_dir=public,review_receipt=receiptpath,review_helper=pathlib.Path('/tmp/vncode-169-independent-verifier/verify.py'),probe_jar=PROBE/'app/VNcode-1.6.8.jar',probe_zip=PROBE/'VN-code-1.6.8-Windows-x64.zip',probe_manifest=PROBE/'jar-manifest-before.json',probe_after_manifest=root/'jar-manifest-after.json',probe_source=PROBE/'Installed168UpdateProbe.java',probe_class=PROBE/'classes/com/vncode/app/integration/update/Installed168UpdateProbe.class',probe_runner=PROBE/'run-probe.py',updater_before=AUDIT/'updater-before.json',updater_before_integrity=AUDIT/'updater-before-integrity.json',updater_after=after,updater_after_integrity=root/'after-integrity.json')
        a.probe_after_manifest.write_bytes(a.probe_manifest.read_bytes())
        def save_integrity():
            value=load(after)
            integrity={'kind':'VNCODE_ORIGINAL_PUBLIC_168_UPDATER_INTEGRITY','stage':'after','installedBinaryVersion':'1.6.8','all75OriginalJarHashesUnchanged':True,'status':'AVAILABLE','latestVersion':VERSION,'selectedMsiUrl':value['selectedDownload'],'selectedMsiSha256':value['sha256'],'selectedMsiBytes':value['size'],'verifiedAtUtc':(publication+datetime.timedelta(minutes=2)).isoformat()}
            for name,path in {'originalJarManifest':a.probe_manifest,'afterJarManifest':a.probe_after_manifest,'originalPublicZip':a.probe_zip,'originalAppJar':a.probe_jar,'probeSource':a.probe_source,'probeClass':a.probe_class,'probeRunner':a.probe_runner,'updaterReceipt':after}.items():integrity[name]=digest(path)
            save('after-integrity.json',integrity)
        save_integrity();verify(a);controls=[]
        def expect_fail(label):
            try:verify(a)
            except (VerificationError,KeyError,TypeError,ValueError):controls.append(label);return
            raise AssertionError('negative control unexpectedly passed:'+label)
        original=base_releases[0]['assets'][0]['digest'];base_releases[0]['assets'][0]['digest']='sha256:'+'0'*64;save('after.json',base_releases+[candidate]);expect_fail('old-asset-change');base_releases[0]['assets'][0]['digest']=original;save('after.json',base_releases+[candidate])
        save('tags.json',base_tags[:-1]+[tag]);expect_fail('old-tag-missing');save('tags.json',base_tags+[tag])
        old_ref=base_tags[0]['object']['sha'];base_tags[0]['object']['sha']='0'*40;save('tags.json',base_tags+[tag]);expect_fail('old-raw-ref-changed');base_tags[0]['object']['sha']=old_ref;save('tags.json',base_tags+[tag])
        for field,bad in [('immutable',False),('target_commitish','0'*40),('id',release_id+1)]:
            original=candidate[field];candidate[field]=bad;save('after.json',base_releases+[candidate]);save('latest.json',candidate);expect_fail('candidate-'+field);candidate[field]=original;save('after.json',base_releases+[candidate]);save('latest.json',candidate)
        (public/'release-notes.md').write_text('changed');expect_fail('public-bytes-changed');(public/'release-notes.md').write_bytes((review/'release-notes.md').read_bytes())
        changed=load(receiptpath);changed['sourceCommit']='0'*40;save('review.json',changed);expect_fail('review-source');changed['sourceCommit']=SOURCE;save('review.json',changed)
        changed=copy.deepcopy(tag);changed['object']['sha']='0'*40;save('tags.json',base_tags+[changed]);expect_fail('new-tag-source');save('tags.json',base_tags+[tag])
        for field,bad in [('size',msi['bytes']+1),('sha256','0'*64),('checkStatus','CURRENT'),('windowsSelectionOnlyAfterNetwork',False),('networkReadCount',0),('verifiedAtUtc',(publication-datetime.timedelta(seconds=1)).isoformat())]:
            changed=load(after);original=changed[field];changed[field]=bad;save('updater.json',changed);save_integrity();expect_fail('updater-'+field);changed[field]=original;save('updater.json',changed);save_integrity()
        changed=load(after);changed['networkReads'][0]['tlsVersion']='TLSv1';save('updater.json',changed);save_integrity();expect_fail('updater-tls');changed['networkReads'][0]['tlsVersion']='TLSv1.3';save('updater.json',changed);save_integrity()
        changed=load(a.updater_after_integrity);changed['probeClass']['sha256']='0'*64;save('after-integrity.json',changed);expect_fail('integrity-class-digest');save_integrity()
        for bad in [None,0,-1,True,release_id+1]:
            a.release_id=bad;expect_fail('explicit-release-id-'+str(bad))
        a.release_id=release_id
        a.probe_after_manifest.write_text('{}');expect_fail('after-jar-manifest');a.probe_after_manifest.write_bytes(a.probe_manifest.read_bytes())
        count=base_releases[0]['assets'][0]['download_count'];base_releases[0]['assets'][0]['download_count']=count+1;save('after.json',base_releases+[candidate]);verify(a)
        print(json.dumps({'kind':'VNCODE_169_PUBLIC_VERIFIER_SELF_TEST','result':'PASS','syntheticPositive':True,'negativeControlsPassed':len(controls),'negativeControls':controls,'downloadCounterIgnored':True,'actualReleaseVerified':False}))

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--self-test',action='store_true');p.add_argument('--release-id',type=int,help='Required positive actual published release ID; never inferred from metadata')
    p.add_argument('--before-releases',type=pathlib.Path,default=AUDIT/'releases-before.json');p.add_argument('--before-tags',type=pathlib.Path,default=AUDIT/'tag-refs-before.json')
    for key in ['after-releases','after-tags','latest','reviewed-dir','public-dir','review-receipt','updater-after']:p.add_argument('--'+key,type=pathlib.Path)
    p.add_argument('--probe-jar',type=pathlib.Path,default=PROBE/'app/VNcode-1.6.8.jar');p.add_argument('--probe-manifest',type=pathlib.Path,default=PROBE/'jar-manifest-before.json');p.add_argument('--probe-source',type=pathlib.Path,default=PROBE/'Installed168UpdateProbe.java');p.add_argument('--probe-class',type=pathlib.Path,default=PROBE/'classes/com/vncode/app/integration/update/Installed168UpdateProbe.class');p.add_argument('--updater-before',type=pathlib.Path,default=AUDIT/'updater-before.json')
    p.add_argument('--review-helper',type=pathlib.Path,default=pathlib.Path('/tmp/vncode-169-independent-verifier/verify.py'))
    p.add_argument('--probe-zip',type=pathlib.Path,default=PROBE/'VN-code-1.6.8-Windows-x64.zip');p.add_argument('--probe-after-manifest',type=pathlib.Path,default=PROBE/'jar-manifest-after-after.json');p.add_argument('--probe-runner',type=pathlib.Path,default=PROBE/'run-probe.py');p.add_argument('--updater-before-integrity',type=pathlib.Path,default=AUDIT/'updater-before-integrity.json');p.add_argument('--updater-after-integrity',type=pathlib.Path,default=AUDIT/'updater-after-integrity.json')
    p.add_argument('--output',type=pathlib.Path,default=AUDIT/'public-preservation-verified.json');a=p.parse_args()
    if a.self_test:self_test();return
    if a.release_id is None or a.release_id<=0:p.error('--release-id must explicitly supply the positive actual published release ID')
    for name in ['after_releases','after_tags','latest','reviewed_dir','public_dir','review_receipt','updater_after']:require(getattr(a,name) is not None,'missing-input:'+name)
    require(a.output.resolve().parent==AUDIT.resolve(),'output-must-stay-in-audit-directory')
    require(not a.output.exists() and not a.output.is_symlink(),'output-already-exists')
    report=verify(a);report['helperSha256']=digest(__file__)['sha256']
    with a.output.open('x',encoding='utf-8') as stream:stream.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'result':'PASS','output':str(a.output),'sha256':digest(a.output)['sha256']}))
if __name__=='__main__':
    try:main()
    except (VerificationError,OSError,KeyError,TypeError,ValueError,json.JSONDecodeError) as e:print('PUBLIC_VERIFICATION_FAILED:',str(e),file=sys.stderr);sys.exit(1)
