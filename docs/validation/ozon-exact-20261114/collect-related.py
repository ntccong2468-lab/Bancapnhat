import datetime, hashlib, json, pathlib, subprocess, xml.etree.ElementTree as ET, zipfile

ROOT=pathlib.Path('/workspace/vncode-ozon-exact-gtin-20261114')
AUDIT=pathlib.Path('/tmp/vncode-ozon-exact-20261114')
CODE='a86048ec57521bc4d32b0a27c86d2eb1ee70fcb3'
def git(*args): return subprocess.check_output(['git',*args],cwd=ROOT)
def sha(data): return hashlib.sha256(data).hexdigest()
exit_record=json.loads((AUDIT/'related-exit.json').read_text())
log=(AUDIT/'related-clean-verify.log').read_text()
assert exit_record['exitCode']==0 and '[INFO] BUILD SUCCESS' in log
assert not git('diff',CODE,'--','src','pom.xml','.mvn','mvnw','mvnw.cmd')
records=[]
for path in sorted((ROOT/'target/surefire-reports').glob('TEST-*.xml')):
    suite=ET.parse(path).getroot()
    records.append({'name':suite.get('name'),'tests':int(suite.get('tests',0)),
                    'failures':int(suite.get('failures',0)),'errors':int(suite.get('errors',0)),
                    'skipped':int(suite.get('skipped',0)),'seconds':float(suite.get('time',0)),
                    'xmlSha256':sha(path.read_bytes())})
totals={key:sum(row[key] for row in records) for key in ('tests','failures','errors','skipped')}
assert totals['tests']>0 and totals['failures']==totals['errors']==0
skips=[]
for path in (ROOT/'target/surefire-reports').glob('TEST-*.xml'):
    suite=ET.parse(path).getroot()
    for case in suite.findall('testcase'):
        skipped=case.find('skipped')
        if skipped is not None: skips.append({'suite':suite.get('name'),'case':case.get('name'),'reason':skipped.get('message','')})
assert len(skips)==totals['skipped']
inputs={}
for row in git('ls-tree','-r',CODE).decode().splitlines():
    meta,path=row.split('\t',1)
    if path.startswith(('src/main/','src/test/','.mvn/')) or path in ('pom.xml','mvnw','mvnw.cmd'):
        current=(ROOT/path).read_bytes(); blob=git('show',CODE+':'+path)
        assert current==blob,path
        inputs[path]=sha(current)
jar=ROOT/'target/VNcode-1.6.10.jar'
runtime={p.relative_to(ROOT/'target/classes').as_posix():p.read_bytes()
         for p in (ROOT/'target/classes').rglob('*') if p.is_file()}
test_only={p.relative_to(ROOT/'target/test-classes').as_posix()
           for p in (ROOT/'target/test-classes').rglob('*.class')}-runtime.keys()
with zipfile.ZipFile(jar) as archive:
    assert archive.testzip() is None
    entries=set(archive.namelist()); missing=[]
    for path,data in runtime.items():
        if path=='META-INF/MANIFEST.MF': continue
        if path not in entries or archive.read(path)!=data: missing.append(path)
    assert not missing,missing
    assert not entries.intersection(test_only)
    manifest=archive.read('META-INF/MANIFEST.MF').decode()
    assert 'Main-Class: com.vncode.app.Launcher' in manifest and 'Build-Jdk-Spec: 25' in manifest,manifest
    packaged_class_count=sum(p.endswith('.class') for p in entries)
old='75691c68fa14f728856b0418fb6008083787ef7c'
baseline='4ef0f1e2b8ef7b28190de3034b0c67ebf615be0b'
previous=git('diff','--name-only',baseline,old,'--','src/main').decode().splitlines()
assert len(previous)==10
for path in previous: assert git('show',old+':'+path)==git('show',CODE+':'+path),path
selection=json.loads((AUDIT/'related-selection.json').read_text())
result={'status':'PASS','scope':selection['scope'],'sourceCommit':CODE,
        'collectedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'actualExitCode':exit_record['exitCode'],'buildSuccessObserved':True,
        'tests':totals,'suites':len(records),'skipIdentities':skips,
        'selection':selection,'suiteResults':records,
        'inputsVerified':len(inputs),'inputSha256':inputs,
        'jar':{'file':jar.name,'bytes':jar.stat().st_size,'sha256':sha(jar.read_bytes()),
               'packagedClasses':packaged_class_count,'compiledResourcesVerified':len(runtime),
               'testClassesPackaged':False,'generatedManifestCheckedSeparately':True},
        'priorKizThemeRuntimeFilesRetainedByteExact':previous,
        'notFullAppRun':True,'noLiveSellerAcceptance':True,
        'historicalFullC2NotReusedToCertifyNewSource':True,
        'logSha256':sha((AUDIT/'related-clean-verify.log').read_bytes())}
(AUDIT/'related-result.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:result[k] for k in ('status','sourceCommit','tests','suites','inputsVerified','jar')}))
