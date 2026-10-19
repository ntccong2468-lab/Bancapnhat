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
export function validateFixtureRecovery(proof,version,sha,summary){
    assert.equal(version,'1.4.18');assert.equal(proof.kind,'fixture-cleanup-only-recovery');
    assert.equal(proof.version,version);assert.equal(proof.sourceCommit,sha);
    assert.equal(proof.applicationSourceCommit,'62a5d0ea5f1abaf0ea638319b0b18231dfd0c238');
    assert.equal(proof.originRunId,39099056215);assert.equal(proof.originArtifactId,12126526148);
    assert.equal(proof.originArchiveSha256,'ae82ace1389c81bac696ba57f1d53029a99eaa2ec10d02a5b3cedb6d66a97d65');
    assert.deepEqual(proof.originSuite,{tests:1748,failures:0,errors:1,skipped:2});assert.equal(proof.originNamedTests,1747);
    for(const key of ['originalContextFailureRetainedInProvenance','failedFixtureClassRerunPassed','newHandleCleanupTestPassed','applicationAndDependenciesUnchanged','originalAssertionsUnchanged','nativeChecksMustRunAgain','packageCompiledAfterFocusedTests'])assert.equal(proof[key],true,key);
    assert.ok(proof.freshClasses.includes('com.vncode.app.ui.FxmlSmokeTest'));
    assert.ok(proof.freshClasses.includes('com.vncode.app.testsupport.FinanceTestCleanupTest'));
    assert.ok(proof.freshTestsExecuted>=56&&proof.reusedPassingTests>0);
    assert.equal(proof.freshTestsExecuted+proof.reusedPassingTests,1748);
    assert.match(proof.freshlyPackagedJarSha256,/^[a-f0-9]{64}$/);
    for(const key of ['tests','failures','errors','skipped'])assert.equal(proof.tests[key],summary[key]);
}

// This one release was requested after the owner was told which live checks remain.
// Retain the normal production-acceptance gate and all source/artifact/native checks.
export function validateOwnerApproved1418(decision,status,version,sha,runId) {
    assert.equal(version,'1.4.18');assert.equal(sha,'e77a746128fa51f183a2a193275b6b1ef894c251');
    assert.equal(String(runId),'39102138545');
    assert.equal(decision.version,version);assert.equal(decision.sourceCommit,sha);assert.equal(String(decision.runId),String(runId));
    assert.equal(decision.ownerRequestedPublication,true);assert.equal(decision.ownerInstruction,'xuất bản cập nhật mới');
    assert.equal(decision.ownerInformedOfUnverifiedChecks,true);assert.equal(decision.standardFullAcceptanceClaimed,false);
    assert.equal(decision.productionLicenseAcceptance,false);assert.equal(decision.enterpriseCryptoProSigningAccepted,false);
    assert.equal(decision.marketplaceLiveGtinWritesAccepted,false);
    assert.match(decision.installerSha256,/^[a-f0-9]{64}$/);assert.ok(Number.isSafeInteger(decision.installerBytes)&&decision.installerBytes>1024*1024);
    assert.equal(status.version,version);assert.equal(status.complete,false);
    assert.equal(status.licenseIntegration.productionReady,false);assert.equal(status.licenseIntegration.changedInThisVersion,false);
    assert.equal(status.validation.liveCryptoProSigningThisVersion,false);
    assert.equal(status.validation.liveMarketplaceGtinWriteThisVersion,false);
    assert.equal(status.validation.productionLicenseVerified,false);
    assert.equal(status.validation.windowsBuild.state,'passed');assert.equal(String(status.validation.windowsBuild.runId),String(runId));
    assert.equal(status.validation.registrationPromptReview.suggestionsReviewedInTable,true);
    assert.equal(status.validation.registrationPromptReview.applyBelowTableVerified,true);
    assert.equal(status.validation.registrationPromptReview.ambiguousValuesNotInvented,true);
}

