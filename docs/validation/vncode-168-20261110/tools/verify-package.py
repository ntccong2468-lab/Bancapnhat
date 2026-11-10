#!/usr/bin/env python3
"""Offline 1.6.8 candidate verifier. Never download, publish, install or call seller APIs.

All PASS results require actual candidate assets, saved CI metadata, explicit reviewed
run/test-count pins and all gates. Source/JAR binding is build-provenance + structural
inspection, not an independent Java recompilation. EXE embedded MSI identity is checked
against the Windows extraction smoke; optionally independently compare an extracted MSI.
"""
import argparse, collections, datetime, hashlib, io, json, pathlib, re, stat, subprocess, sys
import xml.etree.ElementTree as ET
import zipfile

VERSION='1.6.8'
SOURCE='38906a2108501029d9609ed1d071ee987c8b2637'
PREVIOUS_SOURCE='d0231cd6913ce3a1764067bad0901b32c535de41'
REQUIRED_ANCESTORS=('73619d3f5d1da49bc2784b4e6ca4a0829d43e865', '39900722ab9cf055dfef8bf30c5fac0dcce3daea', '8fed8f717169aeb888ffa080674df35b66d42c74', PREVIOUS_SOURCE)
REPO='ntccong2468-lab/Bancapnhat'
UPGRADE_UUID='8CBBA0E2-6E73-4F56-9101-6BC0948D3C72'
BASE='1.6.7'
REQUIRED=[f'VN-code-{VERSION}-Windows-x64.exe',f'VN-code-{VERSION}-Windows-x64.msi',f'VN-code-{VERSION}-Windows-x64.sha256',f'VN-code-{VERSION}-Windows-x64.zip',f'VN-code-{VERSION}-source.zip',f'VN-code-{VERSION}-history.bundle',f'VN-code-{VERSION}-verification.zip','build-info.json','release-notes.md','native-smoke.json','upgrade-baseline-smoke.json']
ASSETS=REQUIRED+['asset-manifest.json','SHA256SUMS','gs1-smoke.json','side-by-side-smoke.json','previous-baseline.json']
SKIPS={('com.vncode.app.features.gs1.Gs1PrefixTemplateStoreTest','privatePermissionsAreAppliedWhereSupported'),('com.vncode.app.shared.WindowsSecretProtectorTest','neverFallsBackToPlaintextOffWindows')}
STRESS=(
 'threeHundredWireSubmissionsPreserveEveryReceiptAndIsolateRejectedAndUnknownResultsAfterRestart',
 'tenThousandApprovedVariantsSurvivePageBoundariesAppendAndReopenWithoutDispatchingNetworkWrites',
 'wbTenThousandGtinUseFourWholeCardWritesAndReadBackEveryMember',
 'ozonTenThousandGtinUseOneHundredDedicatedBarcodeWrites',
 'wbTenThousandDelayedReadbacksKeepDurableReportPendingUntilVerifiedWithoutReplay')
CERT_SUITES={'com.vncode.app.integration.znack.signature.CryptoProCertificateDiscoveryServiceTest':12,'com.vncode.app.integration.znack.signature.CryptoProCertificateRoutingTest':38,'com.vncode.app.ui.znack.ZnackCertificateDiscoveryUiTest':14}
class VerificationError(Exception): pass
def require(v,msg):
 if not v: raise VerificationError(msg)
def check_fields(data, fields, where):
 require(isinstance(data,dict),where+':not-object')
 for key,want in fields.items():
  have=data.get(key);require(type(have) is type(want) and have==want,where+':'+key)
def pairs(items):
 d={}
 for k,v in items: require(k not in d,'duplicate-json-key');d[k]=v
 return d
