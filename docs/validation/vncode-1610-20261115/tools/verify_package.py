#!/usr/bin/env python3
"""Independent, offline 1.6.10 verifier. Extends byte-preserved published-169 guard.
No builds, network, installer execution, business or license calls.
"""
import argparse,collections,datetime,hashlib,importlib.util,io,json,pathlib,re,subprocess,sys,types,xml.etree.ElementTree as ET,zipfile
HERE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('baseline169_verifier',HERE/'baseline169_verifier.py')
v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
require=v.require; parse=v.parse; git=v.git; digest=v.digest; filehash=v.filehash; fields=v.check_fields
SOURCE='b74b5edfd46636e8779c94f467eef01c03c50a14';BASE_SOURCE='4ef0f1e2b8ef7b28190de3034b0c67ebf615be0b';CONSOLIDATED='a86048ec57521bc4d32b0a27c86d2eb1ee70fcb3'
BUILD_RUN=43641520595;WORKFLOW=392732536;ARTIFACT=14137224020;ARTIFACT_SHA='253381c64fb6408d7d1ad1eab491df23a289ce772ca19007138ab9e7768e2ba7';VERSION='1.6.10'
# Actual values observed independently in the downloaded Windows bundle, not forecasts.
v.VERSION=VERSION;v.SOURCE=SOURCE;v.PREVIOUS_SOURCE=BASE_SOURCE;v.CONSOLIDATED_SOURCE=CONSOLIDATED;v.BUILD_RUN=BUILD_RUN;v.EXPECTED_CLASS_COUNT=2026;v.BASE='1.6.9';v.BASE_EXE_BYTES=149432832;v.BASE_EXE_SHA='b516a2bfb2182a9f0e5f82821cb7f299798258e1764eb0897a410be1985060d1'
v.REQUIRED_ANCESTORS=(BASE_SOURCE,CONSOLIDATED,'43b82cca683b29e783a33df995afd675a76aa0dd','50edad4ff2fa929b45f18ee8d2362a30ea0df23f','86b4a2a751ee7ca40d8253d4650517313d9fba90','f3e98725ee2892abf225cb66978ed9302fd49489')
v.REQUIRED=[f'VN-code-{VERSION}-Windows-x64.exe',f'VN-code-{VERSION}-Windows-x64.msi',f'VN-code-{VERSION}-Windows-x64.sha256',f'VN-code-{VERSION}-Windows-x64.zip',f'VN-code-{VERSION}-source.zip',f'VN-code-{VERSION}-history.bundle',f'VN-code-{VERSION}-verification.zip','build-info.json','release-notes.md','native-smoke.json','upgrade-baseline-smoke.json']
v.ASSETS=v.REQUIRED+['asset-manifest.json','SHA256SUMS','gs1-smoke.json','side-by-side-smoke.json','previous-baseline.json']
BASE_HELPER_SHA='14a790dc658ffdb3f4cdf30259e2b8ef15898b2abd89d7d107f3f6054a1c66b9'
REQUIRED_SUITES='''features.gtinsync.GtinCatalogIndexTest
features.gtinsync.OzonExactCatalogIndexTest
features.gtinsync.OzonExactProofBatchTest
features.gtinsync.OzonExactVariantIdentityTest
features.kizmapping.OzonCatalogMappingServiceTest
features.sync.RegistrationCardIndexTest
features.sync.ZnackAutoSyncServiceTest
integration.ozon.OzonExactGtinAdapterTest
integration.znack.ZnackErrorMessagesTest
integration.znack.ZnackPurchaseDiagnosticsTest
integration.znack.ZnackSigningRecoveryTest
integration.znack.ZnackSigningSessionTest
integration.znack.registration.OzonExactVariantProofTest
integration.znack.registration.RegistrationExistingCardsTest
shared.ThemeDialogBackingTest
shared.ThemeServiceTest
shared.WorkspaceAllSurfaceGlassTest
shared.WorkspaceBackdropMaterialTest
shared.WorkspaceDialogChromeTest
shared.WorkspaceGlobalMotionContractTest
shared.WorkspaceInteractionMotionTest
shared.WorkspaceMaterialRenderingTest
shared.WorkspaceRoundedControlClipTest
shared.WorkspaceRouteContinuityTest
ui.ReferenceThemeAccessibilityTest
ui.kizmapping.KizSigningResumePresentationTest
ui.products.ProductColumnHierarchyTest
ui.workspace.HomeControllerPageTransitionTest
ui.workspace.NativeGlassEvidenceTest'''.splitlines()
REQUIRED_CASES={
'shared.WorkspaceAllSurfaceGlassTest':['delayedNativeSkinKeepsLogicalWatchAndTrueRemovalReleasesMaterial','reducedNavigationMaterializesNewViewportWithoutThemeChange'],
'ui.products.ProductBulkDialogTest':['applyingClosesPickerAndShowsRealProgressBelowProductList'],
'ui.workspace.WorkspaceListAreaTest':['listsUseTheWbFbsWorkAreaAndGrowWithTheWindow'],
'shared.WorkspaceBackdropMaterialTest':['callerBackgroundRemainsExcludedWhenCssPrecedesThemeRescan'],
'shared.WorkspaceMaterialRenderingTest':['focusedShopSelectorKeepsNativeContrastDuringAndAfterMaterialRefresh','realFxmlTablesInsideAWorkingSheetHaveNoSeparateRectangularPaintedFrame'],
'ui.workspace.NativeGlassEvidenceTest':['productionRoutesKeepNativeControlsAndLargeListsAcrossApprovedDesktopSizes','shopManagementRemainsAdjacentAcrossNativeThemeAndResizeHistory']}