// The owner requested this release after the API-vs-desktop acceptance difference
// was reported. Bind only this exact fresh Windows build; do not relax publish.
export function validateOwnerRequest1419(decision,version,sha,runId) {
    assert.equal(version,'1.4.19');assert.equal(sha,'d25718c6a69f94f08d9515d36f7444650b1eebd1');
    assert.equal(String(runId),'39114287816');
    assert.equal(decision.version,version);assert.equal(decision.sourceCommit,sha);assert.equal(String(decision.runId),String(runId));
    assert.equal(decision.ownerRequestedPublication,true);
    assert.equal(decision.ownerInstruction,'phát hành bản mới nhưng báo cáo trước cho tôi những gì chỉnh sửa tôi giao đã sửa chưa');
    assert.equal(decision.ownerInformedOfUnverifiedChecks,true);assert.equal(decision.requestReportDeliveredBeforePublication,true);
    assert.equal(decision.standardFullAcceptanceClaimed,false);assert.equal(decision.productionLicenseAcceptance,false);
    assert.equal(decision.enterpriseCryptoProSigningAccepted,false);assert.equal(decision.licensedDesktopGtinWriteAccepted,false);
    assert.equal(decision.liveApiWbGtinWriteVerified,true);
}
export function validateOwnerApproved1419(decision,status,version,sha,runId) {
    validateOwnerRequest1419(decision,version,sha,runId);
    assert.match(decision.installerSha256,/^[a-f0-9]{64}$/);assert.ok(Number.isSafeInteger(decision.installerBytes)&&decision.installerBytes>1024*1024);
    assert.equal(status.version,version);assert.equal(status.complete,false);assert.equal(status.databaseSchema,6);
    assert.equal(status.licenseIntegration.productionReady,false);assert.equal(status.licenseIntegration.changedInThisVersion,false);
    assert.equal(status.liveWbApiGtinWriteVerified,true);assert.equal(status.liveLicensedDesktopGtinWriteVerified,false);
    assert.equal(status.validation.productionLicenseVerified,false);assert.equal(status.validation.liveCryptoProSigningThisVersion,false);
    assert.equal(status.validation.liveLicensedDesktopGtinWriteThisVersion,false);assert.equal(status.validation.liveApiWbGtinWriteThisVersion,true);
    assert.equal(status.validation.windowsBuild.state,'passed');assert.equal(String(status.validation.windowsBuild.runId),String(runId));
    assert.equal(status.validation.windowsBuild.sourceCommit,sha);assert.equal(status.validation.windowsBuild.freshFullJavaExecutionRequired,true);
    assert.equal(status.validation.windowsBuild.upgradeFrom1418Passed,true);
    assert.equal(status.requestReport,'VN-code-1.4.19-request-report.md');
}

