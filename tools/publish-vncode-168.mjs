import assert from 'node:assert/strict';
import {readFile, readdir, mkdtemp, writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
import {tmpdir} from 'node:os';
import {pathToFileURL} from 'node:url';
import {readRegularAsset} from './release-asset-file.mjs';
import {requiredAssets, validateCompleteRelease} from './baseline-release.mjs';
import {validateBuild, validateNativeSmoke, validateSideBySideSmoke, validateUpgradeSmoke} from './publish-vncode-windows.mjs';

// One owner-requested release. These constants deliberately cannot be replaced by dispatch inputs.
export const candidate=Object.freeze({version:'1.6.8',source:'38906a2108501029d9609ed1d071ee987c8b2637',
  build:42828436446,workflow:392732536,baseline:'1.6.7',repo:'ntccong2468-lab/Bancapnhat',releaseId:440439895,
  java:Object.freeze({tests:4254,passed:4252,skipped:2}),
  baselineReleaseId:438471378,baselineSource:'d0231cd6913ce3a1764067bad0901b32c535de41',
  baselineInstallerSha256:'2da4f820660798d8b3979d90b751c1f1c4f9b1d281550b807c558558c6cdbd3b',baselineInstallerBytes:149379584});
export const assets=Object.freeze([...requiredAssets(candidate.version),'asset-manifest.json','SHA256SUMS',
  'gs1-smoke.json','side-by-side-smoke.json','previous-baseline.json']);
const json=bytes=>JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/,''));
const sha256=bytes=>createHash('sha256').update(bytes).digest('hex');
const gh=args=>execFileSync('gh',args,{encoding:'utf8',maxBuffer:32*1024*1024}).trim();
const api=endpoint=>json(gh(['api',`repos/${candidate.repo}/${endpoint}`]));

export function validateEvidence({run,info,manifest,native,isolation,upgrade,gs1,previous}){
  const c=candidate;
  validateBuild(run,c.source,c.workflow);assert.equal(run.id,c.build);
  assert.equal(info.version,c.version);assert.equal(info.sourceCommit,c.source);assert.equal(info.runId,c.build);
  assert.equal(info.workflow,'publish-vncode-baseline.yml');
  assert.equal(manifest.version,c.version);assert.equal(manifest.sourceCommit,c.source);
  for(const name of requiredAssets(c.version))assert.ok(manifest.files[name],`Missing manifest member: ${name}`);
  for(const key of ['fullJavaSuiteRun','nativeSmokeRun','baselineUpgradeRun'])assert.equal(info.verification[key],true);
  const tests=info.verification.java;
  assert.ok(Number.isSafeInteger(c.java.tests)&&c.java.tests>=4188,'Actual full Windows tests must retain at least the published 1.6.7 baseline of 4188 cases');
  assert.ok(Number.isSafeInteger(c.java.skipped)&&c.java.skipped>=0&&c.java.skipped<c.java.tests,'Actual skip count must be pinned');
  assert.equal(c.java.passed,c.java.tests-c.java.skipped,'Pinned Java totals must be coherent');
  assert.equal(tests.tests,c.java.tests,'Require the exact full Windows case count of the pinned source');assert.equal(tests.failures,0);assert.equal(tests.errors,0);
  assert.equal(tests.skipped,c.java.skipped,'Require the exact verified OS skip count');assert.equal(tests.passed,c.java.passed);
  validateNativeSmoke(native,c.version);validateSideBySideSmoke(isolation,c.version);validateUpgradeSmoke(upgrade,c.version,c.baseline);
  assert.equal(upgrade.passiveExeHandoffVerified,true);
  assert.equal(previous.version,c.baseline);assert.equal(previous.skipUpgrade,false);
  assert.equal(previous.tag,`v${c.baseline}`);assert.equal(previous.sha256,c.baselineInstallerSha256);assert.equal(previous.bytes,c.baselineInstallerBytes);
  assert.equal(previous.url,`https://github.com/${c.repo}/releases/download/v${c.baseline}/VN-code-${c.baseline}-Windows-x64.exe`);
  assert.equal(upgrade.baselineTag,previous.tag);assert.equal(upgrade.baselineInstallerSha256,previous.sha256);assert.equal(upgrade.baselineInstallerBytes,previous.bytes);
  assert.equal(gs1.version,c.version);assert.equal(gs1.result,'passed');assert.equal(gs1.liveGS1Mutations,false);
  return true;
}

export function validateBaselinePublished(release){
  const c=candidate;
  assert.equal(release.id,c.baselineReleaseId);assert.equal(release.tag_name,`v${c.baseline}`);assert.equal(release.target_commitish,c.baselineSource);
  assert.equal(release.draft,false);assert.equal(release.prerelease,false);assert.equal(release.immutable,true);
  const name=`VN-code-${c.baseline}-Windows-x64.exe`,matches=release.assets.filter(asset=>asset.name===name);assert.equal(matches.length,1);
  assert.equal(matches[0].size,c.baselineInstallerBytes);assert.equal(matches[0].digest,`sha256:${c.baselineInstallerSha256}`);
  assert.equal(matches[0].browser_download_url,`https://github.com/${c.repo}/releases/download/v${c.baseline}/${name}`);
  return true;
}

export function validateStagedAssets(release,local){
  assert.equal(release.tag_name,`v${candidate.version}`);
  assert.equal(release.target_commitish,candidate.source);assert.equal(release.prerelease,false);
  assert.deepEqual(release.assets.map(a=>a.name).sort(),[...assets].sort());
  for(const asset of release.assets){
    assert.equal(asset.state,'uploaded');assert.equal(asset.size,local[asset.name].bytes);
    assert.equal(asset.digest,`sha256:${local[asset.name].sha256}`,`Wrong public digest: ${asset.name}`);
  }
}

