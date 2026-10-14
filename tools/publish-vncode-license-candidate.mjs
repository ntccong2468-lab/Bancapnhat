import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {mkdtemp,readFile,writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {resolveReleaseVersion} from './release-version.mjs';
import {validatePublicationSource,validateJavaTestSummary} from './preview-source-guard.mjs';
import {validateBuild,validateNativeSmoke,validateSideBySideSmoke,validateUpgradeSmoke,validateReleaseAssets} from './publish-vncode-windows.mjs';
import {validateInstaller,validateGs1Smoke,validateWorkspaceSmoke} from './publish-vncode-preview.mjs';

const REPO='ntccong2468-lab/Bancapnhat';
const gh=args=>execFileSync('gh',args,{encoding:'utf8',maxBuffer:32*1024*1024});
const api=endpoint=>JSON.parse(gh(['api',`repos/${REPO}/${endpoint}`]));
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');

export function validateCandidateDraft(release,tag,sha,version) {
 assert.match(sha,/^[0-9a-f]{40}$/);
 assert.ok(tag.startsWith(`v${version}-license-test.`));
 assert.match(tag,/^v\d+\.\d+\.\d+-license-test\.[1-9]\d*$/);
 assert.equal(release.draft,true,'License testing cannot publish a release');
 assert.equal(release.prerelease,true);assert.equal(release.tag_name,tag);
 assert.equal(release.target_commitish,sha,'Draft must bind to the verified build');
}

async function main(runId,sha,tag) {
 assert.match(runId??'',/^[1-9]\d{0,15}$/);
 const {version}=await resolveReleaseVersion({root:process.cwd()});
 assert.ok(tag.startsWith(`v${version}-license-test.`));
 assert.match(tag,/^v\d+\.\d+\.\d+-license-test\.[1-9]\d*$/);
 assert.equal(execFileSync('git',['status','--porcelain'],{encoding:'utf8'}).trim(),'');
 validatePublicationSource(sha);
 const run=api(`actions/runs/${runId}`),workflow=api('actions/workflows/build-java.yml');
 validateBuild(run,sha,workflow.id);
 const artifacts=api(`actions/runs/${runId}/artifacts?per_page=100`).artifacts
  .filter(value=>value.name===`VN-code-${version}-Windows-x64`&&!value.expired);
 assert.equal(artifacts.length,1);
 const directory=await mkdtemp(path.join(tmpdir(),'vncode-license-candidate-'));
 gh(['run','download',runId,'--repo',REPO,'--name',artifacts[0].name,'--dir',directory]);
 const json=async name=>JSON.parse((await readFile(path.join(directory,name),'utf8')).replace(/^\uFEFF/,''));
 const filename=`VN-code-${version}-Windows-x64.exe`,checksum=`VN-code-${version}-Windows-x64.sha256`;
 const bytes=await readFile(path.join(directory,filename));
 const digest=(await readFile(path.join(directory,checksum),'utf8')).trim().split(/\s+/)[0];
 validateInstaller(bytes,digest);
 const native=await json('native-smoke.json');validateNativeSmoke(native,version);
 const coexistence=await json('side-by-side-smoke.json');validateSideBySideSmoke(coexistence,version);
 const gs1=await json('gs1-smoke.json');validateGs1Smoke(gs1,version);
 const workspace=await json('seller-workspace-smoke.json');validateWorkspaceSmoke(workspace,version,sha);
 const upgrades=[];
 for(const [name,from] of [['upgrade-smoke.json','1.1.34'],['upgrade-preview-smoke.json','1.2.0'],
  ['upgrade-latest-preview-smoke.json','1.2.1'],['upgrade-current-preview-smoke.json','1.2.2'],['upgrade-gs1-preview-smoke.json','1.3.0']]){
  const proof=await json(name);validateUpgradeSmoke(proof,version,from);upgrades.push(proof);
 }
 const log=gh(['run','view',runId,'--repo',REPO,'--log']);
 const javaTestSummary=validateJavaTestSummary(log),nodePassed=Number(log.match(/(?:#|ℹ)\s*tests\s+(\d+)/)?.[1]);
 assert.ok(nodePassed>=46);
 const info={appName:'VN code',version,sourceCommit:sha,githubRunId:Number(runId),githubRunUrl:run.html_url,
  javaTestSummary,nodePassed,installer:{filename,sha256:digest,bytes:bytes.length,architecture:'x86_64',authenticodeSigned:false},
  nativeSmoke:native,coexistenceSmoke:coexistence,gs1Smoke:gs1,workspaceSmoke:workspace,upgradeSmoke:upgrades,
  stage:'license-activation-test-candidate',publicRelease:false,licenseProductionReady:false,licenseActivation:'unrun'};
 await writeFile(path.join(directory,'build-info.json'),JSON.stringify(info,null,2)+'\n');
 const notes=`Bộ cài VNCode ${version} Windows x64 để chủ repo kiểm tra kích hoạt license. Java đã đóng gói. Đây là bản nháp, chưa phát hành công khai hoặc cập nhật tự động.\n\n`+
  `- ${javaTestSummary.passed} kiểm thử Java/JavaFX đạt, 0 lỗi, ${javaTestSummary.skipped} trường hợp chỉ áp dụng ngoài Windows bỏ qua; ${nodePassed} kiểm thử Node đạt.\n`+
  `- Launcher, GS1/DPAPI, 10 cửa hàng ở DPI 100/125/150, cài độc lập và nâng cấp giữ dữ liệu từ 1.1.34, 1.2.0, 1.2.1, 1.2.2, 1.3.0 đều đạt.\n`+
  `- Bộ cài chưa ký Authenticode. License thật và marketplace/CryptoPro/GS1/máy in thật chưa nghiệm thu; máy Windows 2 CPU/4 GiB chưa đo.\n`+
  `- Source: ${sha}. [CI](${run.html_url}).\n- SHA-256: ${digest}.\n\n`+
  `Đóng VNCode rồi chạy **${filename}**. Cho license thử 10 cửa hàng, đặt Cửa hàng=10 và Lưu giới hạn trên website cho đúng khách. Trong app nhập Gmail, xác minh rồi nhập mã license đã cấp; báo trạng thái kích hoạt hoặc mã lỗi.\n`;
 await writeFile(path.join(directory,'release-notes.md'),notes);
 let release=api('releases?per_page=100').find(value=>value.tag_name===tag);
 if(!release){gh(['release','create',tag,'--repo',REPO,'--target',sha,'--draft','--prerelease','--title',`VNCode ${version} — kiểm tra license Windows`,'--notes-file',path.join(directory,'release-notes.md')]);release=api('releases?per_page=100').find(value=>value.tag_name===tag);}
 validateCandidateDraft(release,tag,sha,version);
 const names=[filename,checksum,'build-info.json','release-notes.md'];validateReleaseAssets(release.assets,names);
 for(const name of names){
  const expected=hash(await readFile(path.join(directory,name))),existing=release.assets.find(value=>value.name===name);
  if(existing){assert.equal(existing.state,'uploaded');assert.equal(existing.digest,`sha256:${expected}`);}
  else gh(['release','upload',tag,path.join(directory,name),'--repo',REPO]);
 }
 gh(['release','edit',tag,'--repo',REPO,'--notes-file',path.join(directory,'release-notes.md')]);
 const uploaded=api(`releases/${release.id}`);validateCandidateDraft(uploaded,tag,sha,version);validateReleaseAssets(uploaded.assets,names,true);
 for(const name of names)assert.equal(uploaded.assets.find(value=>value.name===name).digest,`sha256:${hash(await readFile(path.join(directory,name)))}`);
 console.log(JSON.stringify({url:uploaded.html_url,draft:true,sourceCommit:sha,sha256:digest}));
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))
 main(process.argv[2],process.argv[3],process.argv[4]).catch(error=>{console.error(error.message);process.exitCode=1;});
