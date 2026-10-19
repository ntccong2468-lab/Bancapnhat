import test from 'node:test';
import {readFileSync} from 'node:fs';
import assert from 'node:assert/strict';
import * as preview from './publish-vncode-preview.mjs';
import {validateNativeSmoke} from './publish-vncode-windows.mjs';
import {resolveReleaseVersion} from './release-version.mjs';
const version='1.4.0',sha='a'.repeat(40);
const upgrade=fromVersion=>({appName:'VN code',version,fromVersion,result:'passed',installerUpgradeUuid:'8CBBA0E2-6E73-4F56-9101-6BC0948D3C72',singleRegistration:true,dataPreserved:true,liveMarketplaceMutations:false,fingerprint:'b'.repeat(64)});
const clone=value=>structuredClone(value);
function workspace(){
 const runs=[];
 for(const datasetId of ['6wb4ozon','10ozon'])for(const requestedScale of ['1.0','1.25','1.5'])runs.push({
  buildId:sha,inputJarSha256:'c'.repeat(64),fixtureSha256:(datasetId==='10ozon'?'d':'e').repeat(64),datasetId,
  hardwareId:'Synthetic hardware',logicalProcessors:4,physicalMemoryBytes:16*1024**3,osVersion:'Windows test',requestedScale,cycles:20,targetWindowsLowEndMeasured:false,
  report:{appName:'VN code',version,result:'passed',cycles:20,liveMarketplaceMutations:false,outputScale:Number(requestedScale),
   dataset:{dataset:datasetId,shops:10,products:100000,orders:10000,synthetic:true},
   readWorkers:3,imageWorkers:2,readActive:0,readPending:0,imagePending:0,legacyTasks:0,mutationActive:0,mutationPending:0,sellerHttpActive:0,
   screenCacheBytes:1024,screenCacheBudgetBytes:67108864,listenersBefore:0,listenersAfter:0,heapUsedBytes:100000000,maxHeapBytes:4000000000,fxStallsOver500ms:0,maxFxPulseGapMs:90,
   'cached-shop-to-pulse':{samples:200,medianMs:60,p95Ms:90},'cached-fbs-to-pulse':{samples:datasetId==='10ozon'?200:80,medianMs:80,p95Ms:120}
  }
 });
 return {appName:'VN code',version,result:'passed',sourceRevision:sha,liveMarketplaceMutations:false,targetWindowsLowEndMeasured:false,datasets:['6wb4ozon','10ozon'],scales:['1.0','1.25','1.5'],runs};
}
test('the products release declares a Windows version newer than the GS1 installer',async()=>{
 const source=await resolveReleaseVersion({root:process.cwd()});
 assert.ok(Number(source.version.split('.')[0])>1 || Number(source.version.split('.')[1])>=4);
});
test('schema-five releases require the actual native 3 to 5 migration evidence',()=>{
 const proof={appName:'VN code',version,platform:'windows-x64',windowTitle:'VN code v'+version,nativeLauncher:'passed',schemaMigration:'3 to 5',historyPreserved:true,rollbackSnapshotVerified:true,liveMarketplaceMutations:false};
 assert.doesNotThrow(()=>validateNativeSmoke(proof,version));
 assert.throws(()=>validateNativeSmoke({...proof,schemaMigration:'3 to 4'},version));
});
test('seller KIZ occurrence releases require schema-six native migration evidence',()=>{
 const current='1.4.19';
 const proof={appName:'VN code',version:current,platform:'windows-x64',windowTitle:'VN code v'+current,nativeLauncher:'passed',schemaMigration:'3 to 6',historyPreserved:true,rollbackSnapshotVerified:true,liveMarketplaceMutations:false};
 validateNativeSmoke(proof,current);
 assert.throws(()=>validateNativeSmoke({...proof,schemaMigration:'3 to 5'},current));
 const upgradeProbe=readFileSync(new URL('./windows-upgrade-smoke.ps1',import.meta.url),'utf8');
 assert.match(upgradeProbe,/FromStable1418/);
 assert.match(upgradeProbe,/571f7c26e85722ab742b92000505d606198ba1b66d44f08d2d0a01a4cd231ccd/);
 const workflow=readFileSync(new URL('../.github/workflows/build-java.yml',import.meta.url),'utf8');
 assert.match(workflow,/windows-upgrade-smoke\.ps1 -Version \$env:APP_VERSION -FromStable1418/);
});
test('the products release must upgrade the most recent GS1 installer with preserved data',()=>{
 assert.throws(()=>preview.validatePreviewUpgrades(version,upgrade('1.2.0'),upgrade('1.2.1'),upgrade('1.2.2'),null));
 assert.doesNotThrow(()=>preview.validatePreviewUpgrades(version,upgrade('1.2.0'),upgrade('1.2.1'),upgrade('1.2.2'),upgrade('1.3.0')));
 assert.throws(()=>preview.validatePreviewUpgrades(version,upgrade('1.2.0'),upgrade('1.2.1'),upgrade('1.2.2'),{...upgrade('1.3.0'),dataPreserved:false}));
});
test('1.5.0 tests the immutable 1.4.19 installer with its actual schema six',()=>{
 const probe=readFileSync(new URL('./windows-upgrade-smoke.ps1',import.meta.url),'utf8');
 assert.match(probe,/\$FromStable1419/);
 assert.match(probe,/e2d43bdca42031b6e583dea8efa298addc142e46fadfb2a21ca00b6e5cc31084/);
 assert.match(probe,/\$baselineSchema=if\(\[version\]\$fromVersion -ge \[version\]'1\.4\.19'\)\{6\}/);
 const workflow=readFileSync(new URL('../.github/workflows/build-java.yml',import.meta.url),'utf8');
 assert.match(workflow,/windows-upgrade-smoke\.ps1 -Version \$env:APP_VERSION -FromStable1419/);
});
test('the 1.5.0 upgrade probe selects the exact published installer URL and checksum as a pair',()=>{
 const probe=readFileSync(new URL('./windows-upgrade-smoke.ps1',import.meta.url),'utf8');
 const published=JSON.parse(readFileSync(new URL('../docs/releases/VN-code-1.5.0-status.json',import.meta.url),'utf8')).windowsInstaller;
 function selectedLiteral(name,flag){
  const line=probe.split('\n').find(line=>line.startsWith('$'+name+'='));
  const options=new Map([...line.matchAll(/(?:if|elseif)\(\$(\w+)\)\{'([^']+)'\}/g)].map(m=>[m[1],m[2]]));
  return options.get(flag);
 }
 assert.equal(selectedLiteral('baselineUrl','FromStable150'),published.url);
 assert.equal(selectedLiteral('baselineHash','FromStable150'),published.sha256);
 assert.match(published.url,/\/v1\.5\.0\/VN-code-1\.5\.0-Windows-x64\.exe$/);
});
test('workspace publication requires both datasets and all actual Windows DPI scales',()=>{
 assert.equal(typeof preview.validateWorkspaceSmoke,'function');
 const good=workspace();assert.doesNotThrow(()=>preview.validateWorkspaceSmoke(good,version,sha));
 const missing=clone(good);missing.runs.pop();assert.throws(()=>preview.validateWorkspaceSmoke(missing,version,sha));
 const duplicate=clone(good);duplicate.runs[5]=duplicate.runs[4];assert.throws(()=>preview.validateWorkspaceSmoke(duplicate,version,sha));
 const wrongScale=clone(good);wrongScale.runs[1].report.outputScale=1;assert.throws(()=>preview.validateWorkspaceSmoke(wrongScale,version,sha));
 const wrongOwner=clone(good);wrongOwner.runs[1].buildId='f'.repeat(40);assert.throws(()=>preview.validateWorkspaceSmoke(wrongOwner,version,sha));
});
test('workspace publication rejects resource overruns, unfinished work and leaked listeners',()=>{
 assert.equal(typeof preview.validateWorkspaceSmoke,'function');
 for(const [key,bad] of [['screenCacheBytes',67108865],['readWorkers',4],['imageWorkers',6],['readPending',1],['imagePending',1],['mutationPending',1],['legacyTasks',1],['listenersAfter',1]]){
  const proof=workspace();proof.runs[0].report[key]=bad;assert.throws(()=>preview.validateWorkspaceSmoke(proof,version,sha),key);
 }
 const small=workspace();small.runs[0].report.dataset.products=1000;assert.throws(()=>preview.validateWorkspaceSmoke(small,version,sha));
 const mutations=workspace();mutations.runs[0].report.liveMarketplaceMutations=true;assert.throws(()=>preview.validateWorkspaceSmoke(mutations,version,sha));
 const invalid=workspace();invalid.runs[0].report['cached-fbs-to-pulse'].p95Ms=-1;assert.throws(()=>preview.validateWorkspaceSmoke(invalid,version,sha));
});
test('manual preview metadata binds the download to exact installer bytes, source and build',()=>{
 assert.equal(typeof preview.createPreviewManifest,'function');
 const info={appName:'VN code',version,releaseTag:'v1.4.0-preview.1',sourceCommit:sha,githubRunId:123,installer:{filename:'VN-code-1.4.0-preview.1-Windows-x64.exe',sha256:'b'.repeat(64),bytes:2000000,architecture:'x86_64',authenticodeSigned:false}};
 const manifest=preview.createPreviewManifest(info);
 assert.equal(manifest.automaticUpdate,false);assert.equal(manifest.installMode,'manual');assert.equal(manifest.channel,'preview');
 assert.equal(manifest.installer.sha256,info.installer.sha256);assert.equal(manifest.sourceCommit,sha);assert.equal(manifest.githubRunId,123);
 assert.equal(manifest.installer.url,'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.0-preview.1/VN-code-1.4.0-preview.1-Windows-x64.exe');
 assert.equal(manifest.installerUpgradeUuid,'8CBBA0E2-6E73-4F56-9101-6BC0948D3C72');
 for(const bad of [{...info,version:'1.3.0'},{...info,sourceCommit:'invalid'},{...info,installer:{...info.installer,filename:'other.exe'}},{...info,installer:{...info.installer,sha256:'invalid'}}])assert.throws(()=>preview.createPreviewManifest(bad));
});