// The continuing owner request names 1.5.0. Publish its tested installer with the
// disclosed limits; this receipt must never be used as full live acceptance.
export function validateOwnerRequested150(decision,status,version,sha,runId) {
    assert.equal(version,'1.5.0');assert.equal(sha,'2eea069123737e2c9f7bcddcecaa51f21a1645dc');
    assert.equal(String(runId),'39130866288');
    assert.equal(decision.version,version);assert.equal(decision.sourceCommit,sha);assert.equal(String(decision.runId),String(runId));
    assert.equal(decision.phase,'verified-for-publication');assert.equal(decision.ownerRequestedPublication,true);
    assert.equal(decision.ownerInstruction,'các mục vừa chỉnh sửa cũng đưa vào bản cập nhật mới, bản này có tên 1.5.0');
    assert.equal(decision.requestReportDeliveredBeforePublication,true);assert.equal(decision.unverifiedChecksDisclosedBeforePublication,true);
    assert.equal(decision.standardFullAcceptanceClaimed,false);
    for(const field of ['productionLicenseAcceptance','enterpriseCryptoProSigningAccepted','licensedDesktopGtinWriteAccepted',
            'liveApiWbGtinWriteThisVersion','liveApiOzonGtinWriteThisVersion','liveCatalogCardCreatedThisVersion','bulkMarketplaceKizValidationOnImport'])
        assert.equal(decision[field],false,field);
    assert.match(decision.installerSha256,/^[a-f0-9]{64}$/);assert.ok(Number.isSafeInteger(decision.installerBytes)&&decision.installerBytes>1024*1024);
    assert.equal(status.version,version);assert.equal(status.complete,false);assert.equal(status.databaseSchema,6);
    assert.equal(status.licenseIntegration.productionReady,false);assert.equal(status.licenseIntegration.changedInThisVersion,false);
    for(const field of ['productionLicenseVerified','liveCryptoProSigningThisVersion','liveLicensedDesktopGtinWriteThisVersion',
            'liveApiWbGtinWriteThisVersion','liveApiOzonGtinWriteThisVersion','liveCatalogCardCreatedThisVersion'])
        assert.equal(status.validation[field],false,field);
    assert.equal(status.bulkMarketplaceKizValidationOnImport,false);
    const build=status.validation.windowsBuild;
    assert.equal(build.state,'passed');assert.equal(String(build.runId),String(runId));assert.equal(build.sourceCommit,sha);
    assert.equal(build.freshFullJavaExecutionRequired,true);assert.equal(build.upgradeFrom1419Passed,true);
    assert.deepEqual(decision.windowsJava,{tests:1792,failures:0,errors:0,skipped:2,passed:1790});
    for(const field of ['tests','failures','errors','skipped','passed'])assert.equal(build.fullJavaSuite[field],decision.windowsJava[field]);
    assert.equal(status.requestReport,'VN-code-1.5.0-request-report.md');
}

// Explicit owner request for this exact 1.5.1 application build. The receipt
// records missing live acceptance without changing the standard publish gate.
export function validateOwnerRequest151(decision,status,version,sha,runId) {
    assert.equal(version,'1.5.1');assert.equal(sha,'96e20614882488a2d60319fc29f4ca62b6cb9b81');
    assert.equal(String(runId),'39286749991');
    assert.equal(decision.version,version);assert.equal(decision.sourceCommit,sha);assert.equal(String(decision.runId),String(runId));
    assert.ok(['authorized-awaiting-verified-build','verified-for-publication'].includes(decision.phase));
    assert.equal(decision.ownerRequestedPublication,true);
    assert.equal(decision.ownerInstruction,'sau khi xong hãy đưa bản mới này lên latest để thông báo xuống app cho seller cập nhật');
    assert.equal(decision.unverifiedChecksDisclosedBeforePublication,true);assert.equal(decision.standardFullAcceptanceClaimed,false);
    for(const field of ['productionLicenseAcceptance','enterpriseCryptoProSigningAccepted','licensedDesktopGtinWriteAccepted',
            'liveApiWbGtinWriteThisVersion','liveApiOzonGtinWriteThisVersion','liveCatalogCardCreatedThisVersion','liveCatalogCardRepairedThisVersion'])assert.equal(decision[field],false,field);
    assert.equal(status.version,version);assert.equal(status.complete,false);assert.equal(status.databaseSchema,6);
    assert.equal(status.licenseIntegration.productionReady,false);assert.equal(status.licenseIntegration.changedInThisVersion,false);
    for(const field of ['productionLicenseVerified','liveCryptoProSigningThisVersion','liveLicensedDesktopGtinWriteThisVersion',
            'liveApiWbGtinWriteThisVersion','liveApiOzonGtinWriteThisVersion','liveCatalogCardCreatedThisVersion','liveCatalogCardRepairedThisVersion'])assert.equal(status.validation[field],false,field);
    const build=status.validation.windowsBuild;
    assert.equal(String(build.runId),String(runId));assert.equal(build.sourceCommit,sha);
    assert.equal(build.freshFullJavaExecutionRequired,true);assert.equal(status.requestReport,'VN-code-1.5.1-request-report.md');
    if(decision.phase==='authorized-awaiting-verified-build') {
        assert.equal(build.state,'running');assert.equal(decision.installerSha256,null);assert.equal(decision.installerBytes,null);
        assert.equal(decision.windowsJava,null);assert.equal(build.fullJavaSuite,undefined);assert.notEqual(build.upgradeFrom150Passed,true);
    }
}
export function validateOwnerRequested151(decision,status,version,sha,runId) {
    validateOwnerRequest151(decision,status,version,sha,runId);
    assert.equal(decision.phase,'verified-for-publication');
    assert.match(decision.installerSha256,/^[a-f0-9]{64}$/);assert.ok(Number.isSafeInteger(decision.installerBytes)&&decision.installerBytes>1024*1024);
    const build=status.validation.windowsBuild;
    assert.equal(build.state,'passed');assert.equal(build.upgradeFrom150Passed,true);
    assert.ok(decision.windowsJava.tests>=1924);assert.equal(decision.windowsJava.failures,0);assert.equal(decision.windowsJava.errors,0);
    assert.equal(decision.windowsJava.passed,decision.windowsJava.tests-decision.windowsJava.skipped);
    assert.deepEqual(build.fullJavaSuite,decision.windowsJava);
}

