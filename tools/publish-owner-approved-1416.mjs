// One release only. The normal stable publisher and its acceptance gates remain unchanged.
// The owner explicitly requested publication without further test runs after the baseline
// probe expected schema 4 for 1.4.15 (which actually uses schema 5).
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {mkdtemp,readFile,writeFile,copyFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {validateNativeSmoke,validateSideBySideSmoke,validateUpgradeSmoke,validateReleaseAssets} from './publish-vncode-windows.mjs';
import {validateJavaTestSummary} from './preview-source-guard.mjs';
import {validateWorkspaceSmoke} from './publish-vncode-preview.mjs';
import {validateStableAsset,validatePublishedStable} from './publish-vncode-stable.mjs';

const repo='ntccong2468-lab/Bancapnhat',version='1.4.16',tag='v1.4.16',runId='39061440469';
const source='536567835f6a044626d00be273b2217bb1c4fc7c';
const installerSha='9c18a6d388945358213dad0c6eaa158cdd53b4f78cacbe773fe87b6d0883dfb2';
assert.equal(process.env.RELEASE_BUILD_RUN,runId);assert.equal(process.env.RELEASE_BUILD_SOURCE,source);
const gh=args=>execFileSync('gh',args,{encoding:'utf8',maxBuffer:64*1024*1024});
const api=endpoint=>JSON.parse(gh(['api',`repos/${repo}/${endpoint}`]));
const json=bytes=>JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/,''));
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
// All application, dependency, build and runtime packaging inputs are identical to the tested app.
execFileSync('git',['diff','--exit-code',source,'HEAD','--','pom.xml','src','.mvn','mvnw','mvnw.cmd','build.sh','build.bat','.github/workflows/build-java.yml']);
const decision=json(await readFile('docs/validation/2026-10-17-vncode-1416-owner-publication.json'));
assert.equal(decision.ownerRequestedImmediatePublication,true);
assert.equal(decision.sourceCommit,source);assert.equal(decision.runId,Number(runId));
assert.equal(decision.productionLicenseAcceptance,false);
const run=api(`actions/runs/${runId}`);
assert.equal(run.head_sha,source);assert.equal(run.repository.full_name,repo);
assert.equal(run.workflow_id,api('actions/workflows/build-java.yml').id);
assert.equal(run.status,'completed');assert.equal(run.conclusion,'failure');
const jobs=api(`actions/runs/${runId}/jobs?per_page=100`).jobs;
const desktop=jobs.find(j=>j.name==='build-and-test');assert.ok(desktop);
assert.deepEqual(desktop.steps.filter(s=>s.conclusion==='failure').map(s=>s.name),[
 'Verify data-preserving upgrade from Published VN code 1.4.15']);
for(const name of ['Run Java and FXML test suite','Build unsigned JavaFX EXE installer',
 'Verify native Windows launcher and schema-3 history migration','Verify GS1 Windows DPAPI and additive local storage',
 'Verify ten-shop workspaces and actual Windows DPI 100 125 150','Verify all desktop layouts in four languages and both themes',
 'Verify independent installation, fresh data and safe uninstall','Verify data-preserving upgrade from Published VN code 1.4.13'])
 assert.equal(desktop.steps.find(s=>s.name===name)?.conclusion,'success',name);
assert.equal(jobs.find(j=>j.name==='license-portal')?.conclusion,'success');
const log=gh(['run','view',runId,'--repo',repo,'--log']);const java=validateJavaTestSummary(log);
assert.deepEqual(java,{tests:1687,failures:0,errors:0,skipped:2,passed:1685});
assert.ok(log.includes('Schema is not ready')&&log.includes('WindowsDataProbe.java:27'));
const dir=await mkdtemp(path.join(tmpdir(),'vncode-owner-1416-'));
gh(['run','download',runId,'--repo',repo,'--name',`VN-code-${version}-Windows-native-diagnostics-${runId}`,'--dir',dir]);
const out=path.join(dir,'out');const native=await readFile(path.join(out,'VN code-1.4.16.exe'));
assert.equal(native.length,145501184);assert.equal(hash(native),installerSha);
assert.equal(native.subarray(0,2).toString(),'MZ');const pe=native.readUInt32LE(0x3c);
assert.equal(native.subarray(pe,pe+4).toString(),'PE\0\0');
const name=`VN-code-${version}-Windows-x64.exe`,checksum=`VN-code-${version}-Windows-x64.sha256`;
await writeFile(path.join(out,name),native);await writeFile(path.join(out,checksum),`${installerSha}  ${name}\n`);
const read=async file=>json(await readFile(path.join(out,file)));
const smoke=await read('native-smoke.json');validateNativeSmoke(smoke,version);
validateSideBySideSmoke(await read('side-by-side-smoke.json'),version);
const proofs=['native-smoke.json','side-by-side-smoke.json'];
const upgrades=[['upgrade-smoke.json','1.1.34'],['upgrade-preview-smoke.json','1.2.0'],
 ['upgrade-latest-preview-smoke.json','1.2.1'],['upgrade-current-preview-smoke.json','1.2.2'],
 ['upgrade-gs1-preview-smoke.json','1.3.0'],['upgrade-license-test-smoke.json','1.4.0'],
 ['upgrade-stable-146-smoke.json','1.4.6'],['upgrade-stable-1410-smoke.json','1.4.10'],
 ['upgrade-stable-1411-smoke.json','1.4.11'],['upgrade-stable-1412-smoke.json','1.4.12'],
 ['upgrade-stable-1413-smoke.json','1.4.13']];