export function validateReleaseIdentity(release){
  assert.equal(release.id,candidate.releaseId,'Wrong release ID');
  assert.equal(release.tag_name,`v${candidate.version}`,'Wrong release tag');
  assert.equal(release.target_commitish,candidate.source,'Wrong release source');
  assert.equal(release.prerelease,false);
  assert.ok(Array.isArray(release.assets));
  assert.ok(release.assets.every(a=>assets.includes(a.name)),'Unreviewed draft asset');
  return true;
}

async function main(){
  assert.ok(Number.isSafeInteger(candidate.releaseId)&&candidate.releaseId>0,'Draft release ID must be pinned before publication');
  const c=candidate,tag=`v${c.version}`;
  const run=api(`actions/runs/${c.build}`);validateBuild(run,c.source,c.workflow);
  const artifacts=api(`actions/runs/${c.build}/artifacts`).artifacts.filter(a=>a.name===`VN-code-${c.version}-complete-baseline`);
  assert.equal(artifacts.length,1);assert.equal(artifacts[0].expired,false);
  const dir=await mkdtemp(path.join(tmpdir(),'vncode-168-release-'));
  gh(['run','download',String(c.build),'--repo',c.repo,'--name',artifacts[0].name,'--dir',dir]);
  assert.deepEqual((await readdir(dir)).sort(),[...assets].sort(),'Unexpected or missing build assets');
  const read=async name=>json(await readRegularAsset(path.join(dir,name)));
  const info=await read('build-info.json'),manifest=await read('asset-manifest.json');
  const evidence={run,info,manifest,native:await read('native-smoke.json'),isolation:await read('side-by-side-smoke.json'),
    upgrade:await read('upgrade-baseline-smoke.json'),gs1:await read('gs1-smoke.json'),previous:await read('previous-baseline.json')};
  validateEvidence(evidence);
  const local={};
  for(const name of assets){
    const bytes=await readRegularAsset(path.join(dir,name));
    local[name]={bytes:bytes.length,sha256:sha256(bytes)};
    if(manifest.files[name])assert.deepEqual(local[name],manifest.files[name],`Manifest differs: ${name}`);
  }
  const installer=`VN-code-${c.version}-Windows-x64.exe`,msi=`VN-code-${c.version}-Windows-x64.msi`;
  assert.equal(info.installer.name,installer);assert.equal(info.installer.sha256,local[installer].sha256);
  assert.equal(info.installer.bytes,local[installer].bytes);
  assert.equal(evidence.upgrade.passiveExeInstallerSha256,local[installer].sha256);
  assert.equal(evidence.upgrade.passiveExeEmbeddedMsiSha256,local[msi].sha256);
  const sums=(await readFile(path.join(dir,'SHA256SUMS'),'utf8')).trim().split(/\r?\n/);
  const checked=new Set();
  for(const line of sums){const match=/^([a-f0-9]{64})  ([A-Za-z0-9._-]+)$/.exec(line);assert.ok(match);assert.ok(!checked.has(match[2]));checked.add(match[2]);assert.equal(local[match[2]]?.sha256,match[1]);}
  for(const name of [...requiredAssets(c.version),'asset-manifest.json'])assert.ok(checked.has(name));
  execFileSync('git',['bundle','verify',path.join(dir,`VN-code-${c.version}-history.bundle`)],{stdio:'pipe'});
  const existing=api('releases?per_page=100').filter(r=>r.tag_name===tag);assert.ok(existing.length<=1);
  const tags=api(`git/matching-refs/tags/${tag}`).filter(r=>r.ref===`refs/tags/${tag}`);
  assert.ok(tags.length<=1);if(tags.length){assert.equal(tags[0].object.type,'commit');assert.equal(tags[0].object.sha,c.source,'Existing tag points to other code');}
  // The owner-requested draft is pinned by the creation response ID.
  // A stale release-list response must never cause creation of another release.
  if(existing.length)assert.equal(existing[0].id,c.releaseId,'Unexpected duplicate release');
  let release=api(`releases/${c.releaseId}`);
  validateReleaseIdentity(release);
  if(release.draft){
    for(const name of assets){
      const present=release.assets.filter(a=>a.name===name);assert.ok(present.length<=1);
      if(present.length){assert.equal(present[0].digest,`sha256:${local[name].sha256}`);assert.equal(present[0].size,local[name].bytes);}
      else gh(['release','upload',tag,path.join(dir,name),'--repo',c.repo]);
    }
    release=api(`releases/${release.id}`);validateStagedAssets(release,local);
    const latest=api('releases/latest');validateBaselinePublished(latest);
    gh(['release','edit',tag,'--repo',c.repo,'--draft=false','--latest']);
  }
  const latest=api('releases/latest');validateReleaseIdentity(latest);validateStagedAssets(latest,local);validateCompleteRelease(latest,manifest,c.source);assert.equal(latest.immutable,true);
  assert.equal(api(`git/ref/tags/${tag}`).object.sha,c.source);
  await writeFile('publication-1.6.8.json',JSON.stringify({version:c.version,sourceCommit:c.source,buildRun:c.build,
    releaseId:latest.id,url:latest.html_url,publishedAt:latest.published_at,assets:local},null,2)+'\n');
  console.log(`Published and verified ${latest.html_url}; ${assets.length} assets. Previous releases retained.`);
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href)main().catch(error=>{console.error(error.message);process.exitCode=1;});