def tree_at(repo,commit,prefix='src/main/'):
 d={}
 for row in git(repo,'ls-tree','-r','-z',commit,'--',prefix).split(b'\0'):
  if row:
   meta,p=row.split(b'\t',1);mode,kind,oid=meta.split();require(kind==b'blob','nonblob-runtime');d[p.decode()]=oid.decode()
 return d

def inclusion(repo,tree,path):
 data=parse(path.read_bytes());fields(data,{'baseline':BASE_SOURCE,'sourceCommit':SOURCE,'baselineRuntimeFiles':800,'runtimeFiles':805,'unchanged':756,'previousSource':CONSOLIDATED,'previousRuntimeFiles':801,'pomUnchangedFromPrevious':True},'runtime-preservation')
 current=tree_at(repo,SOURCE);old=tree_at(repo,BASE_SOURCE);business=tree_at(repo,CONSOLIDATED)
 require(len(current)==805 and sum(p.endswith('.java') for p in current)==763,'actual-runtime-count');require(data['runtimeBlobs']==current,'runtime-manifest-blob-mismatch')
 def delta(before):return {'unchanged':sum(current.get(p)==o for p,o in before.items()),'modified':sorted(p for p in before.keys()&current.keys() if before[p]!=current[p]),'added':sorted(current.keys()-before.keys()),'removed':sorted(before.keys()-current.keys())}
 d=delta(old);require(d=={k:data[k] for k in d},'169-runtime-delta');require(not d['removed'] and len(d['modified'])==44 and len(d['added'])==5,'169-preservation-count')
 bd=delta(business);require(bd==data['previousSourceDelta'] and bd['unchanged']==773 and len(bd['modified'])==28 and len(bd['added'])==4 and not bd['removed'],'business-checkpoint-preservation')
 outside={p:o for p,o in business.items() if p.startswith('src/main/java/') and '/shared/' not in p and '/ui/' not in p}
 require(len(outside)==603 and all(current.get(p)==oid for p,oid in outside.items()),'business-java-outside-ui-shared-changed')
 require(git(repo,'show',SOURCE+':pom.xml')==git(repo,'show',CONSOLIDATED+':pom.xml'),'pom-business-checkpoint-changed')
 require(not any('PrototypeCredential' in p for p in current),'credential-prototype-in-runtime')
 return {'files':805,'java':763,'baseline169':d,'businessCheckpoint':bd,'outsideUiSharedJavaByteExact':603,'manifestSha256':filehash(path),'runtimeGitBlobMapVerified':True,'pomBusinessCheckpointByteExact':True}
v.verify_source_inclusion=inclusion

