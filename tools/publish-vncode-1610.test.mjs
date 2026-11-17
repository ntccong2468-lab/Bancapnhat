import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,symlink,rm,readFile,mkdir,chmod,access} from 'node:fs/promises';
import path from 'node:path';
import {tmpdir} from 'node:os';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {readRegularAsset} from './release-asset-file.mjs';
import {assets,candidate as c,validateEvidence,validateStagedAssets,validateReleaseIdentity,validateBaselinePublished,validatePublicationPins,retentionInventory,validatePreservation,validateArtifactMetadata,validateRunSnapshot,withReviewedRun} from './publish-vncode-1610.mjs';

const actualPinsRequired={skip:c.pinsFinalized?false:'PENDING actual successful Windows counts, reviewed artifact and draft identity; no forecast acceptance'};
function evidence(){return {
  run:{id:c.build,status:'completed',conclusion:'success',repository:{full_name:c.repo},head_repository:{full_name:c.repo},head_sha:c.source,workflow_id:c.workflow,run_attempt:c.buildAttempt},
  info:{version:c.version,sourceCommit:c.source,runId:c.build,runAttempt:c.buildAttempt,workflow:'publish-vncode-baseline.yml',verification:{fullJavaSuiteRun:true,nativeSmokeRun:true,baselineUpgradeRun:true,java:{tests:c.java.tests,failures:0,errors:0,skipped:c.java.skipped,passed:c.java.passed}}},
  manifest:{version:c.version,sourceCommit:c.source,files:Object.fromEntries(assets.map(n=>[n,{bytes:123,sha256:'a'.repeat(64)}]))},
  native:{version:c.version,platform:'windows-x64',appName:'VN code',windowTitle:`VN code v${c.version}`,nativeLauncher:'passed',schemaMigration:'3 to 6',historyPreserved:true,rollbackSnapshotVerified:true,liveMarketplaceMutations:false},
  isolation:{appName:'VN code',version:c.version,platform:'windows-x64',result:'passed',wcodeVersion:'1.1.75',windowTitle:`VN code v${c.version}`,installerUpgradeUuid:'8CBBA0E2-6E73-4F56-9101-6BC0948D3C72',independentRegistrations:true,freshVncodeData:true,wcodeFilesUnchanged:true,uninstallPreservesWcode:true,liveMarketplaceMutations:false},
  upgrade:{appName:'VN code',version:c.version,fromVersion:c.baseline,result:'passed',installerUpgradeUuid:'8CBBA0E2-6E73-4F56-9101-6BC0948D3C72',singleRegistration:true,dataPreserved:true,liveMarketplaceMutations:false,fingerprint:'b'.repeat(64),passiveExeHandoffVerified:true,baselineTag:`v${c.baseline}`,baselineInstallerSha256:c.baselineInstallerSha256,baselineInstallerBytes:c.baselineInstallerBytes},
  gs1:{version:c.version,result:'passed',liveGS1Mutations:false},previous:{version:c.baseline,skipUpgrade:false,tag:`v${c.baseline}`,sha256:c.baselineInstallerSha256,bytes:c.baselineInstallerBytes,url:`https://github.com/${c.repo}/releases/download/v${c.baseline}/VN-code-${c.baseline}-Windows-x64.exe`}
};}
test('only the pinned complete Windows build with upgrade evidence is eligible',actualPinsRequired,()=>assert.equal(validateEvidence(evidence()),true));
test('wrong source, incomplete tests and unproved native upgrade cannot publish',actualPinsRequired,()=>{
  for(const change of [e=>e.run.head_sha='c'.repeat(40),e=>e.run.conclusion='failure',e=>e.run.id++,e=>e.run.run_attempt++,e=>e.info.runAttempt++,e=>e.info.sourceCommit='c'.repeat(40),
    e=>e.info.verification.java.failures=1,e=>e.info.verification.java.errors=1,e=>e.info.verification.java.tests=10,
    e=>e.info.verification.baselineUpgradeRun=false,e=>e.upgrade.fromVersion='1.5.19',e=>e.upgrade.passiveExeHandoffVerified=false,
    e=>e.previous.sha256='f'.repeat(64),e=>e.previous.bytes++,e=>e.previous.skipUpgrade=true,e=>e.upgrade.baselineInstallerSha256='f'.repeat(64),e=>e.upgrade.baselineInstallerBytes++,
    e=>e.native.historyPreserved=false,e=>e.gs1.liveGS1Mutations=true,e=>delete e.manifest.files[assets[0]]]){
    const e=evidence();change(e);assert.throws(()=>validateEvidence(e));
  }
});
test('frozen 1610 source rejects partial totals, overcounts and changed OS skip counts',actualPinsRequired,()=>{
  const summaries=[
    {tests:4463,skipped:c.java.skipped,passed:4463-c.java.skipped},
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
test('resume only the verified draft ID even when release listing is stale',actualPinsRequired,()=>{
  const release={id:c.releaseId,tag_name:`v${c.version}`,target_commitish:c.source,draft:true,prerelease:false,assets:[]};
  assert.equal(validateReleaseIdentity(release),true);
  for(const change of [r=>r.id++,r=>r.tag_name='v1.6.2',r=>r.target_commitish='wrong-source',
    r=>r.prerelease=true,r=>r.assets=[{name:'unreviewed.exe'}]]){
    const invalid=structuredClone(release);change(invalid);assert.throws(()=>validateReleaseIdentity(invalid));
  }
});
test('release validation reads a regular asset and refuses a directory or symbolic link',async()=>{
  const dir=await mkdtemp(path.join(tmpdir(),'vncode-1610-asset-test-'));
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

test('the immutable 169 upgrade baseline must retain its exact identity and installer bytes',()=>{
  const release={id:c.baselineReleaseId,tag_name:`v${c.baseline}`,target_commitish:c.baselineSource,draft:false,prerelease:false,immutable:true,
    assets:[{name:`VN-code-${c.baseline}-Windows-x64.exe`,size:c.baselineInstallerBytes,digest:`sha256:${c.baselineInstallerSha256}`,browser_download_url:`https://github.com/${c.repo}/releases/download/v${c.baseline}/VN-code-${c.baseline}-Windows-x64.exe`}]};
  assert.equal(validateBaselinePublished(release),true);
  for(const change of [r=>r.id++,r=>r.target_commitish='wrong-source',r=>r.tag_name='v1.6.6',r=>r.immutable=false,r=>r.draft=true,
    r=>r.assets[0].size++,r=>r.assets[0].digest='sha256:'+'f'.repeat(64),r=>r.assets[0].browser_download_url='https://example.com/other.exe',r=>r.assets.push(r.assets[0])]){
    const invalid=structuredClone(release);change(invalid);assert.throws(()=>validateBaselinePublished(invalid));
  }
});

test('pending full-suite totals stay explicit; finalized totals retain the published 169 baseline',()=>{
  if(!c.pinsFinalized){
    assert.equal(c.java.forecast,true);
    for(const key of ['tests','passed','skipped'])assert.equal(c.java[key],'PENDING');
    for(const key of ['releaseId','artifactId','artifactDigest','buildAttempt'])assert.equal(c[key],'PENDING');
    return;
  }
  assert.ok(Number.isSafeInteger(c.java.tests));assert.ok(c.java.tests>=4464);
  assert.equal(c.java.passed+c.java.skipped,c.java.tests);
});

// These fixture checks exercise publication gates; they do not replace CI evidence.
test('publication refuses provisional or forecast pins before making any release changes',()=>{
  if(c.pinsFinalized){assert.equal(c.java.forecast,false);assert.ok(c.releaseId>0);assert.equal(validatePublicationPins(),true);}
  else {assert.equal(c.java.forecast,true);assert.throws(()=>validatePublicationPins(),/not finalized/);}
});

test('only the reviewed immutable Actions archive can supply publication bytes',actualPinsRequired,()=>{
  const artifact={id:c.artifactId,name:`VN-code-${c.version}-complete-baseline`,expired:false,digest:c.artifactDigest};
  assert.equal(validateArtifactMetadata(artifact),true);
  for(const change of [a=>a.id++,a=>a.name='other-build',a=>a.expired=true,
    a=>a.digest='sha256:'+(c.artifactDigest==='sha256:'+'f'.repeat(64)?'e':'f').repeat(64),a=>delete a.digest]){
    const changed=structuredClone(artifact);change(changed);assert.throws(()=>validateArtifactMetadata(changed));
  }
});

test('retention proof requires every previous published release, asset and tag to remain unchanged',()=>{
  const release={id:100,tag_name:'v1.6.9',target_commitish:'a'.repeat(40),draft:false,prerelease:false,immutable:true,published_at:'2026-11-10T15:34:37Z',
    assets:[{id:123,name:'old.exe',state:'uploaded',size:12,digest:'sha256:'+'b'.repeat(64),browser_download_url:'https://github.com/fixture/old.exe'}]};
  const tag={ref:'refs/tags/v1.6.9',object:{type:'commit',sha:'a'.repeat(40)}};
  const before=retentionInventory([release],[tag]);
  assert.equal(validatePreservation(before,retentionInventory([release,{...release,id:101,tag_name:'v1.6.10'}],[tag])),true);
  for(const change of [r=>delete r.releases[100],r=>r.releases[100].source='c'.repeat(40),r=>r.releases[100].immutable=false,
    r=>r.releases[100].assets.pop(),r=>r.releases[100].assets[0].size++,r=>r.releases[100].assets[0].digest='sha256:'+'c'.repeat(64),
    r=>delete r.tags['refs/tags/v1.6.9'],r=>r.tags['refs/tags/v1.6.9'].sha='c'.repeat(40)]){
    const changed=structuredClone(before);change(changed);assert.throws(()=>validatePreservation(before,changed));
  }
  assert.throws(()=>retentionInventory([release,release],[tag]));
  assert.throws(()=>retentionInventory([release],[tag,tag]));
});


test('unfinished publication fails before gh is invoked, without passing any credential environment',async()=>{
  assert.ok(Object.isFrozen(c));assert.ok(Object.isFrozen(c.java));
  if(c.pinsFinalized){assert.equal(validatePublicationPins(),true);return;}
  const dir=await mkdtemp(path.join(tmpdir(),'vncode-1610-blocked-main-'));
  try{
    const bin=path.join(dir,'bin');await mkdir(bin);const marker=path.join(dir,'gh-was-invoked');
    const gh=path.join(bin,'gh');await writeFile(gh,'#!/bin/sh\nprintf called > "$VNCODE_PUBLISH_GH_MARKER"\nexit 97\n');await chmod(gh,0o755);
    await writeFile(path.join(bin,'gh.cmd'),'@echo called>"%VNCODE_PUBLISH_GH_MARKER%"\r\n@exit /b 97\r\n');
    const result=spawnSync(process.execPath,[fileURLToPath(new URL('./publish-vncode-1610.mjs',import.meta.url))],{
      cwd:process.cwd(),encoding:'utf8',timeout:10000,
      env:{PATH:bin,VNCODE_PUBLISH_GH_MARKER:marker}
    });
    assert.equal(result.error,undefined);assert.equal(result.status,1);
    assert.match(result.stderr,/Publication pins are not finalized/);
    await assert.rejects(()=>access(marker),{code:'ENOENT'});
  }finally{await rm(dir,{recursive:true,force:true});}
});

test('the explicit 1610 workflow mode routes only to its own publisher and receipt',async()=>{
  const workflow=await readFile(new URL('../.github/workflows/publish-vncode-stable.yml',import.meta.url),'utf8');
  assert.match(workflow,/owner-requested-169, owner-requested-1610\]/);
  assert.match(workflow,/elif \[ "\$RELEASE_MODE" = "owner-requested-1610" \]; then\s+node tools\/publish-vncode-1610\.mjs/);
  assert.match(workflow,/name: Preserve 1\.6\.10 publication receipt\s+if: inputs\.mode == 'owner-requested-1610'/);
  assert.match(workflow,/path: publication-1\.6\.10\.json/);
});


function completedAttempt(attempt=1){return {id:c.build,status:'completed',conclusion:'success',repository:{full_name:c.repo},head_repository:{full_name:c.repo},head_sha:c.source,workflow_id:c.workflow,run_attempt:attempt};}

test('actual mutation gate rejects attempt mismatch, active rerun and changed source before invoking mutation',()=>{
  const expectedAttempt=1;let mutations=0;
  assert.equal(validateRunSnapshot(completedAttempt(),expectedAttempt),true);
  const validResult=withReviewedRun(()=>completedAttempt(),()=>{mutations++;return 'checked';},expectedAttempt);
  assert.equal(validResult,'checked');assert.equal(mutations,1);
  for(const change of [r=>r.run_attempt=2,r=>{r.status='in_progress';r.conclusion=null;},r=>{r.status='queued';r.conclusion=null;},r=>r.conclusion='failure',r=>r.head_sha='a'.repeat(40),r=>r.id++,r=>r.workflow_id++,r=>r.head_repository.full_name='fixture/other']){
    const snapshot=completedAttempt();change(snapshot);const before=mutations;
    assert.throws(()=>withReviewedRun(()=>snapshot,()=>mutations++,expectedAttempt));
    assert.equal(mutations,before,'Rejected run metadata must never call the mutation callback');
  }
  assert.throws(()=>withReviewedRun(()=>completedAttempt(),()=>mutations++,'PENDING'));
  assert.equal(mutations,1);
});

test('a rerun beginning after initial success prevents the later staged mutation',()=>{
  const snapshots=[completedAttempt(),{...completedAttempt(2),status:'in_progress',conclusion:null}];
  const readRun=()=>snapshots.shift();assert.equal(validateRunSnapshot(readRun(),1),true);
  let mutations=0;assert.throws(()=>withReviewedRun(readRun,()=>mutations++,1));assert.equal(mutations,0);
});
