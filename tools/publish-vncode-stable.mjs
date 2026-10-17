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
import {validateLicenseReadiness} from './license-release-gate.mjs';
import {validateWorkspaceSmoke} from './publish-vncode-preview.mjs';
import {origin as verifiedJavaOrigin} from './reuse-verified-windows-java.mjs';

const REPO='ntccong2468-lab/Bancapnhat';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const json=bytes=>JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/,''));
const gh=args=>execFileSync('gh',args,{encoding:'utf8',maxBuffer:64*1024*1024});
const api=endpoint=>JSON.parse(gh(['api',`repos/${REPO}/${endpoint}`]));

export function validateStableAsset(asset,version,digest,size,draft=false) {
    assert.equal(asset.name,`VN-code-${version}-Windows-x64.exe`);
    assert.equal(asset.state,'uploaded');assert.equal(asset.digest,`sha256:${digest}`);assert.equal(asset.size,size);
    const base=`https://github.com/${REPO}/releases/download/`;
    if(draft) {
        assert.ok(asset.browser_download_url.startsWith(base));
        const segments=asset.browser_download_url.slice(base.length).split('/');
        assert.equal(segments.length,2);assert.equal(segments[1],asset.name);
        assert.ok(segments[0]===`v${version}`||/^untagged-[a-f0-9]+$/.test(segments[0]));
    } else assert.equal(asset.browser_download_url,`${base}v${version}/${asset.name}`);
}
export function validatePublishedStable(release,version) {
    assert.equal(release.tag_name,`v${version}`);assert.equal(release.draft,false);assert.equal(release.prerelease,false);
    assert.ok(release.body?.trim(),'Update notes are required');
    assert.ok(release.assets.some(asset=>asset.name===`VN-code-${version}-Windows-x64.exe`));
}
export function validateReusedJava(proof,version,sha,summary){
    assert.equal(proof.version,version);assert.equal(proof.sourceCommit,sha);
    assert.equal(proof.originRunId,verifiedJavaOrigin.run);assert.equal(proof.originSourceCommit,verifiedJavaOrigin.sha);
    assert.equal(proof.artifactId,verifiedJavaOrigin.artifact);assert.equal(proof.archiveSha256,verifiedJavaOrigin.digest);
    assert.equal(proof.applicationAndJavaTestInputsUnchanged,true);assert.equal(proof.javaExecutedInOriginRun,true);assert.equal(proof.nativeChecksMustRunAgain,true);
    assert.equal(proof.fixtureClassesCompiledWithoutChangingJar,true);assert.match(proof.applicationJarSha256,/^[a-f0-9]{64}$/);
    for(const key of ['tests','failures','errors','skipped'])assert.equal(proof.tests[key],summary[key]);
}