def smokes(load,assets,tree,info,embedded):
 native=load('native-smoke.json');side=load('side-by-side-smoke.json');gs1=load('gs1-smoke.json');upgrade=load('upgrade-baseline-smoke.json');old=load('previous-baseline.json')
 fields(native,{'version':VERSION,'platform':'windows-x64','appName':'VN code','windowTitle':'VN code v'+VERSION,'nativeLauncher':'passed','schemaMigration':'3 to 6','historyPreserved':True,'rollbackSnapshotVerified':True,'liveMarketplaceMutations':False},'native')
 fields(side,{'version':VERSION,'platform':'windows-x64','appName':'VN code','result':'passed','windowTitle':'VN code v'+VERSION,'installerUpgradeUuid':v.UPGRADE_UUID,'independentRegistrations':True,'freshVncodeData':True,'wcodeFilesUnchanged':True,'uninstallPreservesWcode':True,'liveMarketplaceMutations':False},'coexistence')
 fields(gs1,{'appName':'VN code','version':VERSION,'result':'passed','dpapiRoundTrip':True,'protectedPasswordPersistence':True,'unverifiedInvoiceBlocked':True,'catalogKeyDpapiRoundTrip':True,'catalogKeyPurposeIsolated':True,'liveGS1Mutations':False},'gs1-dpapi')
 exe=f'VN-code-{VERSION}-Windows-x64.exe';msi=f'VN-code-{VERSION}-Windows-x64.msi'
 fields(upgrade,{'version':VERSION,'appName':'VN code','fromVersion':'1.6.9','result':'passed','installerUpgradeUuid':v.UPGRADE_UUID,'singleRegistration':True,'singleShortcutPerLocation':True,'dataPreserved':True,'liveMarketplaceMutations':False,'passiveExeHandoffVerified':True,'passiveExeInstallerSha256':assets[exe]['sha256'],'passiveExeEmbeddedMsiSha256':assets[msi]['sha256'],'baselineTag':'v1.6.9','baselineInstallerBytes':v.BASE_EXE_BYTES,'baselineInstallerSha256':v.BASE_EXE_SHA},'upgrade')
 require(re.fullmatch(r'[a-f0-9]{64}',upgrade.get('fingerprint','')) is not None,'upgrade-data-fingerprint-invalid')
 fields(old,{'version':'1.6.9','tag':'v1.6.9','skipUpgrade':False,'bytes':v.BASE_EXE_BYTES,'sha256':v.BASE_EXE_SHA,'url':f'https://github.com/{v.REPO}/releases/download/v1.6.9/VN-code-1.6.9-Windows-x64.exe'},'previous-baseline')
 baseline=parse(tree['docs/validation/vncode-169-20261112/package-review.json'])['assets']['VN-code-1.6.9-Windows-x64.exe'];fields(baseline,{'bytes':v.BASE_EXE_BYTES,'sha256':v.BASE_EXE_SHA},'published-169-source-receipt')
 fields(info['installer'],{'name':exe,'bytes':assets[exe]['bytes'],'sha256':assets[exe]['sha256']},'build-exe')
 require(not embedded,'optional-extracted-msi-not-used')
 return {'nativeMigration':'passed','gs1Dpapi':'passed-by-Windows-smoke','freshInstallCoexistenceUninstall':'passed','upgradeFrom':'1.6.9','baselineExeBytes':v.BASE_EXE_BYTES,'baselineExeSha256':v.BASE_EXE_SHA,'passiveExeAndEmbeddedMsi':'bound-to-Windows-extraction-smoke-and-independent-PE-resource-read','liveBusinessMutations':False}
v.verify_smokes=smokes

def extended_junit(root,repo):
 suites={};mapping={}
 with zipfile.ZipFile(root/f'VN-code-{VERSION}-verification.zip') as z:
  members=v.members(z)
  for p in members:
   if pathlib.PurePosixPath(p).name.startswith('TEST-') and p.endswith('.xml'):
    s=ET.fromstring(z.read(p));name=s.get('name');require(name not in suites,'duplicate-suite');suites[name]=s
  for suffix in REQUIRED_SUITES:
   name='com.vncode.app.'+suffix;require(name in suites,'new-suite-missing:'+name);s=suites[name];cases=s.findall('testcase');require(cases,'empty-new-suite:'+name)
   require(all(not any(c.find(t) is not None for t in ('failure','error','skipped')) for c in cases),'new-suite-not-passed:'+name)
   p='src/test/java/'+name.replace('.','/')+'.java';require(git(repo,'cat-file','-e',SOURCE+':'+p)==b'','new-suite-source-absent');mapping[name]=len(cases)
  case_results=[]
  for suffix,methods in REQUIRED_CASES.items():
   # Require the exact recorded class/package and unambiguous method identity.
   exact='com.vncode.app.'+suffix
   choices=[n for n in suites if n==exact]
   require(len(choices)==1,'required-regression-suite-missing-or-ambiguous:'+suffix);name=choices[0]
   for method in methods:
    hits=[c for c in suites[name].findall('testcase') if c.get('name')==method or c.get('name','').startswith(method+'(')]
    require(len(hits)==1 and all(hits[0].find(t) is None for t in ('failure','error','skipped')),'required-regression-not-passed:'+method);case_results.append({'suite':name,'method':method,'seconds':hits[0].get('time')})
 legacy={**v.CERT_SUITES,**v.NEW_SUITES,**v.EXPANDED_SUITES}
 require(len(legacy)==18,'legacy-suite-contract-count')
 for name,count in legacy.items():
  path='src/test/java/'+name.replace('.','/')+'.java';require(git(repo,'show',BASE_SOURCE+':'+path)==git(repo,'show',SOURCE+':'+path),'legacy-suite-source-changed:'+name)
 return {'requiredNewSuites':mapping,'requiredRegressionCases':case_results,'legacy18SourcesByteExactTo169':True}