async function main(runId,sha,mode='prepare') {
    assert.match(runId??'',/^[1-9][0-9]{0,15}$/);assert.match(sha??'',/^[a-f0-9]{40}$/);
    assert.ok(['prepare','publish','owner-approved-1418','owner-approved-1419','owner-requested-150','owner-requested-151'].includes(mode));
    const {version}=await resolveReleaseVersion({root:process.cwd()});const tag=`v${version}`;
    validatePublicationSource(sha);
    let run=api(`actions/runs/${runId}`);
    const workflowId=api('actions/workflows/build-java.yml').id;
    const status=json(await readFile(`docs/releases/VN-code-${version}-status.json`));
    let ownerDecision,pendingOwnerBuild=false;
    if(mode==='owner-approved-1418') {
        ownerDecision=json(await readFile('docs/validation/2026-10-18-vncode-1418-owner-publication.json'));
        validateOwnerApproved1418(ownerDecision,status,version,sha,runId);
    }
    if(mode==='owner-approved-1419') {
        ownerDecision=json(await readFile('docs/validation/2026-10-18-vncode-1419-owner-publication.json'));
        validateOwnerRequest1419(ownerDecision,version,sha,runId);
        pendingOwnerBuild=ownerDecision.phase==='authorized-awaiting-verified-build';
        if(pendingOwnerBuild) {
            assert.equal(ownerDecision.installerSha256,null);assert.equal(ownerDecision.installerBytes,null);
            assert.equal(status.validation.windowsBuild.sourceCommit,sha);assert.equal(String(status.validation.windowsBuild.runId),String(runId));
        } else validateOwnerApproved1419(ownerDecision,status,version,sha,runId);
        assert.ok((await readFile('docs/releases/'+status.requestReport,'utf8')).includes('Chưa hoàn tất'));
    }
    if(mode==='owner-requested-150') {
        ownerDecision=json(await readFile('docs/validation/2026-10-18-vncode-150-owner-publication.json'));
        validateOwnerRequested150(ownerDecision,status,version,sha,runId);
        assert.ok((await readFile('docs/releases/'+status.requestReport,'utf8')).includes('Chưa hoàn tất'));
    }
    if(mode==='owner-requested-151') {
        ownerDecision=json(await readFile('docs/validation/2026-10-19-vncode-151-owner-publication.json'));
        validateOwnerRequest151(ownerDecision,status,version,sha,runId);
        pendingOwnerBuild=ownerDecision.phase==='authorized-awaiting-verified-build';
        if(!pendingOwnerBuild)validateOwnerRequested151(ownerDecision,status,version,sha,runId);
        assert.ok((await readFile('docs/releases/'+status.requestReport,'utf8')).includes('Chưa hoàn tất'));
    }
    if(pendingOwnerBuild) {
        const deadline=Date.now()+90*60*1000;
        while(run.status!=='completed') {
            assert.equal(run.head_sha,sha);assert.equal(run.workflow_id,workflowId);assert.equal(run.repository.full_name,REPO);
            assert.ok(Date.now()<deadline,'Timed out waiting for the authorized Windows build');
            console.log(`Waiting for authorized Windows build ${runId}; no release mutation has started.`);
            await new Promise(resolve=>setTimeout(resolve,30000));run=api(`actions/runs/${runId}`);
        }
    }
    validateBuild(run,sha,workflowId);
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
    if(ownerDecision&&!pendingOwnerBuild){assert.equal(digest,ownerDecision.installerSha256);assert.equal(bytes.length,ownerDecision.installerBytes);}
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
    if(version.localeCompare('1.4.18','en',{numeric:true})>0) {
        validateUpgradeSmoke(json(await readFile(path.join(directory,'upgrade-stable-1418-smoke.json'))),version,'1.4.18');
        proofFiles.push('upgrade-stable-1418-smoke.json');
    }
    if(version.localeCompare('1.4.19','en',{numeric:true})>0) {
        validateUpgradeSmoke(json(await readFile(path.join(directory,'upgrade-stable-1419-smoke.json'))),version,'1.4.19');
        proofFiles.push('upgrade-stable-1419-smoke.json');
    }
    if(version.localeCompare('1.5.0','en',{numeric:true})>0) {
        validateUpgradeSmoke(json(await readFile(path.join(directory,'upgrade-stable-150-smoke.json'))),version,'1.5.0');
        proofFiles.push('upgrade-stable-150-smoke.json');
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
    if(mode==='owner-requested-150') {
        assert.ok(!logs.includes('REUSED_WINDOWS_JAVA:')&&!logs.includes('RECONCILED_WINDOWS_JAVA:'),'1.5.0 requires a fresh full Windows Java run');
        assert.deepEqual(java,ownerDecision.windowsJava);
    }
    if(mode==='owner-requested-151') {
        assert.ok(!logs.includes('REUSED_WINDOWS_JAVA:')&&!logs.includes('RECONCILED_WINDOWS_JAVA:'),'1.5.1 requires a fresh full Windows Java run');
        if(!pendingOwnerBuild)assert.deepEqual(java,ownerDecision.windowsJava);
    }
    if(logs.includes('REUSED_WINDOWS_JAVA:')){
        javaVerificationOrigin=json(await readFile(path.join(directory,'java-verification-reuse.json')));
        validateReusedJava(javaVerificationOrigin,version,sha,java);proofFiles.push('java-verification-reuse.json');
    }
    if(logs.includes('RECONCILED_WINDOWS_JAVA:')){
        assert.equal(javaVerificationOrigin,undefined);
        javaVerificationOrigin=json(await readFile(path.join(directory,'java-fixture-recovery.json')));
        validateFixtureRecovery(javaVerificationOrigin,version,sha,java);proofFiles.push('java-fixture-recovery.json');
    }
    if(pendingOwnerBuild) {
        // Every build, native, upgrade, Java and artifact check above has passed.
        // Fill the receipt with observed bytes; do not turn unknown live acceptance into success.
        assert.equal(javaVerificationOrigin,undefined,'This authorization requires a fresh full Java run');
        ownerDecision={...ownerDecision,phase:'verified-for-publication',installerSha256:digest,installerBytes:bytes.length,
            windowsJava:java,verifiedNativeProofs:proofFiles};
        if(mode==='owner-requested-151') {
            status.validation.windowsBuild={...status.validation.windowsBuild,state:'passed',upgradeFrom150Passed:true,fullJavaSuite:java};
            validateOwnerRequested151(ownerDecision,status,version,sha,runId);
        } else {
            status.validation.windowsBuild={...status.validation.windowsBuild,state:'passed',upgradeFrom1418Passed:true};
            validateOwnerApproved1419(ownerDecision,status,version,sha,runId);
        }
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
    if(mode==='owner-approved-1418') {
        const live=json(await readFile('docs/validation/2026-10-18-vncode-real-card.json'));
        assert.equal(live.realCardReadBack,true);assert.equal(live.listedInOwnProductList,true);
        assert.equal(live.realGtinsAllocated,1);assert.equal(live.cardsCreated,1);
        assert.equal(live.fixtureLicenseUsedForLiveRequests,false);
        await writeFile(path.join(directory,'release-acceptance.json'),JSON.stringify(ownerDecision,null,2)+'\n');
        await writeFile(path.join(directory,'catalog-live-card-test.json'),JSON.stringify(live,null,2)+'\n');
        names.push('release-acceptance.json','catalog-live-card-test.json');
    }
    if(mode==='owner-approved-1419') {
        const live=json(await readFile('docs/validation/2026-10-18-vncode-1419-wb-live-gtin.json'));
        assert.equal(live.version,version);assert.equal(live.result,'passed');assert.equal(live.mutation.requestCount,1);
        assert.equal(live.mutation.http,200);assert.equal(live.mutation.error,false);
        for(const field of ['oldBarcodesPreserved','otherVariantsUnchanged','otherFieldsPreserved','photosPreserved'])assert.equal(live.readback[field],true);
        assert.equal(live.independentPersistenceCheck.persistedOnWB,true);assert.equal(live.license.fixtureUsedForLiveMutation,false);
        assert.equal(live.license.productionDesktopAcceptance,'not_exercised');
        await writeFile(path.join(directory,'release-acceptance.json'),JSON.stringify(ownerDecision,null,2)+'\n');
        await writeFile(path.join(directory,'wb-live-gtin-test.json'),JSON.stringify(live,null,2)+'\n');
        names.push('release-acceptance.json','wb-live-gtin-test.json');
    }
    if(mode==='owner-requested-150') {
        await writeFile(path.join(directory,'release-acceptance.json'),JSON.stringify(ownerDecision,null,2)+'\n');
        for(const [source,name] of [['docs/releases/VN-code-1.5.0-request-report.md','request-report.md'],
                ['docs/validation/2026-10-18-vncode-150-live-read.json','catalog-marketplace-live-read.json'],
                ['docs/validation/2026-10-18-vncode-150-catalog-details.json','catalog-live-details.json']]) {
            await writeFile(path.join(directory,name),await readFile(source));names.push(name);
        }
        names.push('release-acceptance.json');
    }
    if(mode==='owner-requested-151') {
        await writeFile(path.join(directory,'release-acceptance.json'),JSON.stringify(ownerDecision,null,2)+'\n');
        await writeFile(path.join(directory,'request-report.md'),await readFile('docs/releases/VN-code-1.5.1-request-report.md'));
        names.push('release-acceptance.json','request-report.md');
    }
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
    const publishing=mode==='publish'||mode==='owner-approved-1418'||mode==='owner-approved-1419'||mode==='owner-requested-150'||mode==='owner-requested-151';
    if(publishing) {
        const request=path.join(directory,'publish.json');await writeFile(request,JSON.stringify({draft:false,prerelease:false,make_latest:'true'}));
        gh(['api','-X','PATCH',`repos/${REPO}/releases/${release.id}`,'--input',request]);
        validatePublishedStable(api('releases/latest'),version);
        validateStableAsset(api(`releases/${release.id}`).assets.find(a=>a.name===name),version,digest,bytes.length);
    }
    console.log(JSON.stringify({version,mode,url:api(`releases/${release.id}`).html_url,sourceCommit:sha,sha256:digest,published:publishing}));
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))await main(...process.argv.slice(2));
