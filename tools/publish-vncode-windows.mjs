import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, writeFile, copyFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { resolveReleaseVersion } from './release-version.mjs';
const REPO='ntccong2468-lab/Vncode';
export function validateBuild(run,tagSha,workflowId) {
  assert.equal(run.status,'completed');assert.equal(run.conclusion,'success');
  assert.equal(run.repository?.full_name,REPO);assert.equal(run.head_repository?.full_name,REPO);
  assert.equal(run.head_sha,tagSha);assert.equal(run.workflow_id,workflowId);
  assert.match(tagSha,/^[0-9a-f]{40}$/);
}
export function validateNativeSmoke(smoke,version) {
  assert.equal(smoke.version,version);assert.equal(smoke.platform,'windows-x64');
  assert.equal(smoke.nativeLauncher,'passed');assert.equal(smoke.schemaMigration,'3 to 4');
  assert.equal(smoke.historyPreserved,true);assert.equal(smoke.rollbackSnapshotVerified,true);
  assert.equal(smoke.liveMarketplaceMutations,false);
}
export function validateReleaseAssets(existing,names,complete=false) {
  const allowed=new Set(names);
  assert.ok(existing.every(asset=>allowed.has(asset.name)),"Unverified extra assets on the release draft.");
  assert.equal(new Set(existing.map(asset=>asset.name)).size,existing.length,"Duplicate assets.");
  if(complete) {
    assert.equal(existing.length,names.length);
    assert.ok(names.every(name=>existing.some(asset=>asset.name===name)));
  }
}
const command=(args)=>execFileSync('gh',args,{encoding:'utf8',maxBuffer:32*1024*1024});
const api=(endpoint)=>JSON.parse(command(['api',`repos/${REPO}/${endpoint}`]));
const hash=(bytes)=>createHash('sha256').update(bytes).digest('hex');
const json=(bytes)=>JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/,''));
async function main(runId) {
  assert.match(runId??'',/^[1-9][0-9]{0,15}$/);
  const {version,tag}=await resolveReleaseVersion({root:process.cwd()});
  assert.equal(version,'1.1.33','This publisher validates the schema-4 recovery release.');
  const tagRef=api(`git/ref/tags/${tag}`);assert.equal(tagRef.object.type,'commit');
  const sha=tagRef.object.sha;
  const run=api(`actions/runs/${runId}`),workflow=api('actions/workflows/build-java.yml');
  validateBuild(run,sha,workflow.id);
  // Publishing tooling may differ; the tested application and packaging input must match.
  execFileSync('git',['diff','--quiet',sha,'HEAD','--','pom.xml','src','build.bat','build.sh']);
  const artifactName=`WCode-${version}-Ozon-Test-Windows-x64`;
  const artifacts=api(`actions/runs/${runId}/artifacts?per_page=100`).artifacts.filter(a=>a.name===artifactName&&!a.expired);
  assert.equal(artifacts.length,1,'Require one unexpired Windows build artifact.');
  const directory=await mkdtemp(path.join(tmpdir(),'vncode-release-'));
  command(['run','download',runId,'--repo',REPO,'--name',artifactName,'--dir',directory]);
  const original=`WCode-${version}-Ozon-Test.exe`,name=`Vncode-${version}-Windows-x64.exe`;
  const bytes=await readFile(path.join(directory,original));
  const digest=hash(bytes),checksum=(await readFile(path.join(directory,`WCode-${version}-Ozon-Test.sha256`),'utf8')).trim().split(/\s+/)[0];
  assert.equal(digest,checksum);assert.ok(bytes.length>1024*1024);
  assert.equal(bytes.subarray(0,2).toString(),'MZ');const pe=bytes.readUInt32LE(0x3c);
  assert.equal(bytes.subarray(pe,pe+4).toString(),'PE\0\0');assert.equal(bytes.readUInt16LE(pe+4),0x8664);
  assert.equal(bytes.readUInt16LE(pe+24),0x20b);
  assert.equal(bytes.readUInt32LE(pe+24+112+4*8),0);assert.equal(bytes.readUInt32LE(pe+24+112+4*8+4),0);
  const smoke=json(await readFile(path.join(directory,'native-smoke.json')));validateNativeSmoke(smoke,version);
  const log=command(['run','view',runId,'--repo',REPO,'--log']).replace(/\x1b\[[0-9;]*m/g,'');
  assert.ok(log.includes('Tests run: 553, Failures: 0, Errors: 0, Skipped: 0'));
  assert.match(log,/# tests\s+18(?:\s|$)/);
  await copyFile(path.join(directory,original),path.join(directory,name));
  const runUrl=`https://github.com/${REPO}/actions/runs/${runId}`;
  const info={version,repository:REPO,sourceCommit:sha,githubRunId:Number(runId),githubRunUrl:runUrl,
    javaFxmlTests:{run:553,failures:0,errors:0,skipped:0,platforms:['linux','windows']},
    nodeContracts:{run:18,failures:0},nativeSmoke:smoke,
    installer:{filename:name,originalArtifactFilename:original,bytes:bytes.length,sha256:digest,architecture:'x86_64',authenticodeSigned:false},
    sourceBaseline:'WCode1.1.32 + existing Vncode GTIN module',publicRecoveryChangelog:'WCode1.1.75',
    exactWcode1_1_75SourceIntegrated:false,liveWbOzonGtinWriteEnabled:false,signedUpdateManifestPublished:false};
  await writeFile(path.join(directory,'build-info.json'),JSON.stringify(info,null,2)+'\n');
  const notes=execFileSync('git',['show',`${sha}:docs/releases/Vncode-${version}.md`],{encoding:'utf8'})+
    `\n## Bộ cài và kiểm tra\n\nTải \`${name}\` bên dưới rồi chạy; Java đã được đóng gói kèm.\n\n`+
    `- 553 kiểm thử Java/JavaFX, 18 Node contracts qua trên Windows; 0 thất bại/lỗi/bỏ qua.\n`+
    `- Native Windows launcher và migration schema 3 → 4 giữ GTIN/feed/good ID/cờ WB; integrity/foreign keys và snapshot sạch.\n`+
    `- [CI Windows](${runUrl}), commit \`${sha}\`.\n`+
    `- SHA-256 EXE: \`${digest}\`.\n`+
    `- Đây là prerelease chưa ký Authenticode; tải/cài thủ công. Live GS1/CryptoPro/seller/máy in chưa nghiệm thu; thêm/thay GTIN production WB/Ozon vẫn bị khóa.\n`;
  await writeFile(path.join(directory,'release-notes.md'),notes);
  const assets=[name,'native-smoke.json','build-info.json','release-notes.md'];
  const hashes={};for(const asset of assets)hashes[asset]=hash(await readFile(path.join(directory,asset)));
  await writeFile(path.join(directory,'checksums.sha256'),assets.map(a=>`${hashes[a]}  ${a}\n`).join(''));
  assets.push('checksums.sha256');hashes['checksums.sha256']=hash(await readFile(path.join(directory,'checksums.sha256')));
  const releases=api('releases?per_page=100').filter(r=>r.tag_name===tag);
  assert.equal(releases.length,1,'Require the prepared draft; never create or overwrite an unrelated release.');
  const release=releases[0];assert.equal(release.draft,true);assert.equal(release.prerelease,true);
  validateReleaseAssets(release.assets,assets);
  for(const asset of assets) {
    const existing=release.assets.find(a=>a.name===asset);
    if(existing){assert.equal(existing.state,'uploaded');assert.equal(existing.digest,`sha256:${hashes[asset]}`);continue;}
    command(['release','upload',tag,path.join(directory,asset),'--repo',REPO]);
  }
  const uploaded=api(`releases/${release.id}`);
  assert.equal(uploaded.draft,true);assert.equal(uploaded.tag_name,tag);
  validateReleaseAssets(uploaded.assets,assets,true);
  for(const asset of assets) {
    const actual=uploaded.assets.find(a=>a.name===asset);
    assert.ok(actual);assert.equal(actual.state,'uploaded');assert.equal(actual.digest,`sha256:${hashes[asset]}`);
  }
  assert.equal(api(`git/ref/tags/${tag}`).object.sha,sha);
  command(['release','edit',tag,'--repo',REPO,'--draft=false','--prerelease','--latest=false','--notes-file',path.join(directory,'release-notes.md')]);
  console.log(JSON.stringify({url:`https://github.com/${REPO}/releases/tag/${tag}`,sourceCommit:sha,sha256:digest,bytes:bytes.length}));
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  main(process.argv[2]).catch(error=>{console.error(error.message);process.exitCode=1;});
}