async function main(runId,sha,mode='prepare') {
    assert.match(runId??'',/^[1-9][0-9]{0,15}$/);assert.match(sha??'',/^[a-f0-9]{40}$/);
    assert.ok(['prepare','publish'].includes(mode));
    const {version}=await resolveReleaseVersion({root:process.cwd()});const tag=`v${version}`;
    validatePublicationSource(sha);
    const run=api(`actions/runs/${runId}`);validateBuild(run,sha,api('actions/workflows/build-java.yml').id);
    const status=json(await readFile(`docs/releases/VN-code-${version}-status.json`));
    if(mode==='publish') {
        assert.equal(status.complete,true,'Release functionality acceptance is incomplete');
        validateLicenseReadiness(status,json(await readFile('src/main/resources/com/vncode/app/license/trusted-keys.json')),
            json(await readFile(`docs/validation/VN-code-${version}-license-acceptance.json`)),version,sha);
    }
    const artifactName=`VN-code-${version}-Windows-x64`;
    assert.equal(api(`actions/runs/${runId}/artifacts?per_page=100`).artifacts.filter(a=>a.name===artifactName&&!a.expired).length,1);
    const directory=await mkdtemp(path.join(tmpdir(),'vncode-stable-'));
    gh(['run','download',runId,'--repo',REPO,'--name',artifactName,'--dir',directory]);
    const name=`VN-code-${version}-Windows-x64.exe`;const bytes=await readFile(path.join(directory,name));
    const digest=hash(bytes);assert.equal(digest,(await readFile(path.join(directory,`VN-code-${version}-Windows-x64.sha256`),'utf8')).trim().split(/\s+/)[0]);
    assert.ok(bytes.length>1024*1024);assert.equal(bytes.subarray(0,2).toString(),'MZ');const pe=bytes.readUInt32LE(0x3c);assert.equal(bytes.subarray(pe,pe+4).toString(),'PE\0\0');
    const native=json(await readFile(path.join(directory,'native-smoke.json')));validateNativeSmoke(native,version);
    validateSideBySideSmoke(json(await readFile(path.join(directory,'side-by-side-smoke.json'))),version);
    validateUpgradeSmoke(json(await readFile(path.join(directory,'upgrade-smoke.json'))),version);
    const proofFiles=['native-smoke.json','side-by-side-smoke.json','upgrade-smoke.json'];
    for(const [file,from] of [['upgrade-preview-smoke.json','1.2.0'],['upgrade-latest-preview-smoke.json','1.2.1'],
            ['upgrade-current-preview-smoke.json','1.2.2'],['upgrade-gs1-preview-smoke.json','1.3.0'],['upgrade-license-test-smoke.json','1.4.0']]) {
        validateUpgradeSmoke(json(await readFile(path.join(directory,file))),version,from);proofFiles.push(file);
    }
    if(version.localeCompare('1.4.6','en',{numeric:true})>0) {
        validateUpgradeSmoke(json(await readFile(path.join(directory,'upgrade-stable-146-smoke.json'))),version,'1.4.6');
        proofFiles.push('upgrade-stable-146-smoke.json');
    }
    for(const [from,file] of [['1.4.10','upgrade-stable-1410-smoke.json'],['1.4.11','upgrade-stable-1411-smoke.json'],['1.4.12','upgrade-stable-1412-smoke.json'],['1.4.13','upgrade-stable-1413-smoke.json'],['1.4.15','upgrade-stable-1415-smoke.json'],['1.4.16','upgrade-stable-1416-smoke.json']]) {
        if(version.localeCompare(from,'en',{numeric:true})>0){validateUpgradeSmoke(json(await readFile(path.join(directory,file))),version,from);proofFiles.push(file);}
    }
    const workspace=json(await readFile(path.join(directory,'seller-workspace-smoke.json')));validateWorkspaceSmoke(workspace,version,sha);
    const ui=json(await readFile(path.join(directory,'ui-layout.json')));
    assert.equal(ui.version,version);assert.equal(ui.liveSellerMutations,false);assert.equal(ui.layouts.length,version.localeCompare('1.4.13','en',{numeric:true})>0?288:272);
    assert.ok(ui.layouts.every(layout=>layout.horizontalButtonOverflow.length===0),'UI contains clipped actions');
    const gs1=json(await readFile(path.join(directory,'gs1-smoke.json')));
    assert.equal(gs1.result,'passed');assert.equal(gs1.version,version);assert.equal(gs1.liveGS1Mutations,false);
    assert.equal(gs1.catalogKeyDpapiRoundTrip,true);assert.equal(gs1.catalogKeyPurposeIsolated,true);
    proofFiles.push('seller-workspace-smoke.json','ui-layout.json','gs1-smoke.json');
    const logs=gh(['run','view',runId,'--repo',REPO,'--log']);
    const java=validateJavaTestSummary(logs);let javaVerificationOrigin;
    if(logs.includes('REUSED_WINDOWS_JAVA:')){
        javaVerificationOrigin=json(await readFile(path.join(directory,'java-verification-reuse.json')));
        validateReusedJava(javaVerificationOrigin,version,sha,java);proofFiles.push('java-verification-reuse.json');
    }
    const notes=(await readFile(`docs/releases/VN-code-${version}.md`,'utf8')).trim();assert.ok(notes.length>50);
    const info={version,sourceCommit:sha,runId:Number(runId),java,installer:{filename:name,bytes:bytes.length,sha256:digest},
        licenseProductionAcceptance:status.licenseIntegration.productionReady,native,javaVerificationOrigin,channel:'stable-after-publication',authenticodeSigned:false};
    await writeFile(path.join(directory,'build-info.json'),JSON.stringify(info,null,2)+'\n');
    await writeFile(path.join(directory,'release-notes.md'),notes+'\n');
    const names=[name,`VN-code-${version}-Windows-x64.sha256`,'build-info.json','release-notes.md',...proofFiles];
    // Ship the matching gateway deployment package with this desktop build.
    const portalVersion=json(await readFile('website/license-portal/package.json')).version;
    assert.match(portalVersion,/^\d+\.\d+\.\d+$/);
    const portalName=`VNCode-Portal-${portalVersion}-License-Network`;
    const artifacts=api(`actions/runs/${runId}/artifacts?per_page=100`).artifacts;
    assert.equal(artifacts.filter(a=>a.name===portalName&&!a.expired).length,1);
    gh(['run','download',runId,'--repo',REPO,'--name',portalName,'--dir',directory]);
    const portalBytes=await readFile(path.join(directory,portalName+'.zip'));
    assert.equal(portalBytes.subarray(0,2).toString(),'PK');assert.ok(portalBytes.length>1000&&portalBytes.length<10*1024*1024);
    assert.equal(hash(portalBytes),(await readFile(path.join(directory,portalName+'.sha256'),'utf8')).trim().split(/\s+/)[0]);
    names.push(portalName+'.zip',portalName+'.sha256');
    const digests={};for(const file of names)digests[file]=hash(await readFile(path.join(directory,file)));
    const refs=api(`git/matching-refs/tags/${tag}`).filter(item=>item.ref===`refs/tags/${tag}`);
    assert.ok(refs.length<=1);
    if(refs.length) {
        assert.equal(refs[0].object.type,'commit');assert.equal(refs[0].object.sha,sha,'Never move an existing release tag');
    } else {
        const request=path.join(directory,'tag-ref.json');await writeFile(request,JSON.stringify({ref:`refs/tags/${tag}`,sha}));
        gh(['api','-X','POST',`repos/${REPO}/git/refs`,'--input',request]);
    }
    let release=api('releases?per_page=100').find(r=>r.tag_name===tag);
    if(!release) {
        const request=path.join(directory,'create-release.json');
        await writeFile(request,JSON.stringify({tag_name:tag,target_commitish:sha,draft:true,prerelease:false,name:`VNCode ${version}`,body:notes}));
        release=JSON.parse(gh(['api','-X','POST',`repos/${REPO}/releases`,'--input',request]));
    }
    assert.equal(release.draft,true,'Never overwrite an already published version');assert.equal(release.prerelease,false);
    assert.equal(api(`git/ref/tags/${tag}`).object.sha,sha);validateReleaseAssets(release.assets,names);
    for(const file of names) {
        const present=release.assets.find(a=>a.name===file);
        if(present){assert.equal(present.digest,`sha256:${digests[file]}`);assert.equal(present.state,'uploaded');}
        else gh(['release','upload',tag,path.join(directory,file),'--repo',REPO]);
    }
    gh(['release','edit',tag,'--repo',REPO,'--title',`VNCode ${version}`,'--notes-file',path.join(directory,'release-notes.md')]);
    const ready=api(`releases/${release.id}`);validateReleaseAssets(ready.assets,names,true);
    validateStableAsset(ready.assets.find(a=>a.name===name),version,digest,bytes.length,ready.draft);
    if(mode==='publish') {
        const request=path.join(directory,'publish.json');await writeFile(request,JSON.stringify({draft:false,prerelease:false,make_latest:'true'}));
        gh(['api','-X','PATCH',`repos/${REPO}/releases/${release.id}`,'--input',request]);
        validatePublishedStable(api('releases/latest'),version);
        validateStableAsset(api(`releases/${release.id}`).assets.find(a=>a.name===name),version,digest,bytes.length);
    }
    console.log(JSON.stringify({version,mode,url:ready.html_url,sourceCommit:sha,sha256:digest,published:mode==='publish'}));
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))await main(...process.argv.slice(2));