def whole_jar(root,repo,tree):
 with zipfile.ZipFile(root/f'VN-code-{VERSION}-Windows-x64.zip') as z:
  with zipfile.ZipFile(io.BytesIO(z.read(f'VN code/app/VNcode-{VERSION}.jar'))) as j:
   files=v.members(j);count=0;resources=0
   for p,blob in tree.items():
    if p.startswith('src/main/java/') and p.endswith('.java'):
     n=p[len('src/main/java/'):-5]+'.class';require(n in files,'main-source-class-missing:'+n);count+=1
    elif p.startswith('src/main/resources/'):
     n=p[len('src/main/resources/'):];require(n in files,'runtime-resource-missing:'+n)
     expected=blob
     if n=='app.properties':expected=expected.replace(b'${app.version}',VERSION.encode()).replace(b'${app.update.public-key}',b'').replace(b'${app.update.publisher}',b'')
     if n=='META-INF/MANIFEST.MF':
      merged=v.manifest(j.read(n));source_manifest=v.manifest(expected);require(all(merged.get(k)==value for k,value in source_manifest.items()),'source-manifest-fields-overwritten');require(merged.get('Created-By')=='Maven JAR Plugin 3.4.1' and merged.get('Build-Jdk-Spec')=='25','generated-manifest-provenance')
     else:v.equiv(j.read(n),expected,n)
     resources+=1
   require(count==763 and resources==42,'all-main-resource-count')
   v.equiv(j.read('META-INF/maven/com.vncode/VNcode/pom.xml'),tree['pom.xml'],'embedded-pom')
 return {'allMainJavaTopLevelClassesPresent':count,'allRuntimeResourcesPresentAndCompared':resources,'resourceComparison':'40 exact/UTF8-CRLF, app.properties 3POM-default properties filtered, Maven MANIFEST source fields preserved with generated build/classpath fields' ,'embeddedPomExactOrCrLf':True}

