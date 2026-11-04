import assert from 'node:assert/strict';
import {readFile, readdir, lstat, mkdtemp, writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
import {tmpdir} from 'node:os';
import {pathToFileURL} from 'node:url';
import {requiredAssets, validateCompleteRelease} from './baseline-release.mjs';
import {validateBuild, validateNativeSmoke, validateSideBySideSmoke, validateUpgradeSmoke} from './publish-vncode-windows.mjs';

// One owner-requested release. These constants deliberately cannot be replaced by dispatch inputs.
export const candidate=Object.freeze({version:'1.6.2',source:'3968f8f4bb2239ddf61b48e9f6245086bd4ad529',
  build:41789324429,workflow:392732536,baseline:'1.6.1',repo:'ntccong2468-lab/Bancapnhat'});
export const assets=Object.freeze([...requiredAssets(candidate.version),'asset-manifest.json','SHA256SUMS',
  'gs1-smoke.json','side-by-side-smoke.json','previous-baseline.json']);
const json=bytes=>JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/,''));
const sha256=bytes=>createHash('sha256').update(bytes).digest('hex');
const gh=args=>execFileSync('gh',args,{encoding:'utf8',maxBuffer:32*1024*1024}).trim();
const api=endpoint=>json(gh(['api',`repos/${candidate.repo}/${endpoint}`]));

export function validateEvidence({run,info,manifest,native,isolation,upgrade,gs1,previous}){
  const c=candidate;
  validateBuild(run,c.source,c.workflow);
  assert.equal(info.version,c.version);assert.equal(info.sourceCommit,c.source);assert.equal(info.runId,c.build);
  assert.equal(info.workflow,'publish-vncode-baseline.yml');
  assert.equal(manifest.version,c.version);assert.equal(manifest.sourceCommit,c.source);
  for(const name of requiredAssets(c.version))assert.ok(manifest.files[name],`Missing manifest member: ${name}`);
  for(const key of ['fullJavaSuiteRun','nativeSmokeRun','baselineUpgradeRun'])assert.equal(info.verification[key],true);
  const tests=info.verification.java;
  assert.ok(Number.isInteger(tests.tests)&&tests.tests>=2800);assert.equal(tests.failures,0);assert.equal(tests.errors,0);
  assert.ok(Number.isInteger(tests.skipped)&&tests.skipped>=0&&tests.skipped<=2);assert.equal(tests.passed,tests.tests-tests.skipped);
  validateNativeSmoke(native,c.version);validateSideBySideSmoke(isolation,c.version);validateUpgradeSmoke(upgrade,c.version,c.baseline);
  assert.equal(upgrade.passiveExeHandoffVerified,true);
  assert.equal(previous.version,c.baseline);assert.equal(previous.skipUpgrade,false);
  assert.equal(gs1.version,c.version);assert.equal(gs1.result,'passed');assert.equal(gs1.liveGS1Mutations,false);
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

async function main(){
  const c=candidate,tag=`v${c.version}`;
  const run=api(`actions/runs/${c.build}`);validateBuild(run,c.source,c.workflow);
  const artifacts=api(`actions/runs/${c.build}/artifacts`).artifacts.filter(a=>a.name===`VN-code-${c.version}-complete-baseline`);
  assert.equal(artifacts.length,1);assert.equal(artifacts[0].expired,false);
  const dir=await mkdtemp(path.join(tmpdir(),'vncode-162-release-'));
  gh(['run','download',String(c.build),'--repo',c.repo,'--name',artifacts[0].name,'--dir',dir]);
  assert.deepEqual((await readdir(dir)).sort(),[...assets].sort(),'Unexpected or missing build assets');
  const read=async name=>json(await readFile(path.join(dir,name)));
  const info=await read('build-info.json'),manifest=await read('asset-manifest.json');
  const evidence={run,info,manifest,native:await read('native-smoke.json'),isolation:await read('side-by-side-smoke.json'),
    upgrade:await read('upgrade-baseline-smoke.json'),gs1:await read('gs1-smoke.json'),previous:await read('previous-baseline.json')};
  validateEvidence(evidence);
  const local={};
  for(const name of assets){
    const file=path.join(dir,name),stat=await lstat(file);assert.ok(stat.isFile()&&!stat.isSymbolicLink());
    local[name]={bytes:stat.size,sha256:sha256(await readFile(file))};
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
  if(!existing.length)gh(['release','create',tag,'--repo',c.repo,'--draft','--target',c.source,'--title',`VN code ${c.version}`,'--notes-file',path.join(dir,'release-notes.md')]);
  const matches=api('releases?per_page=100').filter(r=>r.tag_name===tag);assert.equal(matches.length,1);
  let release=api(`releases/${matches[0].id}`);
  assert.equal(release.target_commitish,c.source);assert.equal(release.prerelease,false);
  assert.ok(release.assets.every(a=>assets.includes(a.name)),'Unreviewed draft asset');
  if(release.draft){
    for(const name of assets){
      const present=release.assets.filter(a=>a.name===name);assert.ok(present.length<=1);
      if(present.length){assert.equal(present[0].digest,`sha256:${local[name].sha256}`);assert.equal(present[0].size,local[name].bytes);}
      else gh(['release','upload',tag,path.join(dir,name),'--repo',c.repo]);
    }
    release=api(`releases/${release.id}`);validateStagedAssets(release,local);
    const latest=api('releases/latest');assert.equal(latest.tag_name,`v${c.baseline}`,'Latest changed during verification');
    gh(['release','edit',tag,'--repo',c.repo,'--draft=false','--latest']);
  }
  const latest=api('releases/latest');validateStagedAssets(latest,local);validateCompleteRelease(latest,manifest,c.source);
  assert.equal(api(`git/ref/tags/${tag}`).object.sha,c.source);
  await writeFile('publication-1.6.2.json',JSON.stringify({version:c.version,sourceCommit:c.source,buildRun:c.build,
    releaseId:latest.id,url:latest.html_url,publishedAt:latest.published_at,assets:local},null,2)+'\n');
  console.log(`Published and verified ${latest.html_url}; ${assets.length} assets. Previous releases retained.`);
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href)main().catch(error=>{console.error(error.message);process.exitCode=1;});