for(const [file,from] of upgrades){validateUpgradeSmoke(await read(file),version,from);proofs.push(file);}
validateWorkspaceSmoke(await read('seller-workspace-smoke.json'),version,source);
const ui=await read('ui-layout.json');assert.equal(ui.version,version);assert.equal(ui.layouts.length,288);
assert.ok(ui.layouts.every(l=>l.horizontalButtonOverflow.length===0));
const gs1=await read('gs1-smoke.json');assert.equal(gs1.result,'passed');assert.equal(gs1.version,version);
assert.equal(gs1.catalogKeyDpapiRoundTrip,true);assert.equal(gs1.catalogKeyPurposeIsolated,true);assert.equal(gs1.liveGS1Mutations,false);
proofs.push('seller-workspace-smoke.json','ui-layout.json','gs1-smoke.json');
const portal='VNCode-Portal-0.3.6-License-Network';
gh(['run','download',runId,'--repo',repo,'--name',portal,'--dir',out]);
assert.equal(hash(await readFile(path.join(out,portal+'.zip'))),(await readFile(path.join(out,portal+'.sha256'),'utf8')).trim().split(/\s+/)[0]);
const notes=(await readFile('docs/releases/VN-code-1.4.16.md','utf8')).trim();
await writeFile(path.join(out,'release-notes.md'),notes+'\n');
const info={version,sourceCommit:source,runId:Number(runId),java,
 installer:{filename:name,bytes:native.length,sha256:installerSha},licenseProductionAcceptance:false,native:smoke,
 channel:'stable',authenticodeSigned:false,ownerApprovedPublication:true,upgradeBaselinesVerified:upgrades.map(x=>x[1]),
 upgrade1415Retested:false,acceptanceLimits:'release-acceptance.json'};
await writeFile(path.join(out,'build-info.json'),JSON.stringify(info,null,2)+'\n');
await copyFile('docs/validation/2026-10-17-vncode-1416-owner-publication.json',path.join(out,'release-acceptance.json'));
const names=[name,checksum,'build-info.json','release-notes.md','release-acceptance.json',...proofs,portal+'.zip',portal+'.sha256'];
assert.equal(names.length,23);
const refs=api(`git/matching-refs/tags/${tag}`).filter(r=>r.ref===`refs/tags/${tag}`);
if(refs.length){assert.equal(refs.length,1);assert.equal(refs[0].object.sha,source);}
else{const file=path.join(dir,'tag.json');await writeFile(file,JSON.stringify({ref:`refs/tags/${tag}`,sha:source}));gh(['api','-X','POST',`repos/${repo}/git/refs`,'--input',file]);}
let release=api('releases?per_page=100').find(r=>r.tag_name===tag);
if(!release){const file=path.join(dir,'create.json');await writeFile(file,JSON.stringify({tag_name:tag,target_commitish:source,draft:true,prerelease:false,name:'VNCode 1.4.16',body:notes}));release=JSON.parse(gh(['api','-X','POST',`repos/${repo}/releases`,'--input',file]));}
assert.equal(release.draft,true);assert.equal(release.prerelease,false);validateReleaseAssets(release.assets,names);
for(const file of names){
 const digest='sha256:'+hash(await readFile(path.join(out,file)));const existing=release.assets.find(a=>a.name===file);
 if(existing){assert.equal(existing.digest,digest);assert.equal(existing.state,'uploaded');}
 else gh(['release','upload',tag,path.join(out,file),'--repo',repo]);
}
gh(['release','edit',tag,'--repo',repo,'--title','VNCode 1.4.16','--notes-file',path.join(out,'release-notes.md')]);
const draft=api(`releases/${release.id}`);validateReleaseAssets(draft.assets,names,true);
for(const asset of draft.assets){const bytes=await readFile(path.join(out,asset.name));assert.equal(asset.digest,'sha256:'+hash(bytes));assert.equal(asset.size,bytes.length);}
validateStableAsset(draft.assets.find(a=>a.name===name),version,installerSha,native.length,true);
const request=path.join(dir,'publish.json');await writeFile(request,JSON.stringify({draft:false,prerelease:false,make_latest:'true'}));
gh(['api','-X','PATCH',`repos/${repo}/releases/${release.id}`,'--input',request]);
const latest=api('releases/latest');validatePublishedStable(latest,version);
validateStableAsset(latest.assets.find(a=>a.name===name),version,installerSha,native.length);
console.log(JSON.stringify({published:true,version,releaseId:latest.id,url:latest.html_url,sourceCommit:source,runId,sha256:installerSha,assets:names.length,ownerApproved:true}));
