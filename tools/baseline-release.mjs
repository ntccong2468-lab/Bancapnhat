import assert from 'node:assert/strict';
import {readFile,writeFile,mkdir,lstat} from 'node:fs/promises';
import {createReadStream} from 'node:fs';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {resolveReleaseVersion} from './release-version.mjs';
const repo='ntccong2468-lab/Bancapnhat';
export function requiredAssets(version){
 assert.match(version,/^\d+\.\d+\.\d+$/);
 return [`VN-code-${version}-Windows-x64.exe`,`VN-code-${version}-Windows-x64.msi`,`VN-code-${version}-Windows-x64.sha256`,`VN-code-${version}-Windows-x64.zip`,`VN-code-${version}-source.zip`,`VN-code-${version}-history.bundle`,`VN-code-${version}-verification.zip`,'build-info.json','release-notes.md','native-smoke.json','upgrade-baseline-smoke.json'];
}
export function validateCompleteRelease(release,manifest,sourceCommit){
 assert.match(sourceCommit,/^[a-f0-9]{40}$/);assert.equal(manifest.sourceCommit,sourceCommit);
 assert.equal(release.tag_name,`v${manifest.version}`);assert.equal(release.draft,false);assert.equal(release.prerelease,false);
 assert.ok(release.id>0&&release.published_at,'Published release required');
 for(const name of requiredAssets(manifest.version)){
  const wanted=manifest.files[name],assets=release.assets.filter(a=>a.name===name);assert.ok(wanted,`Missing manifest entry: ${name}`);assert.equal(assets.length,1,`Missing/duplicate asset: ${name}`);
  const asset=assets[0];assert.equal(asset.state,'uploaded');assert.ok(wanted.bytes>0);assert.match(wanted.sha256,/^[a-f0-9]{64}$/);
  assert.equal(asset.size,wanted.bytes,`Byte mismatch: ${name}`);assert.equal(asset.digest,`sha256:${wanted.sha256}`,`Digest mismatch: ${name}`);
 }
 return true;
}
function versionParts(tag){const match=/^v(\d+)\.(\d+)\.(\d+)(?:-[A-Za-z0-9.-]+)?$/.exec(tag);assert.ok(match,`Unrelated release must be reviewed separately: ${tag}`);return match.slice(1).map(Number);}
function older(a,b){for(let i=0;i<3;i++){if(a[i]!==b[i])return a[i]<b[i];}return false;}
export async function pruneOlderReleases({release,manifest,sourceCommit,releases,readLatest,deleteRelease}){
 validateCompleteRelease(release,manifest,sourceCommit);
 const candidates=releases.filter(r=>r.id!==release.id),current=versionParts(release.tag_name);
 for(const old of candidates){assert.ok(older(versionParts(old.tag_name),current),`Not an older version: ${old.tag_name}`);if(old.created_at)assert.ok(Date.parse(old.created_at)<=Date.parse(release.published_at),'New concurrent release cannot be deleted');}
 const deleted=[];
 for(const old of candidates){const latest=await readLatest();assert.equal(latest.id,release.id,'Latest changed; cleanup stopped');validateCompleteRelease(latest,manifest,sourceCommit);await deleteRelease(old.id);deleted.push({id:old.id,tag:old.tag_name});}
 return deleted;
}
function gh(args){return execFileSync('gh',args,{encoding:'utf8',maxBuffer:32*1024*1024}).trim();}
function api(endpoint){return JSON.parse(gh(['api',`repos/${repo}/${endpoint}`]));}
async function digest(file){const hash=createHash('sha256');for await(const part of createReadStream(file))hash.update(part);return hash.digest('hex');}
async function download(url,file){
 const parsed=new URL(url);assert.equal(parsed.protocol,'https:');assert.ok(['github.com','api.github.com'].includes(parsed.hostname));
 execFileSync('curl',['--fail','--location','--retry','3','--max-time','600','--output',file,url],{stdio:['ignore','ignore','pipe']});
}
async function makeManifest(directory,version,sourceCommit){
 const files={};for(const name of requiredAssets(version)){const file=path.join(directory,name),stat=await lstat(file);assert.ok(stat.isFile()&&!stat.isSymbolicLink());files[name]={bytes:stat.size,sha256:await digest(file)};}
 const manifest={schema:1,version,sourceCommit,files};await writeFile(path.join(directory,'asset-manifest.json'),JSON.stringify(manifest,null,2)+'\n');
 const all=[...Object.entries(files).map(([name,f])=>`${f.sha256}  ${name}`),`${await digest(path.join(directory,'asset-manifest.json'))}  asset-manifest.json`];await writeFile(path.join(directory,'SHA256SUMS'),all.join('\n')+'\n');return manifest;
}
async function verifyPublic(version,sourceCommit,directory){
 const release=api('releases/latest');assert.equal(release.tag_name,`v${version}`);assert.equal(api(`git/ref/tags/v${version}`).object.sha,sourceCommit,'Tag source changed');
 await mkdir(directory,{recursive:true});
 for(const name of ['asset-manifest.json','SHA256SUMS',...requiredAssets(version)]){
  const assets=release.assets.filter(a=>a.name===name);assert.equal(assets.length,1,`Missing/duplicate public asset: ${name}`);const a=assets[0],file=path.join(directory,name);
  assert.ok(a.size>0&&a.digest?.match(/^sha256:[a-f0-9]{64}$/));await download(a.browser_download_url,file);
  assert.equal((await lstat(file)).size,a.size);assert.equal(`sha256:${await digest(file)}`,a.digest,`Public bytes differ: ${name}`);
 }
 const manifest=JSON.parse(await readFile(path.join(directory,'asset-manifest.json'),'utf8'));validateCompleteRelease(release,manifest,sourceCommit);
 const sums=new Map((await readFile(path.join(directory,'SHA256SUMS'),'utf8')).trim().split(/\r?\n/).map(line=>{const match=/^([a-f0-9]{64})  ([A-Za-z0-9._-]+)$/.exec(line);assert.ok(match,'Invalid checksum entry');return [match[2],match[1]];}));
 for(const name of ['asset-manifest.json',...requiredAssets(version)])assert.equal(await digest(path.join(directory,name)),sums.get(name),`Checksum file differs: ${name}`);
 const info=JSON.parse(await readFile(path.join(directory,'build-info.json'),'utf8'));assert.equal(info.version,version);assert.equal(info.sourceCommit,sourceCommit);assert.equal(info.verification.fullJavaSuiteRun,true);assert.equal(info.verification.nativeSmokeRun,true);assert.equal(info.verification.baselineUpgradeRun,true);
 execFileSync('git',['bundle','verify',path.join(directory,`VN-code-${version}-history.bundle`)],{stdio:['ignore','pipe','pipe']});
 await writeFile(path.join(directory,'public-verification.json'),JSON.stringify({version,sourceCommit,releaseId:release.id,verifiedAt:new Date().toISOString(),publicAssetsVerified:true,files:manifest.files},null,2)+'\n');
 return {release,manifest};
}
async function main(){
 const [mode,directoryArg,sourceArg]=process.argv.slice(2),directory=path.resolve(directoryArg||'out'),{version}=await resolveReleaseVersion(),sourceCommit=sourceArg||execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim();
 if(mode==='manifest'){await makeManifest(directory,version,sourceCommit);return;}
 if(mode==='previous'||mode==='previous-current'){
  const r=api('releases/latest');const same=r.tag_name===`v${version}`;assert.ok(older(versionParts(r.tag_name),versionParts(`v${version}`))||mode==='previous-current'&&same,'Current Latest must be older than candidate');const oldVersion=r.tag_name.slice(1),name=`VN-code-${oldVersion}-Windows-x64.exe`,a=r.assets.find(a=>a.name===name);assert.ok(a?.digest?.match(/^sha256:[a-f0-9]{64}$/));
  await mkdir(directory,{recursive:true});await writeFile(path.join(directory,'previous-baseline.json'),JSON.stringify({version:oldVersion,tag:r.tag_name,url:a.browser_download_url,sha256:a.digest.slice(7),bytes:a.size,skipUpgrade:same},null,2));return;
 }
 assert.ok(mode==='verify-public'||mode==='prune','Mode must be manifest, previous, verify-public or prune');
 const {release,manifest}=await verifyPublic(version,sourceCommit,directory);
 if(mode==='verify-public'){process.stdout.write(`Verified public baseline v${version}\n`);return;}
 const releases=[];for(let page=1;;page++){const batch=api(`releases?per_page=100&page=${page}`);releases.push(...batch);if(batch.length<100)break;}
 const inventory=path.join(directory,'previous-release-inventory.json');
 const existingInventory=api(`releases/${release.id}`).assets.find(a=>a.name==='previous-release-inventory.json');
 if(existingInventory){
  await download(existingInventory.browser_download_url,inventory);assert.equal(existingInventory.digest,`sha256:${await digest(inventory)}`,'Archived inventory changed');
  assert.equal(JSON.parse(await readFile(inventory,'utf8')).retainedRelease,release.id);
 }else{
  await writeFile(inventory,JSON.stringify({archivedAt:new Date().toISOString(),retainedRelease:release.id,releases},null,2)+'\n');
  gh(['release','upload',release.tag_name,inventory,'--repo',repo]);
 }
 const archived=api(`releases/${release.id}`).assets.find(a=>a.name==='previous-release-inventory.json');assert.equal(archived?.digest,`sha256:${await digest(inventory)}`,'Inventory archive must be uploaded before cleanup');
 const deleted=await pruneOlderReleases({release,manifest,sourceCommit,releases,readLatest:async()=>{assert.equal(api(`git/ref/tags/v${version}`).object.sha,sourceCommit,'Tag source changed');return api('releases/latest');},deleteRelease:async id=>{gh(['api','--method','DELETE',`repos/${repo}/releases/${id}`]);process.stdout.write(`Removed older release ${id}\n`);}});
 const remaining=api('releases?per_page=100');assert.deepEqual(remaining.map(r=>r.id),[release.id],'Unexpected remaining release');
 const receipt=path.join(directory,'baseline-cleanup.json');
 const original=JSON.parse(await readFile(inventory,'utf8')).releases.filter(r=>r.id!==release.id).map(r=>({id:r.id,tag:r.tag_name}));
 await writeFile(receipt,JSON.stringify({version,sourceCommit,retainedRelease:release.id,deleted:original,deletedThisRun:deleted,gitHistoryPreserved:true,completedAt:new Date().toISOString()},null,2)+'\n');
 if(!api(`releases/${release.id}`).assets.some(a=>a.name==='baseline-cleanup.json'))gh(['release','upload',release.tag_name,receipt,'--repo',repo]);
}
if(process.argv[1]&&fileURLToPath(import.meta.url)===path.resolve(process.argv[1]))main().catch(error=>{process.stderr.write(`${error.message}\n`);process.exitCode=1;});