def parse(data):return json.loads(data.decode('utf-8-sig'),object_pairs_hook=pairs)
def digest(data):return hashlib.sha256(data).hexdigest()
def filehash(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for data in iter(lambda:f.read(1024*1024),b''):h.update(data)
 return h.hexdigest()
def git(repo,*args):
 result=subprocess.run(['git','-C',str(repo),*args],capture_output=True)
 require(result.returncode==0,'git-failed:'+args[0]);return result.stdout
def source_tree(repo):
 entries={}
 for row in git(repo,'ls-tree','-r','-z',SOURCE).split(b'\0'):
  if not row:continue
  meta,path=row.split(b'\t',1);mode,kind,oid=meta.split();require(kind==b'blob','unsupported-git-tree');entries[path.decode('utf-8')]=oid.decode('ascii')
 ids=list(dict.fromkeys(entries.values()));out=subprocess.run(['git','-C',str(repo),'cat-file','--batch'],input=('\n'.join(ids)+'\n').encode(),capture_output=True)
 require(out.returncode==0,'git-cat-file-failed');stream=io.BytesIO(out.stdout);blobs={}
 for oid in ids:
  head=stream.readline().split();require(len(head)==3 and head[0].decode()==oid and head[1]==b'blob','git-blob-header-invalid')
  data=stream.read(int(head[2]));require(len(data)==int(head[2]) and stream.read(1)==b'\n','git-blob-truncated');blobs[oid]=data
 require(not stream.read(),'git-batch-trailing-data');return {p:blobs[oid] for p,oid in entries.items()}
def equiv(actual,expected,name):
 if actual==expected:return 'exact'
 try: actual.decode('utf-8');expected.decode('utf-8')
 except UnicodeDecodeError: raise VerificationError('binary-mismatch:'+name)
 require(b'\0' not in actual and b'\0' not in expected and actual.replace(b'\r\n',b'\n')==expected.replace(b'\r\n',b'\n'),'text-mismatch:'+name)
 return 'crlf-lf-only'
def members(z):
 result={}
 for entry in z.infolist():
  name=entry.filename;p=pathlib.PurePosixPath(name)
  require(not name.startswith(('/', '\\')) and '\\' not in name and '..' not in p.parts and not re.match(r'^[A-Za-z]:',name),'unsafe-zip-member')
  if not entry.is_dir():
   require(((entry.external_attr >> 16) & 0o170000)!=0o120000,'symlink-zip-member:'+name)
   require(name not in result,'duplicate-zip-member:'+name);result[name]=entry
 return result
def checksums(data):
 out={}
 for line in data.decode('ascii').strip().splitlines():
  m=re.fullmatch(r'([a-f0-9]{64})  ([A-Za-z0-9._-]+)',line);require(m is not None,'invalid-checksum-line')
  value,name=m.groups();require(name not in out,'duplicate-checksum');out[name]=value
 return out
def properties(data):
 d={}
 for line in data.decode('utf-8-sig').splitlines():
  if not line.strip() or line.lstrip().startswith(('#','!')):continue
  require('=' in line,'invalid-properties');k,v=line.split('=',1);k=k.strip();require(k not in d,'duplicate-property');d[k]=v.strip()
 return d
def manifest(data):
 lines=[]
 for line in data.decode('utf-8').replace('\r\n','\n').splitlines():
  if line.startswith(' '):require(bool(lines),'bad-manifest-continuation');lines[-1]+=line[1:]
  elif line:lines.append(line)
 d={}
 for line in lines:
  require(': ' in line,'invalid-manifest');k,v=line.split(': ',1);require(k not in d,'duplicate-manifest-key');d[k]=v
 return d
def verify_source(repo,root,tree):
 with zipfile.ZipFile(root/f'VN-code-{VERSION}-source.zip') as z:
  found=members(z);prefix=f'VN-code-{VERSION}-source/'
  require(all(n.startswith(prefix) for n in found),'source-prefix-mismatch')
  mapped={n[len(prefix):]:m for n,m in found.items()};require(set(mapped)==set(tree),'source-file-set-mismatch')
  normalized=sum(equiv(z.read(mapped[n]),blob,n)!='exact' for n,blob in tree.items())
 return {'files':len(tree),'onlyExactOrUtf8LineEndings':True,'lineEndingDifferences':normalized}
def verify_portable(root,tree,repo):
 changed=git(repo,'diff','--name-only',PREVIOUS_SOURCE,SOURCE,'--','src/main/java','src/main/resources').decode().splitlines()
 require(changed,'runtime-delta-empty')
 with zipfile.ZipFile(root/f'VN-code-{VERSION}-Windows-x64.zip') as z:
  fs=members(z);jp=f'VN code/app/VNcode-{VERSION}.jar';cfg='VN code/app/VN code.cfg';require(jp in fs and cfg in fs,'production-jar-or-config-missing')
  content=z.read(fs[cfg]).decode('utf-8-sig').replace('\r\n','\n')
  require(re.search(r'(?m)^app\.mainclass=com\.vncode\.app\.Launcher$',content) is not None and f'VNcode-{VERSION}.jar' in content,'production-launcher-config-mismatch')
  require(not any(x in content.lower() for x in ['test-classes','license-test','znack-registration-test','-dvncode.data.profile=','vncode.appdata.dir=']),'test-profile-in-config')
  jb=z.read(fs[jp])
  with zipfile.ZipFile(io.BytesIO(jb)) as jar:
   jf=members(jar);classes=[n for n in jf if n.endswith('.class')];require(classes and all(n.startswith('com/vncode/app/') for n in classes),'unexpected-app-classes')
   test_stems={p[len('src/test/java/'):-5] for p in tree if p.startswith('src/test/java/') and p.endswith('.java')};main_stems={p[len('src/main/java/'):-5] for p in tree if p.startswith('src/main/java/') and p.endswith('.java')}
   for n in classes:
    require(n[:-6].split('$',1)[0] not in test_stems-main_stems and not n.startswith(('com/vncode/app/testsupport/','com/vncode/app/performance/')),'fixture-class-in-production:'+n)
    b=jar.read(jf[n]);require(b[:4]==b'\xca\xfe\xba\xbe' and int.from_bytes(b[6:8],'big')==69,'class-not-java25:'+n)
   for p in changed:
    if p.startswith('src/main/java/') and p.endswith('.java'):
     name=p[len('src/main/java/'):-5]+'.class';require(name in jf,'changed-class-missing:'+name)
    elif p.startswith('src/main/resources/'):
     name=p[len('src/main/resources/'):];require(name in jf,'changed-resource-missing:'+name);equiv(jar.read(jf[name]),tree[p],name)
   props=properties(jar.read(jf['app.properties']));check_fields(props,{'app.name':'VN code','app.version':VERSION,'app.update.url':f'https://github.com/{REPO}','app.update.public-key':'','app.update.publisher':''},'app-properties');require(not any('${' in v for v in props.values()),'unfiltered-property')
   mp=properties(jar.read(jf['META-INF/maven/com.vncode/VNcode/pom.properties']));check_fields(mp,{'groupId':'com.vncode','artifactId':'VNcode','version':VERSION},'maven-properties')
   mf=manifest(jar.read(jf['META-INF/MANIFEST.MF']));require(mf.get('Main-Class')=='com.vncode.app.Launcher','jar-main-class-mismatch')
   cp=mf.get('Class-Path','').split();require(cp and len(set(cp))==len(cp),'classpath-invalid')
   for d in cp:
    require(d.startswith('lib/') and '..' not in pathlib.PurePosixPath(d).parts and 'VN code/app/'+d in fs,'classpath-dependency-missing:'+d)
    require(not any(x in d.lower() for x in ('junit','mockito','mockwebserver','opentest4j','apiguardian')),'test-dependency-in-app')
 return {'path':jp,'sha256':digest(jb),'classesObserved':len(classes),'expectedClassCountHardcoded':False,'runtimeChangesChecked':len(changed),'classCheckScope':'presence and Java version; exact compiled-byte/source relationship relies on verified build provenance','dependencies':len(cp),'testProfilesAndFixturesAbsent':True}
def verify_junit(root,info,expected_tests,expected_suites):
 with zipfile.ZipFile(root/f'VN-code-{VERSION}-verification.zip') as z:
  fs=members(z);suites=[]
  for name,m in fs.items():
   if pathlib.PurePosixPath(name).name.startswith('TEST-') and name.endswith('.xml'):
    suite=ET.fromstring(z.read(m));require(suite.tag=='testsuite','bad-junit-root');suites.append(suite)
  names=[s.attrib.get('name') for s in suites];require(len(suites)==expected_suites and len(set(names))==len(names),'suite-count-or-duplicate-mismatch')
  totals={k:0 for k in ('tests','failures','errors','skipped')};skipped=[];stress=[]
  for s in suites:
   cases=s.findall('testcase');require(len(cases)==int(s.attrib['tests']),'junit-case-count-mismatch:'+s.attrib.get('name',''))
   for k in totals: totals[k]+=int(s.attrib.get(k,'0'))
   for tag,attr in [('failure','failures'),('error','errors'),('skipped','skipped')]:require(sum(c.find(tag) is not None for c in cases)==int(s.attrib.get(attr,'0')),'junit-status-count-mismatch')
   for c in cases:
    if c.find('skipped') is not None: skipped.append((s.attrib.get('name'),c.attrib.get('name')))
    name=c.attrib.get('name','')
    for want in STRESS:
     if name==want or name.startswith(want+'('):
      require(all(c.find(tag) is None for tag in ('failure','error','skipped')),'stress-case-not-passed:'+want);stress.append({'method':want,'seconds':float(c.attrib.get('time','0'))})
  require(totals=={'tests':expected_tests,'failures':0,'errors':0,'skipped':len(SKIPS)},'junit-expected-totals-mismatch')
  require(collections.Counter(skipped)==collections.Counter(SKIPS),'unexpected-skip-identities')
  check_fields(info['verification']['java'],{**totals,'passed':totals['tests']-totals['skipped']},'build-junit')
  require(collections.Counter(x['method'] for x in stress)==collections.Counter(STRESS),'required-stress-fixtures-missing')
  by={s.attrib['name']:s for s in suites}
  for name,count in CERT_SUITES.items():
   require(name in by and int(by[name].attrib['tests'])==count,'certificate-suite-missing-or-count');require(all(not any(c.find(t) is not None for t in ('failure','error','skipped')) for c in by[name].findall('testcase')),'certificate-suite-not-passed')
  for name in ('native-smoke.json','gs1-smoke.json','side-by-side-smoke.json','upgrade-baseline-smoke.json','previous-baseline.json'):
   inner=[n for n in fs if pathlib.PurePosixPath(n).name==name];require(len(inner)==1 and z.read(fs[inner[0]])==(root/name).read_bytes(),'smoke-inner-outer-mismatch:'+name)
 return {'totals':totals,'suites':len(suites),'skipIdentities':[list(x) for x in skipped],'stress':stress,'smokesInnerMatch':True,'scope':'Full JUnit evidence including synthetic 300/10000 tests; not real Catalog writes.'}
def verify_smokes(load,assets,tree,info,embedded):
 native=load('native-smoke.json');side=load('side-by-side-smoke.json');gs1=load('gs1-smoke.json');upgrade=load('upgrade-baseline-smoke.json');old=load('previous-baseline.json')
 check_fields(native,{'version':VERSION,'platform':'windows-x64','appName':'VN code','windowTitle':'VN code v'+VERSION,'nativeLauncher':'passed','schemaMigration':'3 to 6','historyPreserved':True,'rollbackSnapshotVerified':True,'liveMarketplaceMutations':False},'native')
 check_fields(side,{'version':VERSION,'platform':'windows-x64','appName':'VN code','result':'passed','windowTitle':'VN code v'+VERSION,'installerUpgradeUuid':UPGRADE_UUID,'independentRegistrations':True,'freshVncodeData':True,'wcodeFilesUnchanged':True,'uninstallPreservesWcode':True,'liveMarketplaceMutations':False},'coexistence')
 check_fields(gs1,{'appName':'VN code','version':VERSION,'result':'passed','dpapiRoundTrip':True,'protectedPasswordPersistence':True,'unverifiedInvoiceBlocked':True,'catalogKeyDpapiRoundTrip':True,'catalogKeyPurposeIsolated':True,'liveGS1Mutations':False},'gs1-dpapi')
 exe=f'VN-code-{VERSION}-Windows-x64.exe';msi=f'VN-code-{VERSION}-Windows-x64.msi'
 check_fields(upgrade,{'version':VERSION,'appName':'VN code','fromVersion':BASE,'result':'passed','installerUpgradeUuid':UPGRADE_UUID,'singleRegistration':True,'dataPreserved':True,'liveMarketplaceMutations':False,'passiveExeHandoffVerified':True,'passiveExeInstallerSha256':assets[exe]['sha256'],'passiveExeEmbeddedMsiSha256':assets[msi]['sha256']},'upgrade')
 require(re.fullmatch(r'[a-f0-9]{64}',upgrade.get('fingerprint','')) is not None,'upgrade-data-fingerprint-invalid')
 check_fields(old,{'version':BASE,'tag':'v'+BASE,'skipUpgrade':False},'previous-baseline')
 previous=parse(tree['docs/validation/2026-11-09-vncode-167-package.json'])['assets'][f'VN-code-{BASE}-Windows-x64.exe'];check_fields(old,{'bytes':previous['bytes'],'sha256':previous['sha256'],'url':f'https://github.com/{REPO}/releases/download/v{BASE}/VN-code-{BASE}-Windows-x64.exe'},'previous-baseline-exe')
 check_fields(upgrade,{'baselineTag':'v'+BASE,'baselineInstallerSha256':previous['sha256'],'baselineInstallerBytes':previous['bytes']},'upgrade-previous-proof')
 check_fields(info['installer'],{'name':exe,'bytes':assets[exe]['bytes'],'sha256':assets[exe]['sha256']},'build-exe')
 if embedded:require(filehash(embedded)==assets[msi]['sha256'],'independent-extracted-msi-mismatch')
 return {'nativeMigration':'passed','gs1Dpapi':'passed-by-Windows-smoke','freshInstallCoexistenceUninstall':'passed','upgradeFrom':BASE,'passiveExeAndEmbeddedMsi':'bound-to-Windows-extraction-smoke-and-artifact-hashes','independentEmbeddedMsi':bool(embedded)}
def verify_actions_artifact(path,artifact,assets,run_id,artifact_id):
 check_fields(artifact,{'id':artifact_id,'name':f'VN-code-{VERSION}-complete-baseline','expired':False,'size_in_bytes':path.stat().st_size,'digest':'sha256:'+filehash(path)},'actions-artifact')
 check_fields(artifact['workflow_run'],{'id':run_id,'head_sha':SOURCE},'artifact-workflow-run')
 with zipfile.ZipFile(path) as archive:
  zipped=members(archive);byname={}
  for name,member in zipped.items():
   base=pathlib.PurePosixPath(name).name;require(base not in byname,'duplicate-action-artifact-name:'+base);byname[base]=member
  require(set(byname)==set(ASSETS),'action-artifact-16-file-set-mismatch')
  for name,member in byname.items():
   h=hashlib.sha256();size=0
   with archive.open(member) as stream:
    for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk);size+=len(chunk)
   require(size==assets[name]['bytes'] and h.hexdigest()==assets[name]['sha256'],'action-artifact-byte-mismatch:'+name)

