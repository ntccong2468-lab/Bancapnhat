import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,symlink,rm} from 'node:fs/promises';
import path from 'node:path';
import {tmpdir} from 'node:os';
import {readRegularAsset} from './release-asset-file.mjs';
import {assets,candidate as c,validateEvidence,validateStagedAssets,validateReleaseIdentity,validateBaselinePublished} from './publish-vncode-168.mjs';

function evidence(){return {
  run:{id:c.build,status:'completed',conclusion:'success',repository:{full_name:c.repo},head_repository:{full_name:c.repo},head_sha:c.source,workflow_id:c.workflow},
  info:{version:c.version,sourceCommit:c.source,runId:c.build,workflow:'publish-vncode-baseline.yml',verification:{fullJavaSuiteRun:true,nativeSmokeRun:true,baselineUpgradeRun:true,java:{tests:c.java.tests,failures:0,errors:0,skipped:c.java.skipped,passed:c.java.passed}}},
  manifest:{version:c.version,sourceCommit:c.source,files:Object.fromEntries(assets.map(n=>[n,{bytes:123,sha256:'a'.repeat(64)}]))},
  native:{version:c.version,platform:'windows-x64',appName:'VN code',windowTitle:`VN code v${c.version}`,nativeLauncher:'passed',schemaMigration:'3 to 6',historyPreserved:true,rollbackSnapshotVerified:true,liveMarketplaceMutations:false},
  isolation:{appName:'VN code',version:c.version,platform:'windows-x64',result:'passed',wcodeVersion:'1.1.75',windowTitle:`VN code v${c.version}`,installerUpgradeUuid:'8CBBA0E2-6E73-4F56-9101-6BC0948D3C72',independentRegistrations:true,freshVncodeData:true,wcodeFilesUnchanged:true,uninstallPreservesWcode:true,liveMarketplaceMutations:false},
  upgrade:{appName:'VN code',version:c.version,fromVersion:c.baseline,result:'passed',installerUpgradeUuid:'8CBBA0E2-6E73-4F56-9101-6BC0948D3C72',singleRegistration:true,dataPreserved:true,liveMarketplaceMutations:false,fingerprint:'b'.repeat(64),passiveExeHandoffVerified:true,baselineTag:`v${c.baseline}`,baselineInstallerSha256:c.baselineInstallerSha256,baselineInstallerBytes:c.baselineInstallerBytes},
  gs1:{version:c.version,result:'passed',liveGS1Mutations:false},previous:{version:c.baseline,skipUpgrade:false,tag:`v${c.baseline}`,sha256:c.baselineInstallerSha256,bytes:c.baselineInstallerBytes,url:`https://github.com/${c.repo}/releases/download/v${c.baseline}/VN-code-${c.baseline}-Windows-x64.exe`}
};}
test('only the pinned complete Windows build with upgrade evidence is eligible',()=>assert.equal(validateEvidence(evidence()),true));
test('wrong source, incomplete tests and unproved native upgrade cannot publish',()=>{
  for(const change of [e=>e.run.head_sha='c'.repeat(40),e=>e.run.conclusion='failure',e=>e.run.id++,e=>e.info.sourceCommit='c'.repeat(40),
    e=>e.info.verification.java.failures=1,e=>e.info.verification.java.errors=1,e=>e.info.verification.java.tests=10,
    e=>e.info.verification.baselineUpgradeRun=false,e=>e.upgrade.fromVersion='1.5.19',e=>e.upgrade.passiveExeHandoffVerified=false,
    e=>e.previous.sha256='f'.repeat(64),e=>e.previous.bytes++,e=>e.previous.skipUpgrade=true,e=>e.upgrade.baselineInstallerSha256='f'.repeat(64),e=>e.upgrade.baselineInstallerBytes++,
    e=>e.native.historyPreserved=false,e=>e.gs1.liveGS1Mutations=true,e=>delete e.manifest.files[assets[0]]]){
    const e=evidence();change(e);assert.throws(()=>validateEvidence(e));
  }
});
test('frozen 168 source rejects partial totals, overcounts and changed OS skip counts',()=>{
  const summaries=[
    {tests:4187,skipped:c.java.skipped,passed:4187-c.java.skipped},
    {tests:502,skipped:0,passed:502},
    {tests:c.java.tests-1,skipped:c.java.skipped,passed:c.java.passed-1},
    {tests:c.java.tests+1,skipped:c.java.skipped,passed:c.java.passed+1},
    {tests:c.java.tests,skipped:c.java.skipped+1,passed:c.java.passed-1},
    {tests:c.java.tests,skipped:c.java.skipped,passed:c.java.passed-1},
    {tests:String(c.java.tests),skipped:c.java.skipped,passed:c.java.passed}
  ];
  for(const summary of summaries){
    const e=evidence();Object.assign(e.info.verification.java,summary);
    assert.throws(()=>validateEvidence(e),JSON.stringify(summary));
  }
});
test('every staged asset must match exact content before switching Latest',()=>{
  const local=evidence().manifest.files;
  const release={tag_name:`v${c.version}`,target_commitish:c.source,prerelease:false,assets:assets.map(name=>({name,state:'uploaded',size:123,digest:'sha256:'+'a'.repeat(64)}))};
  validateStagedAssets(release,local);
  for(const change of [r=>r.assets.pop(),r=>r.assets.push(r.assets[0]),r=>r.assets[0].digest='sha256:'+'f'.repeat(64),
    r=>r.assets[0].size++,r=>r.assets[0].state='new',r=>r.target_commitish='dev',r=>r.prerelease=true]){
    const r=structuredClone(release);change(r);assert.throws(()=>validateStagedAssets(r,local));
  }
});
test('resume only the verified draft ID even when release listing is stale',()=>{
  const release={id:c.releaseId,tag_name:`v${c.version}`,target_commitish:c.source,draft:true,prerelease:false,assets:[]};
  assert.equal(validateReleaseIdentity(release),true);
  for(const change of [r=>r.id++,r=>r.tag_name='v1.6.2',r=>r.target_commitish='wrong-source',
    r=>r.prerelease=true,r=>r.assets=[{name:'unreviewed.exe'}]]){
    const invalid=structuredClone(release);change(invalid);assert.throws(()=>validateReleaseIdentity(invalid));
  }
});
test('release validation reads a regular asset and refuses a directory or symbolic link',async()=>{
  const dir=await mkdtemp(path.join(tmpdir(),'vncode-168-asset-test-'));
  try{
    const file=path.join(dir,'asset');await writeFile(file,'verified bytes');
    assert.equal((await readRegularAsset(file)).toString(),'verified bytes');
    await assert.rejects(()=>readRegularAsset(dir));
    if(process.platform!=='win32'){
      const link=path.join(dir,'link');await symlink(file,link);
      await assert.rejects(()=>readRegularAsset(link));
    }
  }finally{await rm(dir,{recursive:true,force:true});}
});

