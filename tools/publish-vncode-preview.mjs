import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {mkdtemp,readFile,writeFile,copyFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {validateBuild,validateNativeSmoke,validateSideBySideSmoke,validateUpgradeSmoke,validateReleaseAssets} from './publish-vncode-windows.mjs';
const REPO='ntccong2468-lab/Bancapnhat';
const VERSION='1.2.0';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const gh=args=>execFileSync('gh',args,{encoding:'utf8',maxBuffer:32*1024*1024});
const api=endpoint=>JSON.parse(gh(['api',`repos/${REPO}/${endpoint}`]));
const json=bytes=>JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/,''));
export function validatePreview(status,tag) {
 assert.match(tag,/^v1\.2\.0-preview\.[1-9][0-9]*$/);
 assert.equal(status.version,VERSION);assert.equal(status.complete,false);
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
async function main(runId,tag) {
 assert.match(runId??'',/^[1-9][0-9]{0,15}$/);
 const status=json(await readFile('docs/releases/VN-code-1.2.0-status.json'));validatePreview(status,tag);
 assert.equal(execFileSync('git',['status','--porcelain'],{encoding:'utf8'}).trim(),'','Require a clean checkout.');
 const sha=execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim();
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
 const log=gh(['run','view',runId,'--repo',REPO,'--log']).replace(/\x1b\[[0-9;]*m/g,'');
 const javaTests=Math.max(0,...[...log.matchAll(/Tests run: (\d+), Failures: 0, Errors: 0, Skipped: 0/g)].map(x=>Number(x[1])));
 const nodeTests=Number(log.match(/(?:#|ℹ)\s*tests\s+(\d+)/)?.[1]??0);
 assert.ok(javaTests>=585);assert.ok(nodeTests>=27);
 await copyFile(path.join(directory,original),path.join(directory,filename));
 const info={appName:'VN code',version:VERSION,releaseTag:tag,stage:'preview',complete:false,
  sourceCommit:sha,repository:REPO,githubRunId:Number(runId),javaTests,nodeTests,
  installer:{filename,sha256:digest,bytes:bytes.length,architecture:'x86_64',authenticodeSigned:false},
  liveMarketplaceMutations:false,signedUpdateManifestPublished:false,blocked:status.blocked,nativeSmoke:native,upgradeSmoke:upgrade};
 await writeFile(path.join(directory,'build-info.json'),JSON.stringify(info,null,2)+'\n');
 await writeFile(path.join(directory,'feature-status.json'),JSON.stringify(status,null,2)+'\n');
 const notes=`# VN code 1.2.0 — bản thử nghiệm\n\nBộ cài nâng cấp cho cùng ứng dụng VN code Windows, giữ định danh và dữ liệu VN code 1.1.34. Đây chưa phải bản hoàn thiện tương đương toàn bộ WCode 1.2.0.\n\n`+
  `## Đã có trong bản này\n\n- Tin tức/Dashboard, cải tiến giao diện và giữ mẫu tem đã lưu.\n- WB: biểu mẫu giao hàng, lựa chọn theo shop, ngày phải chọn lại, đọc lại thông số sau khi lưu; không tự lặp thao tác ghi khi timeout.\n- Đồng bộ supply WB theo lô 20; bảng Đang giao phân trang và bỏ supply đã biết là rỗng.\n- Module TN VED: 21 phần gốc, tìm Việt/Nga offline, cây danh mục, nhập CSV/JSON có xác nhận nguồn/ngày hiệu lực và sao lưu. Mã chi tiết chưa nhập sẽ báo Chưa tải dữ liệu.\n\n`+
  `## Chưa hoàn tất\n\n${status.blocked.map(x=>`- ${x}`).join('\n')}\n\n`+
  `## Cài đặt và kiểm tra\n\nĐóng VN code rồi chạy **${filename}** bên dưới; Java đã được đóng gói. Bộ cài chưa ký Authenticode. Không phát hành manifest và không đưa bản thử này vào cập nhật tự động. Bản hoàn thiện tiếp theo sẽ dùng số phiên bản Windows cao hơn để tiếp tục nâng cấp cùng ứng dụng.\n\n`+
  `- ${javaTests} kiểm thử Java/JavaFX và ${nodeTests} kiểm thử Node đạt trên Windows; 0 lỗi hoặc bỏ qua.\n- Launcher, kiểm tra cài song song và nâng cấp VN code 1.1.34 giữ dữ liệu đã đạt.\n- Chưa nghiệm thu với credential shop thật, nhà cung cấp AI hoặc máy in thật; không có thao tác ghi marketplace thật trong CI.\n- [CI](https://github.com/${REPO}/actions/runs/${runId}); commit ${sha}.\n- SHA-256: ${digest}.\n`;
 await writeFile(path.join(directory,'release-notes.md'),notes);
 const assets=[filename,'build-info.json','feature-status.json','native-smoke.json','side-by-side-smoke.json','upgrade-smoke.json','release-notes.md'];
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
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main(process.argv[2],process.argv[3]).catch(error=>{console.error(error.message);process.exitCode=1;});