def actions_extended(build,repo):
 before=parse((build/'artifact-before.json').read_bytes());after=parse((build/'artifact-after.json').read_bytes());require(before==after,'artifact-metadata-changed-before-after')
 fields(before,{'id':ARTIFACT,'size_in_bytes':583461462,'digest':'sha256:'+ARTIFACT_SHA},'artifact-independent-pins')
 raw=build/'actions-artifact.zip';require(filehash(raw)==ARTIFACT_SHA,'raw-artifact-actual-sha');
 with zipfile.ZipFile(raw) as z:require(z.testzip() is None,'raw-artifact-crc-failure')
 jobs=parse((build/'jobs.json').read_bytes());require(jobs['total_count']==1 and len(jobs['jobs'])==1,'job-count');job=jobs['jobs'][0]
 fields(job,{'id':132589921294,'run_id':BUILD_RUN,'run_attempt':1,'head_sha':SOURCE,'status':'completed','conclusion':'success','name':'baseline'},'actual-windows-job');require('windows-2022' in job['labels'],'not-windows-runner')
 expected={6:'Configure transient worker animation for JavaFX verification',7:'Verify release tools',8:'Verify fresh JVM motion preferences and original OS accessibility authority',9:'Verify Windows UI, purchase circulation, product documents and Catalog fixtures',10:'Verify whole Java application and package',11:'Build final EXE from verified Java',12:'Restore pinned baseline after installer packaging',13:'Verify native migration and startup',14:'Verify actual Windows data protection',15:'Verify fresh install, coexistence and uninstall',16:'Verify passive upgrade from pinned current Latest',18:'Preserve final MSI before building portable runtime',19:'Build complete portable runtime from same verified Java',20:'Restore the original worker desktop preferences',21:'Package all source, Git history, runtime and verification',22:'Preserve installer and all rebuild inputs'}
 by={s['number']:s for s in job['steps']}
 for number,name in expected.items():fields(by[number],{'name':name,'status':'completed','conclusion':'success'},'job-step-'+str(number))
 for number in (17,23):fields(by[number],{'status':'completed','conclusion':'skipped'},'expected-unused-step-'+str(number))
 workflow=git(repo,'show',SOURCE+':.github/workflows/publish-vncode-baseline.yml')
 whole=workflow.split(b'      - name: Verify whole Java application and package\n',1);require(len(whole)==2,'full-suite-step-missing');block=whole[1].split(b'      - name:',1)[0];require(b'.\\mvnw.cmd -B clean verify' in block and b'-Dtest=' not in block and b'skipTests' not in block and b'maven.test.skip' not in block,'full-suite-workflow-command')
 return {'artifactBeforeAfterExact':True,'rawArtifactCRC':'PASS','artifactId':ARTIFACT,'artifactDigest':ARTIFACT_SHA,'runAttempt':1,'actualWindowsJobId':job['id'],'requiredStepsPassed':list(expected.values())}

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--repo',type=pathlib.Path,required=True);p.add_argument('--build',type=pathlib.Path,required=True);p.add_argument('--receipt',type=pathlib.Path,required=True);p.add_argument('--asset-map',type=pathlib.Path,required=True);a=p.parse_args()
 repo=a.repo.resolve();build=a.build.resolve();out=a.receipt.resolve();assetmap=a.asset_map.resolve();require(out.parent==HERE and assetmap.parent==HERE,'outputs-outside-owned-dir')
 baseline_path=HERE/'baseline169_verifier.py';require(filehash(baseline_path)==BASE_HELPER_SHA,'baseline169-published-helper-sha');require(baseline_path.read_bytes()==git(repo,'show','448c7bec4bcec98e6c9ea13a51df1e5a1689053e:docs/validation/vncode-169-20261112/tools/verify-package.py'),'baseline169-guard-not-byte-preserved')
 args=types.SimpleNamespace(repo=repo,artifacts=build/'assets',run_metadata=build/'run-before.json',artifact_metadata=build/'artifact-before.json',artifact_zip=build/'actions-artifact.zip',source_inclusion=repo/'docs/validation/ios-motion-20261114/source-preservation.json',run_id=BUILD_RUN,artifact_id=ARTIFACT,workflow_id=WORKFLOW,expected_tests=4700,expected_suites=585,extracted_msi=None)
 result=v.verify(args)
 result['kind']='VNCODE_1610_OFFLINE_CANDIDATE_VERIFICATION';result['portable']['classCheckScope']='Presence of all763 main Java top-level classes, exact2026 Java25 class count, all42resources and embeddedPOM; compiled source linkage relies on verified CI provenance, not independent recompilation.'
 result['actionsExtended']=actions_extended(build,repo);tree=v.source_tree(repo)
 with zipfile.ZipFile(build/'assets'/f'VN-code-{VERSION}-source.zip') as z:require(z.comment==SOURCE.encode(),'source-git-comment')
 result['source']['gitArchiveCommentVerified']=True
 bundle=build/'assets'/f'VN-code-{VERSION}-history.bundle'
 with bundle.open('rb') as stream:
  header=[]
  for _ in range(10000):
   line=stream.readline();require(line,'bundle-header-truncated');header.append(line)
   if line==b'\n':break
  else:raise v.VerificationError('bundle-header-unbounded')
 require(header[0].startswith(b'# v') and not any(line.startswith(b'-') for line in header),'bundle-incomplete-history-prerequisites')
 result['historyBundle']={'completeWithoutPrerequisites':True,'sourceIsBundledHead':True,'sha256':filehash(bundle)}
 result['junitExtended']=extended_junit(build/'assets',repo);result['wholeJar']=whole_jar(build/'assets',repo,tree)
 sys.path.insert(0,str(HERE/'motion'));import verify_motion
 result['windowsMotionAndSyntheticProfiles']=verify_motion.verify_motion(build/'assets'/f'VN-code-{VERSION}-verification.zip')
 result['verifierBaseline169Sha256']=filehash(baseline_path);result['helperSha256']=filehash(pathlib.Path(__file__));result['motionHelperSha256']=filehash(HERE/'motion'/'verify_motion.py');result['scope']='Offline actual downloaded16payloads, raw Actions artifact+before/aftermetadata, sourceGit, fullJUnit, CI native+DPAPI+install+passiveupgrade, independentPEresource and motion/profile proof inspection. No new seller/Catalog/license/paid API calls; no installer execution.'
 for name,observed in result['assets'].items():
  item=build/'assets'/name;require(item.stat().st_size==observed['bytes'] and filehash(item)==observed['sha256'],'asset-changed-after-extended-review:'+name)
 # Publishable expected map is emitted only after all independent gates above PASS.
 out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');assetmap.write_text(json.dumps({'sourceCommit':SOURCE,'assets':result['assets']},indent=2)+'\n');print('PASS',out)
if __name__=='__main__':
 try:main()
 except Exception as e:print('VERIFICATION FAILED:',type(e).__name__,str(e),file=sys.stderr);sys.exit(1)
