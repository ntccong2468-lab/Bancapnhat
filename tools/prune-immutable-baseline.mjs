import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
import {requiredAssets,validateCompleteRelease,pruneOlderReleases} from './baseline-release.mjs';
const repo='ntccong2468-lab/Bancapnhat';
const [directoryArg,inventoryPath,checkpoint,...options]=process.argv.slice(2),directory=path.resolve(directoryArg||'out');
assert.match(checkpoint||'',/^[a-f0-9]{40}$/,'Committed public archive required');
assert.match(inventoryPath||'',/^docs\/validation\/evidence\/[A-Za-z0-9._-]+\/previous-release-inventory\.json$/);
assert.ok(options.every(value=>value==='--dry-run'));
const gh=(...args)=>execFileSync('gh',args,{encoding:'utf8',maxBuffer:32*1024*1024}).trim();
const api=endpoint=>JSON.parse(gh('api',`repos/${repo}/${endpoint}`));
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const manifest=JSON.parse(await readFile(path.join(directory,'asset-manifest.json'),'utf8'));
const source=manifest.sourceCommit,release=api('releases/latest');
validateCompleteRelease(release,manifest,source);assert.equal(release.immutable,true);
assert.equal(api(`git/ref/tags/${release.tag_name}`).object.sha,source);
const verified=JSON.parse(await readFile(path.join(directory,'public-verification.json'),'utf8'));
assert.equal(verified.publicAssetsVerified,true);assert.equal(verified.releaseId,release.id);assert.equal(verified.sourceCommit,source);assert.deepEqual(verified.files,manifest.files);
for(const name of requiredAssets(manifest.version)){const data=await readFile(path.join(directory,name));assert.equal(data.length,manifest.files[name].bytes);assert.equal(hash(data),manifest.files[name].sha256);}
const updater=JSON.parse(await readFile(path.join(directory,'updater-verification.json'),'utf8'));
assert.equal(updater.version,manifest.version);assert.equal(updater.releaseId,release.id);assert.equal(updater.latest,true);assert.equal(updater.draft,false);assert.equal(updater.prerelease,false);
assert.equal(updater.sourceCommit,source);assert.equal(updater.actualReleasedApplicationJar,true);assert.equal(updater.actualUpdateApiClientParser,true);assert.equal(updater.actualVersionComparator,true);assert.equal(updater.actualUpdateServiceAutomaticCheck,'AVAILABLE');
const selected=updater.selectedInstallerName;assert.ok([`VN-code-${manifest.version}-Windows-x64.exe`,`VN-code-${manifest.version}-Windows-x64.msi`].includes(selected));
assert.equal(updater.installerSha256,manifest.files[selected].sha256);assert.equal(updater.installerBytes,manifest.files[selected].bytes);assert.equal(updater.windowsInstallerUrl,`https://github.com/${repo}/releases/download/${release.tag_name}/${selected}`);
const archiveBytes=await readFile(inventoryPath),archive=JSON.parse(archiveBytes.toString('utf8'));
assert.equal(archive.retainedRelease,release.id);assert.equal(archive.sourceCommit,source);assert.equal(archive.version,manifest.version);
const remote=api(`contents/${inventoryPath}?ref=${checkpoint}`);
const blob=createHash('sha1').update(Buffer.from(`blob ${archiveBytes.length}\0`)).update(archiveBytes).digest('hex');
assert.equal(remote.sha,blob,'Archive is not stored at the public checkpoint');assert.equal(remote.size,archiveBytes.length);
const archived=new Map(archive.releases.map(r=>[r.id,r]));assert.equal(archived.size,archive.releases.length);assert.ok(archived.has(release.id));
const query='query { repository(owner:"ntccong2468-lab", name:"Bancapnhat") { latestRelease { tagName } } }';
const snapshot=()=>JSON.parse(gh('api','graphql','-f',`query=${query}`)).data.repository;
const current=api('releases?per_page=100');assert.ok(current.length<100,'Complete enumeration required before cleanup');assert.ok(current.some(r=>r.id===release.id));assert.equal(snapshot().latestRelease.tagName,release.tag_name);
for(const row of current){assert.ok(archived.has(row.id),`Unarchived concurrent release: ${row.tag_name}`);assert.equal(row.tag_name,archived.get(row.id).tag_name);}
const present=new Set(current.map(r=>r.id)),targets=archive.releases.filter(r=>present.has(r.id));
const dry=options.includes('--dry-run'),removed=[],alreadyAbsent=archive.releases.filter(r=>r.id!==release.id&&!present.has(r.id)).map(r=>({id:r.id,tag:r.tag_name}));
const deleted=await pruneOlderReleases({release,manifest,sourceCommit:source,releases:targets,readLatest:async()=>{if(dry)return release;assert.equal(snapshot().latestRelease.tagName,release.tag_name,'Latest changed');assert.equal(api(`git/ref/tags/${release.tag_name}`).object.sha,source);return api('releases/latest');},deleteRelease:async id=>{
 if(dry){console.log(`Would remove older release ${id}`);return;}
 let row;try{row=api(`releases/${id}`);}catch(error){if(String(error.stderr).includes('HTTP 404')){alreadyAbsent.push({id,tag:archived.get(id).tag_name});console.log(`Already absent older release ${id}`);return;}throw error;}
 assert.equal(row.id,id);assert.equal(row.tag_name,archived.get(id).tag_name,'Release identity changed');
 gh('api','--method','DELETE',`repos/${repo}/releases/${id}`);removed.push({id,tag:row.tag_name});console.log(`Removed older release ${id}`);
}});
const proof={version:manifest.version,sourceCommit:source,retainedRelease:release.id,archiveCommit:checkpoint,archivePath:inventoryPath,archiveSha256:hash(archiveBytes),gitHistoryPreserved:true,immutableReleaseAssetsUnchanged:true,deletedThisRun:removed,alreadyAbsent,cleanupToolCommit:execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim(),dryRun:dry};
if(!dry){const remaining=api('releases?per_page=100');assert.deepEqual(remaining.map(r=>r.id),[release.id]);assert.equal(snapshot().latestRelease.tagName,release.tag_name);const retained=api(`releases/tags/${release.tag_name}`);assert.equal(retained.id,release.id);validateCompleteRelease(retained,manifest,source);proof.deleted=archive.releases.filter(r=>r.id!==release.id).map(r=>({id:r.id,tag:r.tag_name}));proof.completedAt=new Date().toISOString();}
await writeFile(path.join(directory,dry?'baseline-cleanup-plan.json':'baseline-cleanup.json'),JSON.stringify(proof,null,2)+'\n');
console.log(JSON.stringify({version:manifest.version,dryRun:dry,candidates:deleted.length,archiveCommit:checkpoint,remainingRelease:release.id}));