def verify(a):
 root=a.artifacts.resolve();repo=a.repo.resolve();require(root.is_dir(),'artifact-directory-missing');require({p.name for p in root.iterdir()}==set(ASSETS),'exact-16-assets-required')
 assets={};stats={}
 for name in ASSETS:
  p=root/name;s=p.lstat();require(stat.S_ISREG(s.st_mode) and not p.is_symlink() and s.st_size>0,'asset-not-regular:'+name);assets[name]={'bytes':s.st_size,'sha256':filehash(p)};stats[name]=(s.st_size,s.st_mtime_ns)
 load=lambda name:parse((root/name).read_bytes())
 man=load('asset-manifest.json');check_fields(man,{'schema':1,'version':VERSION,'sourceCommit':SOURCE},'asset-manifest');require(set(man['files'])==set(REQUIRED),'manifest-set-mismatch')
 for n in REQUIRED:require(man['files'][n]==assets[n],'manifest-digest-size:'+n)
 sums=checksums((root/'SHA256SUMS').read_bytes());require(set(sums)==set(REQUIRED+['asset-manifest.json']),'sha256sums-set-mismatch');require(all(assets[n]['sha256']==h for n,h in sums.items()),'sha256sums-digest-mismatch')
 exe=f'VN-code-{VERSION}-Windows-x64.exe';require(checksums((root/f'VN-code-{VERSION}-Windows-x64.sha256').read_bytes())=={exe:assets[exe]['sha256']},'installer-checksum-mismatch')
 run=parse(a.run_metadata.read_bytes());check_fields(run,{'id':a.run_id,'head_sha':SOURCE,'status':'completed','conclusion':'success','workflow_id':a.workflow_id},'ci-run');require(run['repository']['full_name']==run['head_repository']['full_name']==REPO,'ci-repository-mismatch')
 info=load('build-info.json');check_fields(info,{'version':VERSION,'sourceCommit':SOURCE,'runId':a.run_id,'runAttempt':run['run_attempt'],'workflow':'publish-vncode-baseline.yml'},'build-info');check_fields(info['verification'],{'fullJavaSuiteRun':True,'nativeSmokeRun':True,'baselineUpgradeRun':True,'liveCatalogWriteVerified':False,'liveMarketplaceWriteVerified':False,'paidKizPurchaseVerified':False},'build-flags')
 artifact=parse(a.artifact_metadata.read_bytes());verify_actions_artifact(a.artifact_zip,artifact,assets,a.run_id,a.artifact_id)
 for ancestor in REQUIRED_ANCESTORS:require(subprocess.run(['git','-C',str(repo),'merge-base','--is-ancestor',ancestor,SOURCE],capture_output=True).returncode==0,'required-ancestor-missing:'+ancestor)
 tree=source_tree(repo);source=verify_source(repo,root,tree);equiv((root/'release-notes.md').read_bytes(),tree[f'docs/releases/VN-code-{VERSION}.md'],'release-notes')
 portable=verify_portable(root,tree,repo);junit=verify_junit(root,info,a.expected_tests,a.expected_suites);smokes=verify_smokes(load,assets,tree,info,a.extracted_msi)
 with (root/exe).open('rb') as f:
  h=f.read(64);require(h[:2]==b'MZ','exe-header');f.seek(int.from_bytes(h[60:64],'little'));pe=f.read(6);require(pe[:4]==b'PE\0\0' and int.from_bytes(pe[4:6],'little')==0x8664,'exe-not-x64')
 with (root/f'VN-code-{VERSION}-Windows-x64.msi').open('rb') as f:require(f.read(8)==bytes.fromhex('d0cf11e0a1b11ae1'),'msi-container-invalid')
 bundle=(root/f'VN-code-{VERSION}-history.bundle').resolve();git(repo,'bundle','verify',str(bundle));heads=git(repo,'bundle','list-heads',str(bundle)).decode().splitlines();require(any(line.split()[0]==SOURCE for line in heads),'source-not-bundle-head')
 for name in ASSETS:
  p=root/name;s=p.lstat();require((s.st_size,s.st_mtime_ns)==stats[name] and filehash(p)==assets[name]['sha256'],'asset-changed-during-verification:'+name)
 return {'kind':'VNCODE_168_OFFLINE_CANDIDATE_VERIFICATION','result':'PASS','version':VERSION,'sourceCommit':SOURCE,'ciRun':a.run_id,'workflowId':a.workflow_id,'actionArtifactId':artifact['id'],'actionArtifactSha256':filehash(a.artifact_zip),'time':datetime.datetime.now(datetime.timezone.utc).isoformat(),'source':source,'portable':portable,'junit':junit,'smokes':smokes,'assets':assets,'helperSha256':filehash(pathlib.Path(__file__)),'scope':'Read-only downloaded bytes and recorded CI/smoke metadata; no installer execution, seller API, release mutation, or network.','limitations':['Class/source linkage relies on exact source archive and CI provenance; no independent recompilation.','Embedded MSI is checked via Windows smoke attestation against final hashes unless an independently extracted MSI is supplied.','No seller machine, actual KIZ purchase or Catalog write verified.']}
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--repo',type=pathlib.Path,required=True);p.add_argument('--artifacts',type=pathlib.Path,required=True);p.add_argument('--run-metadata',type=pathlib.Path,required=True);p.add_argument('--artifact-metadata',type=pathlib.Path,required=True);p.add_argument('--artifact-zip',type=pathlib.Path,required=True);p.add_argument('--run-id',type=int,required=True);p.add_argument('--artifact-id',type=int,required=True);p.add_argument('--workflow-id',type=int,required=True);p.add_argument('--expected-tests',type=int,required=True);p.add_argument('--expected-suites',type=int,required=True);p.add_argument('--extracted-msi',type=pathlib.Path);p.add_argument('--receipt',type=pathlib.Path,required=True)
 a=p.parse_args();require(a.run_id>0 and a.artifact_id>0 and a.workflow_id>0 and a.expected_tests>=2800 and a.expected_suites>0,'invalid-explicit-pins');require(not a.receipt.resolve().is_relative_to(a.repo.resolve()),'receipt-must-be-outside-repo');require(not a.receipt.resolve().is_relative_to(a.artifacts.resolve()),'receipt-must-be-outside-artifact-directory')
 result=verify(a);a.receipt.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print('PASS',a.receipt)
if __name__=='__main__':
 try:main()
 except (VerificationError,KeyError,ValueError,OSError,zipfile.BadZipFile,ET.ParseError,json.JSONDecodeError) as e:print('VERIFICATION FAILED:',type(e).__name__,str(e),file=sys.stderr);sys.exit(1)
