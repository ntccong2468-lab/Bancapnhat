import {validateLicenseReadiness} from './license-release-gate.mjs';
import {validatePublicationSource,validateJavaTestSummary} from './preview-source-guard.mjs';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {mkdtemp,readFile,writeFile,copyFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {validateBuild,validateNativeSmoke,validateSideBySideSmoke,validateUpgradeSmoke,validateReleaseAssets} from './publish-vncode-windows.mjs';
const REPO='ntccong2468-lab/Bancapnhat';
import {resolveReleaseVersion} from './release-version.mjs';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const gh=args=>execFileSync('gh',args,{encoding:'utf8',maxBuffer:32*1024*1024});
const api=endpoint=>JSON.parse(gh(['api',`repos/${REPO}/${endpoint}`]));
const json=bytes=>JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/,''));
export function validatePreview(status,tag) {
 const match=/^v(\d{1,5}\.\d{1,5}\.\d{1,5})-preview\.[1-9][0-9]*$/.exec(tag);assert.ok(match);
 assert.equal(status.version,match[1]);assert.equal(status.complete,false);
 assert.ok(Array.isArray(status.blocked)&&status.blocked.length>0);
 assert.ok(status.blocked.every(x=>typeof x==='string'&&x.length>0&&x.length<1000));
}
export function validateInstaller(bytes,digest) {
 assert.match(digest,/^[0-9a-f]{64}$/);assert.equal(hash(bytes),digest);
 assert.ok(bytes.length>1024*1024);assert.equal(bytes.subarray(0,2).toString(),'MZ');
 const offset=bytes.readUInt32LE(0x3c);assert.ok(offset+160<bytes.length);
 assert.equal(bytes.subarray(offset,offset+4).toString(),'PE\0\0');assert.equal(bytes.readUInt16LE(offset+4),0x8664);
 assert.equal(bytes.readUInt16LE(offset+24),0x20b);
}
const newerThan122=version=>{
 const parts=version.split('.').map(Number),baseline=[1,2,2];
 for(let i=0;i<3;i++){if(parts[i]!==baseline[i])return parts[i]>baseline[i];}
 return false;
};
const productsRelease=version=>{const [major,minor]=version.split('.').map(Number);return major>1||major===1&&minor>=4;};
export function validateWorkspaceSmoke(proof,version,sha) {
 assert.match(sha,/^[0-9a-f]{40}$/);assert.equal(proof.appName,'VN code');assert.equal(proof.version,version);
 assert.equal(proof.result,'passed');assert.equal(proof.sourceRevision,sha);assert.equal(proof.liveMarketplaceMutations,false);
 assert.equal(proof.targetWindowsLowEndMeasured,false);
 assert.deepEqual(proof.datasets,['6wb4ozon','10ozon']);assert.deepEqual(proof.scales,['1.0','1.25','1.5']);
 assert.equal(proof.runs.length,6);const seen=new Set(),jars=new Set(),fixtures=new Map();
 for(const run of proof.runs){
  assert.ok(proof.datasets.includes(run.datasetId));assert.ok(proof.scales.includes(run.requestedScale));
  const key=run.datasetId+'/'+run.requestedScale;assert.ok(!seen.has(key));seen.add(key);
  assert.equal(run.buildId,sha);assert.match(run.inputJarSha256,/^[0-9a-f]{64}$/);jars.add(run.inputJarSha256);
  assert.match(run.fixtureSha256,/^[0-9a-f]{64}$/);if(fixtures.has(run.datasetId))assert.equal(fixtures.get(run.datasetId),run.fixtureSha256);else fixtures.set(run.datasetId,run.fixtureSha256);
  assert.ok(Number.isInteger(run.logicalProcessors)&&run.logicalProcessors>0);assert.ok(Number.isFinite(run.physicalMemoryBytes)&&run.physicalMemoryBytes>0);
  assert.ok(typeof run.hardwareId==='string'&&run.hardwareId.length>0);assert.ok(typeof run.osVersion==='string'&&run.osVersion.length>0);assert.equal(run.cycles,20);
  const report=run.report;assert.equal(report.result,'passed');assert.equal(report.version,version);assert.equal(report.appName,'VN code');assert.equal(report.cycles,20);assert.equal(report.liveMarketplaceMutations,false);
  assert.equal(report.dataset.dataset,run.datasetId);assert.equal(report.dataset.synthetic,true);assert.equal(report.dataset.shops,10);assert.equal(report.dataset.products,100000);assert.equal(report.dataset.orders,10000);
  assert.ok(Number.isFinite(report.outputScale)&&Math.abs(report.outputScale-Number(run.requestedScale))<=0.02);
  assert.equal(report.readWorkers,3);assert.equal(report.imageWorkers,2);
  for(const field of ['readActive','readPending','imagePending','legacyTasks','mutationActive','mutationPending','sellerHttpActive'])assert.equal(report[field],0,field);
  assert.equal(report.screenCacheBudgetBytes,67108864);assert.ok(Number.isInteger(report.screenCacheBytes)&&report.screenCacheBytes>=0&&report.screenCacheBytes<=67108864);
  assert.ok(Number.isInteger(report.listenersBefore)&&report.listenersBefore>=0);assert.equal(report.listenersAfter,report.listenersBefore);
  for(const field of ['heapUsedBytes','maxHeapBytes','fxStallsOver500ms','maxFxPulseGapMs'])assert.ok(Number.isFinite(report[field])&&report[field]>=0,field);
  for(const operation of ['cached-shop-to-pulse','cached-fbs-to-pulse']){
   const timing=report[operation];assert.equal(timing.samples,operation==='cached-shop-to-pulse'||run.datasetId==='10ozon'?200:80);
   assert.ok(Number.isFinite(timing.medianMs)&&timing.medianMs>=0);assert.ok(Number.isFinite(timing.p95Ms)&&timing.p95Ms>=timing.medianMs);
  }
 }
 assert.equal(jars.size,1);
}
export function createPreviewManifest(info) {
 assert.equal(info.appName,'VN code');validatePreview({version:info.version,complete:false,blocked:['manual preview']},info.releaseTag);
 assert.match(info.sourceCommit,/^[0-9a-f]{40}$/);assert.ok(Number.isSafeInteger(info.githubRunId)&&info.githubRunId>0);
 const installer=info.installer;assert.equal(installer.filename,`VN-code-${info.releaseTag.slice(1)}-Windows-x64.exe`);
 assert.match(installer.sha256,/^[0-9a-f]{64}$/);assert.ok(Number.isSafeInteger(installer.bytes)&&installer.bytes>1048576);
 assert.equal(installer.architecture,'x86_64');assert.equal(installer.authenticodeSigned,false);
 return {schemaVersion:1,appName:'VN code',version:info.version,releaseTag:info.releaseTag,channel:'preview',installMode:'manual',automaticUpdate:false,mandatory:false,
  installerUpgradeUuid:'8CBBA0E2-6E73-4F56-9101-6BC0948D3C72',repository:REPO,sourceCommit:info.sourceCommit,githubRunId:info.githubRunId,
  installer:{...installer,url:`https://github.com/${REPO}/releases/download/${info.releaseTag}/${installer.filename}`}};
}
export function validateGs1Smoke(report,version) {
 assert.equal(report.appName,'VN code');assert.equal(report.version,version);assert.equal(report.result,'passed');
 for(const field of ['dpapiRoundTrip','protectedPasswordPersistence','unverifiedInvoiceBlocked'])assert.equal(report[field],true);
 assert.equal(report.liveGS1Mutations,false);
}
export function validatePreviewUpgrades(version,previous,latest,current,gs1) {
 if(version==='1.2.0')return;
 validateUpgradeSmoke(previous,version,'1.2.0');
 if(version!=='1.2.1')validateUpgradeSmoke(latest,version,'1.2.1');
 if(newerThan122(version))validateUpgradeSmoke(current,version,'1.2.2');
 if(productsRelease(version))validateUpgradeSmoke(gs1,version,'1.3.0');
}
async function main(runId,tag,buildSha) {
 assert.match(runId??'',/^[1-9][0-9]{0,15}$/);
 const {version:VERSION}=await resolveReleaseVersion({root:process.cwd()});
 const status=json(await readFile(`docs/releases/VN-code-${VERSION}-status.json`));validatePreview(status,tag);assert.equal(status.version,VERSION);
 assert.equal(execFileSync('git',['status','--porcelain'],{encoding:'utf8'}).trim(),'','Require a clean checkout.');
 const sha=buildSha??execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim();
 validatePublicationSource(sha);
 if(productsRelease(VERSION))validateLicenseReadiness(status,json(await readFile('src/main/resources/com/vncode/app/license/trusted-keys.json')),json(await readFile(`docs/validation/VN-code-${VERSION}-license-acceptance.json`)),VERSION,sha);
 const run=api(`actions/runs/${runId}`),workflow=api('actions/workflows/build-java.yml');validateBuild(run,sha,workflow.id);
 const artifactName=`VN-code-${VERSION}-Windows-x64`;
 const artifacts=api(`actions/runs/${runId}/artifacts?per_page=100`).artifacts.filter(a=>a.name===artifactName&&!a.expired);
 assert.equal(artifacts.length,1);
 const directory=await mkdtemp(path.join(tmpdir(),'vncode-preview-'));
 gh(['run','download',runId,'--repo',REPO,'--name',artifactName,'--dir',directory]);
 const original=`VN-code-${VERSION}-Windows-x64.exe`,filename=`VN-code-${tag.slice(1)}-Windows-x64.exe`;
 const bytes=await readFile(path.join(directory,original));
 const digest=(await readFile(path.join(directory,`VN-code-${VERSION}-Windows-x64.sha256`),'utf8')).trim().split(/\s+/)[0];validateInstaller(bytes,digest);
 const native=json(await readFile(path.join(directory,'native-smoke.json')));validateNativeSmoke(native,VERSION);
 const coexist=json(await readFile(path.join(directory,'side-by-side-smoke.json')));validateSideBySideSmoke(coexist,VERSION);
 const upgrade=json(await readFile(path.join(directory,'upgrade-smoke.json')));validateUpgradeSmoke(upgrade,VERSION);
 const previewUpgrade=VERSION==='1.2.0'?null:json(await readFile(path.join(directory,'upgrade-preview-smoke.json')));
 const latestPreviewUpgrade=VERSION==='1.2.0'||VERSION==='1.2.1'?null:json(await readFile(path.join(directory,'upgrade-latest-preview-smoke.json')));
 const currentPreviewUpgrade=newerThan122(VERSION)?json(await readFile(path.join(directory,'upgrade-current-preview-smoke.json'))):null;
 const gs1Smoke=newerThan122(VERSION)?json(await readFile(path.join(directory,'gs1-smoke.json'))):null;
 const gs1PreviewUpgrade=productsRelease(VERSION)?json(await readFile(path.join(directory,'upgrade-gs1-preview-smoke.json'))):null;
 const workspaceSmoke=productsRelease(VERSION)?json(await readFile(path.join(directory,'seller-workspace-smoke.json'))):null;
 validatePreviewUpgrades(VERSION,previewUpgrade,latestPreviewUpgrade,currentPreviewUpgrade,gs1PreviewUpgrade);
 if(gs1Smoke)validateGs1Smoke(gs1Smoke,VERSION);
 if(workspaceSmoke){validateWorkspaceSmoke(workspaceSmoke,VERSION,sha);assert.ok((await readFile(path.join(directory,'seller-workspace-evidence.zip'))).length>1024);}
 const log=gh(['run','view',runId,'--repo',REPO,'--log']).replace(/\x1b\[[0-9;]*m/g,'');
 const javaTestSummary=validateJavaTestSummary(log),javaTests=javaTestSummary.passed;
 const nodeTests=Number(log.match(/(?:#|ℹ)\s*tests\s+(\d+)/)?.[1]??0);
 assert.ok(javaTests>=585);assert.ok(nodeTests>=27);
 await copyFile(path.join(directory,original),path.join(directory,filename));
 const info={appName:'VN code',version:VERSION,releaseTag:tag,stage:'preview',complete:false,
  sourceCommit:sha,repository:REPO,githubRunId:Number(runId),javaTests,javaTestSummary,nodeTests,
  installer:{filename,sha256:digest,bytes:bytes.length,architecture:'x86_64',authenticodeSigned:false},
  liveMarketplaceMutations:false,signedUpdateManifestPublished:false,manualPreviewManifestPublished:productsRelease(VERSION),blocked:status.blocked,nativeSmoke:native,upgradeSmoke:upgrade,previewUpgradeSmoke:previewUpgrade,latestPreviewUpgradeSmoke:latestPreviewUpgrade,currentPreviewUpgradeSmoke:currentPreviewUpgrade,gs1PreviewUpgradeSmoke:gs1PreviewUpgrade,gs1Smoke,workspaceSmoke};
 await writeFile(path.join(directory,'build-info.json'),JSON.stringify(info,null,2)+'\n');
 await writeFile(path.join(directory,'feature-status.json'),JSON.stringify(status,null,2)+'\n');
 if(productsRelease(VERSION))await writeFile(path.join(directory,'update-manifest.json'),JSON.stringify(createPreviewManifest(info),null,2)+'\n');
 const notes=`# VN code ${VERSION} — bản thử nghiệm\n\nBộ cài nâng cấp cho cùng ứng dụng VN code Windows, giữ định danh và dữ liệu VN code đã cài. Dự án do Nguyễn Thành Công phát triển và quản lý. Phạm vi đã triển khai và còn thiếu được ghi dưới đây.\n\n`+
  `## Thay đổi giao diện\n\n${(status.changes??[]).map(x=>`- ${x}`).join("\n")}\n\n`+
  `## Đã có trong bản này\n\n${(status.implemented??['Giữ các chức năng VN code của bản đã phát hành trước.']).map(x=>`- ${x}`).join('\n')}\n\n`+
  `## Chưa hoàn tất\n\n${status.blocked.map(x=>`- ${x}`).join('\n')}\n\n`+
  `## Cài đặt và kiểm tra\n\nĐóng VN code rồi chạy **${filename}** bên dưới; Java đã được đóng gói. Bộ cài chưa ký Authenticode. ${productsRelease(VERSION)?'update-manifest.json ghi thông tin tải thủ công, checksum, source commit và build ID; automaticUpdate=false.':'Không phát hành manifest.'} Bản thử không được đưa vào cập nhật tự động. Bản hoàn thiện tiếp theo sẽ dùng số phiên bản Windows cao hơn để tiếp tục nâng cấp cùng ứng dụng.\n\n`+
  `- ${javaTests} kiểm thử Java/JavaFX và ${nodeTests} kiểm thử Node đạt trên Windows; 0 lỗi, ${javaTestSummary.skipped} kiểm thử bỏ qua${javaTestSummary.skipped?' (các trường hợp chỉ áp dụng ngoài Windows)':''}.\n- Launcher, kiểm tra cài song song và nâng cấp VN code 1.1.34 giữ dữ liệu đã đạt.${previewUpgrade?" Nâng cấp từ VN code 1.2.0 Preview cũng đã đạt.":""}${latestPreviewUpgrade?" Nâng cấp từ VN code 1.2.1 Preview cũng đã đạt.":""}${currentPreviewUpgrade?" Nâng cấp từ VN code 1.2.2 Preview cũng đã đạt.":""}${gs1PreviewUpgrade?" Nâng cấp từ VN code 1.3.0 Preview (schema 4 → 5) cũng đã đạt.":""}\n${workspaceSmoke?'- Hai dataset 10 shop × 20 vòng, Products/GS1/Copy/Ozon và DPI 100/125/150% có JSON/phần cứng/ảnh chụp trong seller-workspace-evidence.zip. Chưa đo Windows 2 CPU/4 GiB.\n':''}- Chưa nghiệm thu với credential shop thật, GS1/CryptoPro/chứng thư và SMTP/IMAP thật hoặc máy in thật; không có thao tác ghi marketplace/GS1 thật trong CI.\n- [CI](https://github.com/${REPO}/actions/runs/${runId}); commit ${sha}.\n- SHA-256: ${digest}.\n`;
 await writeFile(path.join(directory,'release-notes.md'),notes);
 const assets=[filename,'build-info.json','feature-status.json','native-smoke.json','side-by-side-smoke.json','upgrade-smoke.json','release-notes.md'];
 if(previewUpgrade)assets.push('upgrade-preview-smoke.json');
 if(latestPreviewUpgrade)assets.push('upgrade-latest-preview-smoke.json');
 if(currentPreviewUpgrade)assets.push('upgrade-current-preview-smoke.json');
 if(gs1Smoke)assets.push('gs1-smoke.json');
 if(gs1PreviewUpgrade)assets.push('upgrade-gs1-preview-smoke.json');
 if(workspaceSmoke)assets.push('seller-workspace-smoke.json','seller-workspace-evidence.zip','update-manifest.json');
 const hashes={};for(const name of assets)hashes[name]=hash(await readFile(path.join(directory,name)));
 await writeFile(path.join(directory,'checksums.sha256'),assets.map(name=>`${hashes[name]}  ${name}\n`).join(''));assets.push('checksums.sha256');hashes['checksums.sha256']=hash(await readFile(path.join(directory,'checksums.sha256')));
 let releases=api('releases?per_page=100').filter(r=>r.tag_name===tag);assert.ok(releases.length<=1);
 if(releases.length===0) {
  gh(['release','create',tag,'--repo',REPO,'--target',sha,'--draft','--prerelease','--title',`VN code ${tag.slice(1)} — Windows`,'--notes-file',path.join(directory,'release-notes.md')]);
  releases=api('releases?per_page=100').filter(r=>r.tag_name===tag);
 }
 assert.equal(releases.length,1);const release=releases[0];assert.equal(release.draft,true);assert.equal(release.prerelease,true);
 assert.equal(api(`git/ref/tags/${tag}`).object.sha,sha);validateReleaseAssets(release.assets,assets);
 for(const name of assets) {
  const existing=release.assets.find(a=>a.name===name);
  if(existing){assert.equal(existing.digest,`sha256:${hashes[name]}`);continue;}
  gh(['release','upload',tag,path.join(directory,name),'--repo',REPO]);
 }
 const uploaded=api(`releases/${release.id}`);validateReleaseAssets(uploaded.assets,assets,true);
 for(const name of assets){const asset=uploaded.assets.find(a=>a.name===name);assert.equal(asset.state,'uploaded');assert.equal(asset.digest,`sha256:${hashes[name]}`);}
 gh(['release','edit',tag,'--repo',REPO,'--draft=false','--prerelease','--latest=false','--notes-file',path.join(directory,'release-notes.md')]);
 console.log(JSON.stringify({url:`https://github.com/${REPO}/releases/tag/${tag}`,installer:`https://github.com/${REPO}/releases/download/${tag}/${filename}`,sha256:digest,sourceCommit:sha}));
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main(process.argv[2],process.argv[3],process.argv[4]).catch(error=>{console.error(error.message);process.exitCode=1;});