test('the immutable 167 upgrade baseline must retain its exact identity and installer bytes',()=>{
  const release={id:c.baselineReleaseId,tag_name:`v${c.baseline}`,target_commitish:c.baselineSource,draft:false,prerelease:false,immutable:true,
    assets:[{name:`VN-code-${c.baseline}-Windows-x64.exe`,size:c.baselineInstallerBytes,digest:`sha256:${c.baselineInstallerSha256}`,browser_download_url:`https://github.com/${c.repo}/releases/download/v${c.baseline}/VN-code-${c.baseline}-Windows-x64.exe`}]};
  assert.equal(validateBaselinePublished(release),true);
  for(const change of [r=>r.id++,r=>r.target_commitish='wrong-source',r=>r.tag_name='v1.6.6',r=>r.immutable=false,r=>r.draft=true,
    r=>r.assets[0].size++,r=>r.assets[0].digest='sha256:'+'f'.repeat(64),r=>r.assets[0].browser_download_url='https://example.com/other.exe',r=>r.assets.push(r.assets[0])]){
    const invalid=structuredClone(release);change(invalid);assert.throws(()=>validateBaselinePublished(invalid));
  }
});

test('candidate full-suite pins retain the published 167 baseline rather than focused acceptance totals',()=>{
  assert.ok(Number.isSafeInteger(c.java.tests));assert.ok(c.java.tests>=4188);
  assert.equal(c.java.passed+c.java.skipped,c.java.tests);
});
