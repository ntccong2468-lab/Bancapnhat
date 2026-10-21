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
import {plan151,validateReuse151} from './reuse-verified-windows-java-151.mjs';

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
    if(version==='1.5.1'){validateReuse151(proof,sha,summary);return;}
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
    assert.equal(version,'1.5.1');assert.equal(sha,'f9da70ca98734b8c5daed0a6ef49b38df9723f54');
    assert.equal(String(runId),'39294782641');
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
    if(build.freshFullJavaExecutionRequired===false)assert.deepEqual(build.javaExecutionPlan,plan151);else assert.equal(build.freshFullJavaExecutionRequired,true);assert.equal(status.requestReport,'VN-code-1.5.1-request-report.md');
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
    if(build.freshFullJavaExecutionRequired===false){assert.equal(decision.freshJavaExecutedInThisRun,false);validateReuse151(decision.javaVerificationOrigin,sha,decision.windowsJava);}
}

// Owner-authorized 1.5.2: fresh full Java and all native checks remain mandatory.
export function validateOwnerRequest152(decision,status,version,sha,runId) {
    assert.equal(version,'1.5.2');assert.match(sha,/^[a-f0-9]{40}$/);
    assert.match(String(runId),/^[1-9][0-9]{0,15}$/);
    assert.equal(decision.version,version);assert.equal(decision.sourceCommit,sha);assert.equal(String(decision.runId),String(runId));
    assert.ok(['authorized-awaiting-verified-build','verified-for-publication'].includes(decision.phase));
    assert.equal(decision.ownerRequestedPublication,true);
    assert.equal(decision.ownerInstruction,'xong phần này thì đóng gói bản cập nhật mới lên latest để phát hành xuống app.');
    assert.equal(decision.unverifiedChecksDisclosedBeforePublication,true);assert.equal(decision.standardFullAcceptanceClaimed,false);
    for(const field of ['productionLicenseAcceptance','enterpriseCryptoProSigningAccepted','licensedDesktopGtinWriteAccepted',
            'liveApiWbGtinWriteThisVersion','liveApiOzonGtinWriteThisVersion','liveCatalogCardCreatedThisVersion','liveCatalogCardRepairedThisVersion'])assert.equal(decision[field],false,field);
    assert.equal(status.version,version);assert.equal(status.complete,false);assert.equal(status.databaseSchema,6);
    assert.equal(status.licenseIntegration.productionReady,false);assert.equal(status.licenseIntegration.changedInThisVersion,false);
    for(const field of ['productionLicenseVerified','liveCryptoProSigningThisVersion','liveLicensedDesktopGtinWriteThisVersion',
            'liveApiWbGtinWriteThisVersion','liveApiOzonGtinWriteThisVersion','liveCatalogCardCreatedThisVersion','liveCatalogCardRepairedThisVersion'])assert.equal(status.validation[field],false,field);
    const build=status.validation.windowsBuild;
    assert.equal(String(build.runId),String(runId));assert.equal(build.sourceCommit,sha);
    assert.equal(build.freshFullJavaExecutionRequired,true);assert.equal(status.requestReport,'VN-code-1.5.2-request-report.md');
    if(decision.phase==='authorized-awaiting-verified-build') {
        assert.equal(build.state,'running');assert.equal(decision.installerSha256,null);assert.equal(decision.installerBytes,null);
        assert.equal(decision.windowsJava,null);assert.equal(build.fullJavaSuite,undefined);assert.notEqual(build.upgradeFrom151Passed,true);
    }
}
export function validateOwnerRequested152(decision,status,version,sha,runId) {
    validateOwnerRequest152(decision,status,version,sha,runId);
    assert.equal(decision.phase,'verified-for-publication');
    assert.match(decision.installerSha256,/^[a-f0-9]{64}$/);assert.ok(Number.isSafeInteger(decision.installerBytes)&&decision.installerBytes>1024*1024);
    const build=status.validation.windowsBuild;
    assert.equal(build.state,'passed');assert.equal(build.upgradeFrom151Passed,true);
    assert.ok(decision.windowsJava.tests>=1940);assert.equal(decision.windowsJava.failures,0);assert.equal(decision.windowsJava.errors,0);
    assert.equal(decision.windowsJava.passed,decision.windowsJava.tests-decision.windowsJava.skipped);
    assert.deepEqual(build.fullJavaSuite,decision.windowsJava);
}

// Owner-authorized 1.5.3: fresh full Java and all native checks remain mandatory.
export function validateOwner153UpgradeProof(proof,version,installerDigest) {
    assert.equal(version,'1.5.3');
    validateUpgradeSmoke(proof,version,'1.5.2');
    assert.equal(proof.passiveExeHandoffVerified,true,'Require the actual EXE passive handoff over 1.5.2');
    assert.match(installerDigest,/^[a-f0-9]{64}$/);
    assert.equal(proof.passiveExeInstallerSha256,installerDigest,'Passive handoff must test the exact published EXE bytes');
    assert.match(proof.passiveExeEmbeddedMsiSha256,/^[a-f0-9]{64}$/);
}
export function validateOwnerRequest153(decision,status,version,sha,runId) {
    assert.equal(version,'1.5.3');assert.equal(sha,'69ffc7ecb60a91c874510522c4aae3c5be069ccd');
    assert.equal(String(runId),'39396769729');
    assert.equal(decision.version,version);assert.equal(decision.sourceCommit,sha);assert.equal(String(decision.runId),String(runId));
    assert.ok(['authorized-awaiting-verified-build','verified-for-publication'].includes(decision.phase));
    assert.equal(decision.ownerRequestedPublication,true);
    assert.equal(decision.ownerInstruction,'xong phần này thì đóng gói bản cập nhật mới lên latest để phát hành xuống app.');
    assert.equal(decision.unverifiedChecksDisclosedBeforePublication,true);assert.equal(decision.standardFullAcceptanceClaimed,false);
    for(const field of ['productionLicenseAcceptance','enterpriseCryptoProSigningAccepted','licensedDesktopGtinWriteAccepted',
            'liveApiWbGtinWriteThisVersion','liveApiOzonGtinWriteThisVersion','liveCatalogCardCreatedThisVersion','liveCatalogCardRepairedThisVersion'])assert.equal(decision[field],false,field);
    assert.equal(status.version,version);assert.equal(status.complete,false);assert.equal(status.databaseSchema,6);
    assert.equal(status.licenseIntegration.productionReady,false);assert.equal(status.licenseIntegration.changedInThisVersion,false);
    for(const field of ['productionLicenseVerified','liveCryptoProSigningThisVersion','liveLicensedDesktopGtinWriteThisVersion',
            'liveApiWbGtinWriteThisVersion','liveApiOzonGtinWriteThisVersion','liveCatalogCardCreatedThisVersion','liveCatalogCardRepairedThisVersion'])assert.equal(status.validation[field],false,field);
    const build=status.validation.windowsBuild;
    assert.equal(String(build.runId),String(runId));assert.equal(build.sourceCommit,sha);
    assert.equal(build.freshFullJavaExecutionRequired,true);assert.equal(build.allNativeChecksMustRunAgain,true);
    assert.equal(status.requestReport,'VN-code-1.5.3-request-report.md');
    if(decision.phase==='authorized-awaiting-verified-build') {
        assert.equal(build.state,'running');assert.equal(decision.installerSha256,null);assert.equal(decision.installerBytes,null);
        assert.equal(decision.windowsJava,null);assert.equal(decision.verifiedNativeProofs,null);
        assert.equal(decision.passiveExeHandoffVerified,null);assert.notEqual(build.passiveExeHandoffVerified,true);
        assert.equal(build.fullJavaSuite,undefined);assert.notEqual(build.upgradeFrom152Passed,true);
        assert.notEqual(build.allNativeChecksPassed,true);assert.notEqual(build.installerPackaged,true);
    }
}
export function validateOwnerRequested153(decision,status,version,sha,runId) {
    validateOwnerRequest153(decision,status,version,sha,runId);
    assert.equal(decision.phase,'verified-for-publication');
    assert.match(decision.installerSha256,/^[a-f0-9]{64}$/);assert.ok(Number.isSafeInteger(decision.installerBytes)&&decision.installerBytes>1024*1024);
    const build=status.validation.windowsBuild;
    assert.equal(build.state,'passed');assert.equal(build.upgradeFrom152Passed,true);
    assert.equal(build.allNativeChecksPassed,true);assert.equal(build.installerPackaged,true);
    assert.equal(decision.passiveExeHandoffVerified,true);assert.equal(build.passiveExeHandoffVerified,true);
    assert.ok(decision.verifiedNativeProofs.includes('upgrade-stable-152-smoke.json'));
    assert.ok(decision.windowsJava.tests>=1990);assert.equal(decision.windowsJava.failures,0);assert.equal(decision.windowsJava.errors,0);
    assert.equal(decision.windowsJava.passed,decision.windowsJava.tests-decision.windowsJava.skipped);
    assert.deepEqual(build.fullJavaSuite,decision.windowsJava);
}

// Owner-requested 1.5.4 is bound to this observed frozen source and fresh Windows run.
// Never replace this binding with placeholders or an unverified different build.
export const owner154Binding=Object.freeze({sourceCommit:'688bd01982898e18c9770300fa20cfe29bde1245',runId:39415265129});
export const owner154RequiredNativeProofs=Object.freeze([
    'native-smoke.json','side-by-side-smoke.json','upgrade-smoke.json',
    'upgrade-preview-smoke.json','upgrade-latest-preview-smoke.json','upgrade-current-preview-smoke.json',
    'upgrade-gs1-preview-smoke.json','upgrade-license-test-smoke.json','upgrade-stable-146-smoke.json',
    'upgrade-stable-1410-smoke.json','upgrade-stable-1411-smoke.json','upgrade-stable-1412-smoke.json',
    'upgrade-stable-1413-smoke.json','upgrade-stable-1415-smoke.json','upgrade-stable-1416-smoke.json',
    'upgrade-stable-1418-smoke.json','upgrade-stable-1419-smoke.json','upgrade-stable-150-smoke.json',
    'upgrade-stable-151-smoke.json','upgrade-stable-152-smoke.json','upgrade-stable-153-smoke.json',
    'seller-workspace-smoke.json','ui-layout.json','gs1-smoke.json'
]);
export function validateOwner154UpgradeProof(proof,version,installerDigest) {
    assert.equal(version,'1.5.4');validateUpgradeSmoke(proof,version,'1.5.3');
    assert.equal(proof.baselineTag,'v1.5.3');assert.equal(proof.baselineInstallerBytes,146864128);
    assert.equal(proof.baselineInstallerSha256,'ec608d3677581c12901ee702e516cb2eea2757a5a9ee8fd84a2ce2438e8a3f31');
    assert.equal(proof.passiveExeHandoffVerified,true,'Require actual published-baseline EXE passive upgrade');
    assert.match(installerDigest,/^[a-f0-9]{64}$/);
    assert.equal(proof.passiveExeInstallerSha256,installerDigest,'Verify the exact target EXE bytes');
    assert.match(proof.passiveExeEmbeddedMsiSha256,/^[a-f0-9]{64}$/);
}
/** Evidence shape only; this function does not authorize or bind a release. */
export function validateOwner154Evidence(decision,status,version,sha,runId) {
    assert.equal(version,'1.5.4');assert.match(sha,/^[a-f0-9]{40}$/);assert.match(String(runId),/^[1-9][0-9]{0,15}$/);
    assert.equal(decision.version,version);assert.equal(decision.sourceCommit,sha);assert.equal(String(decision.runId),String(runId));
    assert.equal(decision.sourceRuntimeIsFinal,true);
    assert.ok(['authorized-awaiting-verified-build','verified-for-publication'].includes(decision.phase));
    assert.equal(decision.ownerRequestedPublication,true);
    assert.equal(decision.ownerInstruction,'xong phần này thì đóng gói bản cập nhật mới lên latest để phát hành xuống app.');
    assert.equal(decision.unverifiedChecksDisclosedBeforePublication,true);assert.equal(decision.standardFullAcceptanceClaimed,false);
    for(const field of ['productionLicenseAcceptance','enterpriseCryptoProSigningAccepted','licensedDesktopGtinWriteAccepted',
            'liveApiWbGtinWriteThisVersion','liveApiOzonGtinWriteThisVersion','liveCatalogCardCreatedThisVersion','liveCatalogCardRepairedThisVersion',
            'liveKizPurchaseThisVersion','livePaidKizOrderThisVersion','liveApiWbKizPurchaseThisVersion','liveApiOzonKizPurchaseThisVersion'])assert.equal(decision[field],false,field);
    assert.equal(status.version,version);assert.equal(status.sourceCommit,sha);assert.equal(status.sourceRuntimeIsFinal,true);
    assert.equal(status.complete,false);assert.equal(status.databaseSchema,6);assert.equal(status.baseline,'v1.5.3');
    assert.equal(status.licenseIntegration.productionReady,false);assert.equal(status.licenseIntegration.changedInThisVersion,false);
    for(const field of ['productionLicenseVerified','liveCryptoProSigningThisVersion','liveLicensedDesktopGtinWriteThisVersion',
            'liveApiWbGtinWriteThisVersion','liveApiOzonGtinWriteThisVersion','liveCatalogCardCreatedThisVersion','liveCatalogCardRepairedThisVersion',
            'liveKizPurchaseThisVersion','livePaidKizOrderThisVersion','liveApiWbKizPurchaseThisVersion','liveApiOzonKizPurchaseThisVersion'])assert.equal(status.validation[field],false,field);
    const linux=status.validation.linuxFullSuite;assert.equal(linux.state,'passed');assert.equal(linux.sourceCommit,sha);
    assert.equal(linux.fullSuiteExecuted,true);assert.equal(linux.cleanVerifyExecuted,true);
    assert.ok(linux.tests>=2088);assert.equal(linux.failures,0);assert.equal(linux.errors,0);
    const build=status.validation.windowsBuild;assert.equal(String(build.runId),String(runId));assert.equal(build.sourceCommit,sha);
    assert.equal(build.freshFullJavaExecutionRequired,true);assert.equal(build.allNativeChecksMustRunAgain,true);
    assert.equal(status.requestReport,'VN-code-1.5.4-request-report.md');
    if(decision.phase==='authorized-awaiting-verified-build') {
        assert.equal(build.state,'running');assert.equal(decision.installerSha256,null);assert.equal(decision.installerBytes,null);
        assert.equal(decision.windowsJava,null);assert.equal(decision.verifiedNativeProofs,null);assert.equal(decision.passiveExeHandoffVerified,null);
        assert.equal(build.fullJavaSuite,undefined);assert.notEqual(build.passiveExeHandoffVerified,true);
        assert.notEqual(build.upgradeFrom153Passed,true);assert.notEqual(build.allNativeChecksPassed,true);assert.notEqual(build.installerPackaged,true);
    } else {
        assert.match(decision.installerSha256,/^[a-f0-9]{64}$/);assert.ok(Number.isSafeInteger(decision.installerBytes)&&decision.installerBytes>1024*1024);
        assert.equal(build.state,'passed');assert.equal(build.upgradeFrom153Passed,true);
        assert.equal(build.allNativeChecksPassed,true);assert.equal(build.installerPackaged,true);
        assert.equal(decision.passiveExeHandoffVerified,true);assert.equal(build.passiveExeHandoffVerified,true);
        assert.deepEqual(decision.verifiedNativeProofs,owner154RequiredNativeProofs);
        for(const field of ['tests','failures','errors','skipped','passed'])assert.ok(Number.isSafeInteger(decision.windowsJava[field]),field);
        assert.ok(decision.windowsJava.tests>=2088);assert.equal(decision.windowsJava.failures,0);assert.equal(decision.windowsJava.errors,0);
        assert.ok(decision.windowsJava.skipped>=0&&decision.windowsJava.skipped<=2);
        assert.equal(decision.windowsJava.passed,decision.windowsJava.tests-decision.windowsJava.skipped);
        assert.deepEqual(build.fullJavaSuite,decision.windowsJava);
    }
}
export function validateOwnerRequest154(decision,status,version,sha,runId) {
    assert.match(owner154Binding.sourceCommit,/^[a-f0-9]{40}$/,'1.5.4 final source is not bound');
    assert.match(String(owner154Binding.runId),/^[1-9][0-9]{0,15}$/,'1.5.4 real Windows run is not bound');
    assert.equal(sha,owner154Binding.sourceCommit);assert.equal(String(runId),String(owner154Binding.runId));
    validateOwner154Evidence(decision,status,version,sha,runId);
}
export function validateOwnerRequested154(decision,status,version,sha,runId) {
    validateOwnerRequest154(decision,status,version,sha,runId);assert.equal(decision.phase,'verified-for-publication');
}

/** The attached report describes observed final evidence, not the preparation record. */
export function formatOwner154RequestReport(template,decision,status) {
    validateOwnerRequested154(decision,status,'1.5.4',decision.sourceCommit,decision.runId);
    const text=template.replace(/\r\n/g,'\n');assert.match(text,/^# Báo cáo VNCode 1\.5\.4\n/);
    const scope=text.indexOf('## Phạm vi sửa\n');assert.ok(scope>=0,'154 request report must describe its scope');
    const next=text.indexOf('\n## ',scope+1);assert.ok(next>scope,'154 request report must separate scope and verification');
    const preamble=text.slice(0,next).trim(),linux=status.validation.linuxFullSuite,windows=decision.windowsJava;
    for(const key of ['tests','passed','skipped'])assert.ok(Number.isSafeInteger(linux[key]),key);
    assert.equal(linux.passed,linux.tests-linux.skipped);assert.ok(linux.skipped>=0&&linux.skipped<=2);
    const proofs=decision.verifiedNativeProofs,upgrades=proofs.filter(name=>name.startsWith('upgrade-')).length;
    assert.equal(proofs.length,24);assert.equal(upgrades,19);
    const number=value=>new Intl.NumberFormat('vi-VN').format(value);
    return `${preamble}\n\n## Kiểm thử bộ cài phát hành\n\n`+
        `Source: \`${decision.sourceCommit}\`. Bản nền: [VNCode 1.5.3](https://github.com/${REPO}/releases/tag/v1.5.3); giữ schema 6 và dữ liệu seller.\n\n`+
        `Linux \`clean verify\`: ${number(linux.tests)} kiểm thử, ${number(linux.passed)} đạt, 0 thất bại, 0 lỗi, ${linux.skipped} bỏ qua theo nền tảng. `+
        `Windows Java mới: ${number(windows.tests)} kiểm thử, ${number(windows.passed)} đạt, 0 thất bại, 0 lỗi, ${windows.skipped} bỏ qua theo nền tảng. `+
        `[Windows build ${decision.runId}](https://github.com/${REPO}/actions/runs/${decision.runId}) chạy từ đúng source trên; không dùng lại kết quả Java của bản trước.\n\n`+
        `Đã xác minh đủ ${proofs.length} bằng chứng native và ${upgrades} ca nâng cấp giữ dữ liệu, gồm 288 bố cục, launcher, workspace, DPAPI và nâng cấp từ bộ cài 1.5.3 đúng checksum. `+
        `Bàn giao \`/passive /norestart\` được kiểm tra bằng chính EXE phát hành và MSI nhúng; dữ liệu và đăng ký ứng dụng được giữ.\n\n`+
        `Bộ cài: \`VN-code-1.5.4-Windows-x64.exe\`, ${number(decision.installerBytes)} byte. SHA-256: \`${decision.installerSha256}\`. `+
        `Checksum và bằng chứng đi kèm dùng để đối chiếu đúng các byte đã kiểm tra. Việc build đạt hoặc updater phát hiện bản mới không chứng minh thông báo đã xuất hiện trên desktop seller.\n\n`+
        `## Chưa hoàn tất\n\n`+
        `Chưa nghiệm thu giao dịch mua KIZ trả phí thật, chữ ký CryptoPro doanh nghiệp, license production, tạo/sửa thẻ Catalog hoặc ghi GTIN lên sàn qua desktop seller trong phiên bản này. `+
        `Mọi cờ nghiệm thu production tương ứng vẫn false; kiểm thử mô phỏng không thay thế bằng chứng giao dịch thật. Chưa đo thời gian cập nhật trên máy seller.\n\n`+
        `Phát hành theo yêu cầu tiếp tục của chủ dự án với các giới hạn đã công bố, không nhận nghiệm thu production đầy đủ. `+
        `Quyền license không giới hạn của phiên kiểm thử chỉ áp dụng dữ liệu cô lập WB/Ozon; kiểm tra license, chữ ký, TLS và toàn vẹn của app được giữ. `+
        `Lượt tải và bàn giao ban đầu từ 1.5.3 vẫn chạy mã updater của 1.5.3; phần sửa updater mới có hiệu lực sau khi cài bản này.\n`;
}

// Bind only after the final source and its actual fresh Windows run are known.
// A preparation record, null binding or another consistent build cannot authorize publication.
export const owner155Binding=Object.freeze({sourceCommit:'cc73b32ae50ed21a25196653291df2996ecef1bf',runId:39496207125});
export const owner155RequiredNativeProofs=Object.freeze([
    ...owner154RequiredNativeProofs.slice(0,-3),'upgrade-stable-154-smoke.json',...owner154RequiredNativeProofs.slice(-3)
]);
export function validateOwner155UpgradeProof(proof,version,installerDigest) {
    assert.equal(version,'1.5.5');validateUpgradeSmoke(proof,version,'1.5.4');
    assert.equal(proof.baselineTag,'v1.5.4');assert.equal(proof.baselineInstallerBytes,146868224);
    assert.equal(proof.baselineInstallerSha256,'e7079a4aca07c1ae2fec26e46c4e3a3d77096a14b1c0485a88cefe95a3f7a18b');
    assert.equal(proof.passiveExeHandoffVerified,true);assert.match(installerDigest,/^[a-f0-9]{64}$/);
    assert.equal(proof.passiveExeInstallerSha256,installerDigest);
    assert.match(proof.passiveExeEmbeddedMsiSha256,/^[a-f0-9]{64}$/);
}
/** Shape validation is not authorization; validateOwnerRequest155 binds the exact source/run. */
export function validateOwner155Evidence(decision,status,version,sha,runId) {
    assert.equal(version,'1.5.5');assert.match(sha,/^[a-f0-9]{40}$/);assert.match(String(runId),/^[1-9][0-9]{0,15}$/);
    assert.equal(decision.version,version);assert.equal(decision.sourceCommit,sha);assert.equal(String(decision.runId),String(runId));
    assert.equal(decision.sourceRuntimeIsFinal,true);assert.equal(decision.ownerRequestedPublication,true);
    assert.equal(decision.ownerInstruction,'áp dụng cho bản cập nhật mới và đưa lên latest');
    assert.ok(['authorized-awaiting-verified-build','verified-for-publication'].includes(decision.phase));
    assert.equal(decision.unverifiedChecksDisclosedBeforePublication,true);assert.equal(decision.standardFullAcceptanceClaimed,false);
    for(const field of ['productionLicenseAcceptance','enterpriseCryptoProSigningAccepted','licensedDesktopGtinWriteAccepted',
            'liveApiWbGtinWriteThisVersion','liveApiOzonGtinWriteThisVersion','liveCatalogCardCreatedThisVersion','liveCatalogCardRepairedThisVersion',
            'liveKizPurchaseThisVersion','livePaidKizOrderThisVersion','liveApiWbKizPurchaseThisVersion','liveApiOzonKizPurchaseThisVersion'])assert.equal(decision[field],false,field);
    assert.equal(status.version,version);assert.equal(status.sourceCommit,sha);assert.equal(status.sourceRuntimeIsFinal,true);
    assert.equal(status.complete,false);assert.equal(status.databaseSchema,6);assert.equal(status.baseline,'v1.5.4');
    assert.equal(status.requestReport,'VN-code-1.5.5-request-report.md');
    assert.equal(status.licenseIntegration.productionReady,false);assert.equal(status.licenseIntegration.changedInThisVersion,false);
    for(const field of ['productionLicenseVerified','liveCryptoProSigningThisVersion','liveLicensedDesktopGtinWriteThisVersion',
            'liveApiWbGtinWriteThisVersion','liveApiOzonGtinWriteThisVersion','liveCatalogCardCreatedThisVersion','liveCatalogCardRepairedThisVersion',
            'liveKizPurchaseThisVersion','livePaidKizOrderThisVersion','liveApiWbKizPurchaseThisVersion','liveApiOzonKizPurchaseThisVersion'])assert.equal(status.validation[field],false,field);
    const linux=status.validation.linuxFullSuite;assert.equal(linux.state,'passed');assert.equal(linux.sourceCommit,sha);
    assert.equal(linux.fullSuiteExecuted,true);assert.equal(linux.cleanVerifyExecuted,true);
    for(const field of ['tests','passed','skipped','failures','errors'])assert.ok(Number.isSafeInteger(linux[field]),field);
    assert.ok(linux.tests>=2088);assert.equal(linux.failures,0);assert.equal(linux.errors,0);
    assert.ok(linux.skipped>=0&&linux.skipped<=2);assert.equal(linux.passed,linux.tests-linux.skipped);
    const build=status.validation.windowsBuild;assert.equal(String(build.runId),String(runId));assert.equal(build.sourceCommit,sha);
    assert.equal(build.freshFullJavaExecutionRequired,true);assert.equal(build.allNativeChecksMustRunAgain,true);
    if(decision.phase==='authorized-awaiting-verified-build') {
        assert.equal(build.state,'running');
        for(const field of ['installerSha256','installerBytes','windowsJava','verifiedNativeProofs','passiveExeHandoffVerified','windowsArtifactId'])assert.equal(decision[field],null,field);
        assert.equal(build.windowsArtifactId,null);assert.equal(build.fullJavaSuite,undefined);
        for(const field of ['upgradeFrom154Passed','allNativeChecksPassed','installerPackaged','passiveExeHandoffVerified'])assert.notEqual(build[field],true,field);
    }else{
        assert.equal(build.state,'passed');
        assert.match(decision.installerSha256,/^[a-f0-9]{64}$/);assert.ok(Number.isSafeInteger(decision.installerBytes)&&decision.installerBytes>1024*1024);
        assert.ok(Number.isSafeInteger(decision.windowsArtifactId)&&decision.windowsArtifactId>0);assert.equal(build.windowsArtifactId,decision.windowsArtifactId);
        for(const field of ['upgradeFrom154Passed','allNativeChecksPassed','installerPackaged','passiveExeHandoffVerified'])assert.equal(build[field],true,field);
        assert.equal(decision.passiveExeHandoffVerified,true);assert.deepEqual(decision.verifiedNativeProofs,owner155RequiredNativeProofs);
        for(const field of ['tests','failures','errors','skipped','passed'])assert.ok(Number.isSafeInteger(decision.windowsJava[field]),field);
        assert.ok(decision.windowsJava.tests>=2088);assert.equal(decision.windowsJava.failures,0);assert.equal(decision.windowsJava.errors,0);
        assert.ok(decision.windowsJava.skipped>=0&&decision.windowsJava.skipped<=2);
        assert.equal(decision.windowsJava.passed,decision.windowsJava.tests-decision.windowsJava.skipped);assert.deepEqual(build.fullJavaSuite,decision.windowsJava);
    }
}
export function validateOwnerRequest155(decision,status,version,sha,runId) {
    assert.match(owner155Binding.sourceCommit??'',/^[a-f0-9]{40}$/,'1.5.5 final source is not bound');
    assert.match(String(owner155Binding.runId??''),/^[1-9][0-9]{0,15}$/,'1.5.5 real Windows run is not bound');
    assert.equal(sha,owner155Binding.sourceCommit);assert.equal(String(runId),String(owner155Binding.runId));
    validateOwner155Evidence(decision,status,version,sha,runId);
}
export function validateOwnerRequested155(decision,status,version,sha,runId) {
    validateOwnerRequest155(decision,status,version,sha,runId);assert.equal(decision.phase,'verified-for-publication');
}
export function validateOwner155Artifact(artifact,version,sha,runId,expectedId=null) {
    assert.equal(version,'1.5.5');assert.ok(Number.isSafeInteger(artifact.id)&&artifact.id>0);
    assert.equal(artifact.name,`VN-code-${version}-Windows-x64`);assert.equal(artifact.expired,false);
    assert.equal(String(artifact.workflow_run?.id),String(runId));assert.equal(artifact.workflow_run?.head_sha,sha);
    assert.match(artifact.digest??'',/^sha256:[a-f0-9]{64}$/);
    if(expectedId!==null)assert.equal(artifact.id,expectedId);
}
/** Formatting observed evidence does not itself authorize any release or mutation. */
export function formatOwner155RequestReport(template,decision,status) {
    validateOwner155Evidence(decision,status,'1.5.5',decision.sourceCommit,decision.runId);assert.equal(decision.phase,'verified-for-publication');
    const text=template.replace(/\r\n/g,'\n');assert.match(text,/^# Báo cáo VNCode 1\.5\.5\n/);
    const scope=text.indexOf('## Phạm vi sửa\n');assert.ok(scope>=0,'155 request report must describe its scope');
    const next=text.indexOf('\n## ',scope+1);assert.ok(next>scope,'155 request report must separate scope and verification');
    const linux=status.validation.linuxFullSuite,windows=decision.windowsJava,number=value=>new Intl.NumberFormat('vi-VN').format(value);
    const proofs=decision.verifiedNativeProofs,upgrades=proofs.filter(name=>name.startsWith('upgrade-')).length;
    assert.equal(proofs.length,25);assert.equal(upgrades,20);
    return `${text.slice(0,next).trim()}\n\n## Kiểm thử bộ cài phát hành\n\n`+
        `Source: \`${decision.sourceCommit}\`. Bản nền: [VNCode 1.5.4](https://github.com/${REPO}/releases/tag/v1.5.4); giữ schema 6 và dữ liệu seller.\n\n`+
        `Linux \`clean verify\`: ${number(linux.tests)} kiểm thử, ${number(linux.passed)} đạt, 0 thất bại, 0 lỗi, ${linux.skipped} bỏ qua theo nền tảng. `+
        `Windows Java mới: ${number(windows.tests)} kiểm thử, ${number(windows.passed)} đạt, 0 thất bại, 0 lỗi, ${windows.skipped} bỏ qua theo nền tảng. `+
        `[Windows build ${decision.runId}](https://github.com/${REPO}/actions/runs/${decision.runId}) chạy từ đúng source, artifact ${decision.windowsArtifactId}; không dùng lại kết quả Java của bản trước.\n\n`+
        `Đủ ${proofs.length} bằng chứng native và ${upgrades} ca nâng cấp giữ dữ liệu, gồm 288 bố cục, launcher, workspace, DPAPI và bộ cài 1.5.4 đúng checksum. `+
        `Bàn giao \`/passive /norestart\` được kiểm tra bằng chính EXE phát hành và MSI nhúng; dữ liệu và đăng ký ứng dụng được giữ.\n\n`+
        `Bộ cài: \`VN-code-1.5.5-Windows-x64.exe\`, ${number(decision.installerBytes)} byte. SHA-256: \`${decision.installerSha256}\`. `+
        `Checksum đối chiếu đúng các byte đã kiểm tra. Updater phát hiện bản mới không chứng minh thông báo đã xuất hiện trên desktop seller.\n\n`+
        `## Chưa hoàn tất\n\n`+
        `Chưa nghiệm thu tạo/sửa thẻ Catalog production, chữ ký CryptoPro doanh nghiệp, ghi GTIN qua desktop seller, giao dịch mua KIZ trả phí thật hoặc license production trong phiên bản này. `+
        `Mọi cờ nghiệm thu production tương ứng vẫn false; kiểm thử mô phỏng không thay thế giao dịch thật. Không bảo đảm sản phẩm khác được duyệt chỉ vì mẫu Catalog đã xanh.\n\n`+
        `Phát hành theo yêu cầu trực tiếp của chủ dự án với các giới hạn đã công bố. Kiểm tra license, chữ ký, TLS và toàn vẹn của app được giữ.\n`;
}
export async function verifyPublicInstaller(asset,version,digest,size,fetcher=fetch) {
    validateStableAsset(asset,version,digest,size);
    const response=await fetcher(asset.browser_download_url,{redirect:'follow',signal:AbortSignal.timeout(180000)});
    assert.equal(response.status,200,'Public installer download failed');
    const bytes=Buffer.from(await response.arrayBuffer());
    assert.equal(bytes.length,size,'Public installer size differs from the verified build');
    assert.equal(hash(bytes),digest,'Public installer bytes differ from the verified build');
    return {bytes:bytes.length,sha256:hash(bytes),anonymousDownloadVerified:true};
}

// This authorization applies only to the audited 1.5.7 source and its fresh Windows run.
// Keep production acceptance false; all artifact, Java, native and upgrade checks still run.
export function validateOwnerRequest157(decision,status,version,sha,runId) {
    assert.equal(version,'1.5.7');assert.equal(sha,'2a44ebda0879ad844cefb86a3e4b97dfaa45c7de');
    assert.equal(String(runId),'39549870900');assert.equal(decision.version,version);
    assert.equal(decision.buildSourceCommit,sha);assert.equal(String(decision.runId),String(runId));
    assert.equal(decision.ownerRequestedPublication,true);assert.equal(decision.ownerInstruction,'tạo bộ cài và thay latest');
    assert.equal(decision.unverifiedChecksDisclosedBeforePublication,true);assert.equal(decision.standardFullAcceptanceClaimed,false);
    for(const field of ['productionLicenseAcceptance','enterpriseCryptoProSigningAccepted','liveMarketplaceWritesAccepted'])assert.equal(decision[field],false);
    assert.ok(['authorized-awaiting-verified-windows-build','verified-for-publication'].includes(decision.phase));
    assert.equal(status.version,version);assert.equal(status.complete,false);assert.equal(status.databaseSchema,6);
    assert.equal(status.licenseIntegration.productionReady,false);assert.equal(status.licenseIntegration.changedInThisVersion,false);
    for(const field of ['productionLicenseVerified','liveCryptoProSigningThisVersion','liveMarketplaceWritesThisVersion'])assert.equal(status.validation[field],false);
    const build=status.validation.windowsBuild;assert.equal(build.sourceCommit,sha);assert.equal(String(build.runId),String(runId));assert.equal(build.freshFullJavaExecutionRequired,true);
    if(decision.phase==='authorized-awaiting-verified-windows-build')assert.equal(build.state,'running');
}
export function validateOwnerRequested157(decision,status,version,sha,runId) {
    validateOwnerRequest157(decision,status,version,sha,runId);assert.equal(decision.phase,'verified-for-publication');
    assert.match(decision.installerSha256,/^[a-f0-9]{64}$/);assert.ok(Number.isSafeInteger(decision.installerBytes)&&decision.installerBytes>1024*1024);
    assert.equal(decision.passiveExeHandoffVerified,true);
    const build=status.validation.windowsBuild;assert.equal(build.state,'passed');assert.equal(build.upgradeFrom155Passed,true);
    assert.equal(build.allNativeChecksPassed,true);assert.equal(build.passiveExeHandoffVerified,true);
    const java=decision.windowsJava;assert.ok(java.tests>=2788);assert.equal(java.failures,0);assert.equal(java.errors,0);assert.ok(java.skipped<=2);assert.equal(java.passed,java.tests-java.skipped);
    assert.deepEqual(build.fullJavaSuite,java);
}

// One explicit Latest request, bound to the observed application commit and fresh CI run.
// Publication authorization never stands in for production seller acceptance.
export const owner158RequiredNativeProofs=Object.freeze([
    ...owner155RequiredNativeProofs.slice(0,-3),'upgrade-stable-155-smoke.json','upgrade-stable-157-smoke.json',
    ...owner155RequiredNativeProofs.slice(-3)
]);
export function validateOwnerRequest158(decision,status,version,sha,runId) {
    assert.equal(version,'1.5.8');assert.equal(sha,'06408f3a35fddd2952b64374f75e5761d0b43502');
    assert.equal(String(runId),'39641010813');assert.equal(decision.version,version);
    assert.equal(decision.buildSourceCommit,sha);assert.equal(String(decision.runId),String(runId));
    assert.equal(decision.ownerRequestedPublication,true);
    assert.equal(decision.ownerInstruction,'tạo phát hành bản cập nhật mới lên latest để thông báo xuống ứng dụng');
    assert.equal(decision.unverifiedChecksDisclosedBeforePublication,true);assert.equal(decision.standardFullAcceptanceClaimed,false);
    for(const field of ['productionLicenseAcceptance','enterpriseCryptoProSigningAccepted','liveMarketplaceWritesAccepted'])assert.equal(decision[field],false);
    assert.ok(['authorized-awaiting-verified-windows-build','verified-for-publication'].includes(decision.phase));
    assert.equal(status.version,version);assert.equal(status.complete,false);assert.equal(status.databaseSchema,6);assert.equal(status.baseline,'v1.5.7');
    assert.equal(status.registrationUi.identicalTo157,true);assert.equal(status.registrationUi.separateV3Screen,false);
    assert.equal(status.licenseIntegration.productionReady,false);assert.equal(status.licenseIntegration.changedInThisVersion,false);
    for(const field of ['productionLicenseVerified','liveCryptoProSigningThisVersion','liveMarketplaceWritesThisVersion'])assert.equal(status.validation[field],false);
    const build=status.validation.windowsBuild;
    assert.equal(build.sourceCommit,sha);assert.equal(String(build.runId),String(runId));assert.equal(build.freshFullJavaExecutionRequired,true);assert.equal(build.upgradeFrom157Required,true);
    if(decision.phase==='authorized-awaiting-verified-windows-build') {
        assert.equal(build.state,'running');assert.equal(build.windowsArtifactId,null);assert.equal(build.fullJavaSuite,undefined);
        for(const field of ['installerSha256','installerBytes','windowsJava','windowsArtifactId','verifiedNativeProofs','passiveExeHandoffVerified'])assert.equal(decision[field],null,field);
        for(const field of ['upgradeFrom157Passed','allNativeChecksPassed','installerPackaged','passiveExeHandoffVerified'])assert.notEqual(build[field],true,field);
    }
}
export function validateOwnerRequested158(decision,status,version,sha,runId) {
    validateOwnerRequest158(decision,status,version,sha,runId);assert.equal(decision.phase,'verified-for-publication');
    assert.match(decision.installerSha256,/^[a-f0-9]{64}$/);assert.ok(Number.isSafeInteger(decision.installerBytes)&&decision.installerBytes>1024*1024);
    assert.ok(Number.isSafeInteger(decision.windowsArtifactId)&&decision.windowsArtifactId>0);
    assert.deepEqual(decision.verifiedNativeProofs,owner158RequiredNativeProofs);assert.equal(decision.passiveExeHandoffVerified,true);
    const build=status.validation.windowsBuild;assert.equal(build.state,'passed');assert.equal(build.windowsArtifactId,decision.windowsArtifactId);
    for(const field of ['upgradeFrom157Passed','allNativeChecksPassed','installerPackaged','passiveExeHandoffVerified'])assert.equal(build[field],true,field);
    const java=decision.windowsJava;
    for(const field of ['tests','failures','errors','skipped','passed'])assert.ok(Number.isSafeInteger(java[field]),field);
    assert.ok(java.tests>=2838);assert.equal(java.failures,0);assert.equal(java.errors,0);assert.ok(java.skipped>=0&&java.skipped<=2);
    assert.equal(java.passed,java.tests-java.skipped);assert.deepEqual(build.fullJavaSuite,java);
}
export function validateOwner158UpgradeProof(proof,version,installerDigest) {
    assert.equal(version,'1.5.8');validateUpgradeSmoke(proof,version,'1.5.7');
    assert.equal(proof.baselineTag,'v1.5.7');assert.equal(proof.baselineInstallerBytes,147257344);
    assert.equal(proof.baselineInstallerSha256,'b51a0049481296ef9d5ad1f2be67a729a366526965dfd18571b9adac30b9a693');
    assert.equal(proof.passiveExeHandoffVerified,true);assert.match(installerDigest,/^[a-f0-9]{64}$/);
    assert.equal(proof.passiveExeInstallerSha256,installerDigest);assert.match(proof.passiveExeEmbeddedMsiSha256,/^[a-f0-9]{64}$/);
}
export function formatOwner158RequestReport(template,decision,status) {
    validateOwnerRequested158(decision,status,'1.5.8',decision.buildSourceCommit,decision.runId);
    const marker='## Đóng gói và phát hành',offset=template.indexOf(marker);
    assert.ok(offset>=0,'Missing release report section');
    const java=decision.windowsJava,proofs=decision.verifiedNativeProofs;
    return template.slice(0,offset).trim()+'\n\n'+marker+'\n\n'+
        `Windows build [${decision.runId}](https://github.com/${REPO}/actions/runs/${decision.runId}) `+
        `từ đúng source \`${decision.buildSourceCommit}\` đã qua; chạy mới toàn bộ Java, không dùng lại artifact cũ.\n\n`+
        `Java/FXML: ${java.tests} ca, ${java.passed} đạt, ${java.failures} failures, ${java.errors} errors, ${java.skipped} bỏ qua đúng nền tảng. `+
        `${proofs.length} bằng chứng native, gồm ${proofs.filter(name=>name.startsWith('upgrade-')).length} ca nâng cấp, `+
        `workspace, DPI, bố cục và DPAPI đã được đối chiếu. Nâng cấp từ đúng byte/checksum 1.5.7 qua EXE \`/passive /norestart\` `+
        `giữ fingerprint dữ liệu và một registration.\n\n`+
        `Bộ cài \`VN-code-1.5.8-Windows-x64.exe\`: ${decision.installerBytes} byte; SHA-256 \`${decision.installerSha256}\`.\n\n`+
        `Đủ điều kiện công bố Latest theo yêu cầu trực tiếp của chủ dự án. Chưa nghiệm thu license production, `+
        `CryptoPro/chứng thư doanh nghiệp, cấp mã/gửi thẻ hay ghi GTIN seller thật; các cờ nghiệm thu tương ứng vẫn false. `+
        `Bộ cài chưa ký Authenticode. Thông báo trên máy seller chưa được quan sát; ứng dụng kiểm tra GitHub Latest ở lần kiểm tra cập nhật tiếp theo.\n`;
}

async function main(runId,sha,mode='prepare') {
    assert.match(runId??'',/^[1-9][0-9]{0,15}$/);assert.match(sha??'',/^[a-f0-9]{40}$/);
    assert.ok(['prepare','publish','owner-approved-1418','owner-approved-1419','owner-requested-150','owner-requested-151','owner-requested-152','owner-requested-153','owner-requested-154','owner-requested-155','owner-requested-157','owner-requested-158'].includes(mode));
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
    if(mode==='owner-requested-152') {
        ownerDecision=json(await readFile('docs/validation/2026-10-19-vncode-152-owner-publication.json'));
        validateOwnerRequest152(ownerDecision,status,version,sha,runId);
        pendingOwnerBuild=ownerDecision.phase==='authorized-awaiting-verified-build';
        if(!pendingOwnerBuild)validateOwnerRequested152(ownerDecision,status,version,sha,runId);
        assert.ok((await readFile('docs/releases/'+status.requestReport,'utf8')).includes('Chưa hoàn tất'));
    }
    if(mode==='owner-requested-153') {
        ownerDecision=json(await readFile('docs/validation/2026-10-20-vncode-153-owner-publication.json'));
        validateOwnerRequest153(ownerDecision,status,version,sha,runId);
        pendingOwnerBuild=ownerDecision.phase==='authorized-awaiting-verified-build';
        if(!pendingOwnerBuild)validateOwnerRequested153(ownerDecision,status,version,sha,runId);
        assert.ok((await readFile('docs/releases/'+status.requestReport,'utf8')).includes('Chưa hoàn tất'));
    }
    if(mode==='owner-requested-154') {
        ownerDecision=json(await readFile('docs/validation/2026-10-20-vncode-154-owner-publication.json'));
        validateOwnerRequest154(ownerDecision,status,version,sha,runId);
        pendingOwnerBuild=ownerDecision.phase==='authorized-awaiting-verified-build';
        if(!pendingOwnerBuild)validateOwnerRequested154(ownerDecision,status,version,sha,runId);
        assert.ok((await readFile('docs/releases/'+status.requestReport,'utf8')).includes('Chưa hoàn tất'));
    }
    if(mode==='owner-requested-155') {
        ownerDecision=json(await readFile('docs/validation/2026-10-20-vncode-155-owner-publication.json'));
        validateOwnerRequest155(ownerDecision,status,version,sha,runId);
        pendingOwnerBuild=ownerDecision.phase==='authorized-awaiting-verified-build';
        if(!pendingOwnerBuild)validateOwnerRequested155(ownerDecision,status,version,sha,runId);
        assert.ok((await readFile('docs/releases/'+status.requestReport,'utf8')).includes('Chưa hoàn tất'));
    }
    if(mode==='owner-requested-157') {
        ownerDecision=json(await readFile('docs/validation/2026-10-21-vncode-157-owner-publication.json'));
        validateOwnerRequest157(ownerDecision,status,version,sha,runId);
        pendingOwnerBuild=ownerDecision.phase==='authorized-awaiting-verified-windows-build';
        if(!pendingOwnerBuild)validateOwnerRequested157(ownerDecision,status,version,sha,runId);
    }
    if(mode==='owner-requested-158') {
        ownerDecision=json(await readFile('docs/validation/2026-10-21-vncode-158-owner-publication.json'));
        validateOwnerRequest158(ownerDecision,status,version,sha,runId);
        pendingOwnerBuild=ownerDecision.phase==='authorized-awaiting-verified-windows-build';
        if(!pendingOwnerBuild)validateOwnerRequested158(ownerDecision,status,version,sha,runId);
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
    const matchingBuildArtifacts=api(`actions/runs/${runId}/artifacts?per_page=100`).artifacts.filter(a=>a.name===artifactName&&!a.expired);
    assert.equal(matchingBuildArtifacts.length,1);const buildArtifact=matchingBuildArtifacts[0];
    if(mode==='owner-requested-155'){
        validateOwner155Artifact(buildArtifact,version,sha,runId,pendingOwnerBuild?null:ownerDecision.windowsArtifactId);
    }
    if(mode==='owner-requested-158') {
        assert.ok(Number.isSafeInteger(buildArtifact.id)&&buildArtifact.id>0);
        assert.equal(String(buildArtifact.workflow_run.id),String(runId));assert.equal(buildArtifact.workflow_run.head_sha,sha);
        assert.match(buildArtifact.digest??'',/^sha256:[a-f0-9]{64}$/);
        if(!pendingOwnerBuild)assert.equal(buildArtifact.id,ownerDecision.windowsArtifactId);
    }
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
    let passiveExeHandoffVerified;
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
    if(version.localeCompare('1.5.1','en',{numeric:true})>0) {
        validateUpgradeSmoke(json(await readFile(path.join(directory,'upgrade-stable-151-smoke.json'))),version,'1.5.1');
        proofFiles.push('upgrade-stable-151-smoke.json');
    }
    if(version.localeCompare('1.5.2','en',{numeric:true})>0) {
        const upgrade152=json(await readFile(path.join(directory,'upgrade-stable-152-smoke.json')));
        if(mode==='owner-requested-153') {
            validateOwner153UpgradeProof(upgrade152,version,digest);
            passiveExeHandoffVerified=upgrade152.passiveExeHandoffVerified;
        } else validateUpgradeSmoke(upgrade152,version,'1.5.2');
        proofFiles.push('upgrade-stable-152-smoke.json');
    }
    if(version.localeCompare('1.5.3','en',{numeric:true})>0) {
        const upgrade153=json(await readFile(path.join(directory,'upgrade-stable-153-smoke.json')));
        if(mode==='owner-requested-154') {
            validateOwner154UpgradeProof(upgrade153,version,digest);
            passiveExeHandoffVerified=upgrade153.passiveExeHandoffVerified;
        } else validateUpgradeSmoke(upgrade153,version,'1.5.3');
        proofFiles.push('upgrade-stable-153-smoke.json');
    }
    if(version.localeCompare('1.5.4','en',{numeric:true})>0) {
        const upgrade154=json(await readFile(path.join(directory,'upgrade-stable-154-smoke.json')));
        if(mode==='owner-requested-155') {
            validateOwner155UpgradeProof(upgrade154,version,digest);
            passiveExeHandoffVerified=upgrade154.passiveExeHandoffVerified;
        } else validateUpgradeSmoke(upgrade154,version,'1.5.4');
        proofFiles.push('upgrade-stable-154-smoke.json');
    }
    if(['owner-requested-157','owner-requested-158'].includes(mode)) {
        const upgrade155=json(await readFile(path.join(directory,'upgrade-stable-155-smoke.json')));
        validateUpgradeSmoke(upgrade155,version,'1.5.5');
        assert.equal(upgrade155.baselineTag,'v1.5.5');assert.equal(upgrade155.baselineInstallerSha256,'e966b8d36101f8d0c62b5340e7804ff574fb1453e89d713dbcd604b7446b4b13');
        assert.equal(upgrade155.baselineInstallerBytes,147044352);assert.equal(upgrade155.passiveExeHandoffVerified,true);
        assert.equal(upgrade155.passiveExeInstallerSha256,digest);assert.match(upgrade155.passiveExeEmbeddedMsiSha256,/^[a-f0-9]{64}$/);
        passiveExeHandoffVerified=true;proofFiles.push('upgrade-stable-155-smoke.json');
    }
    if(mode==='owner-requested-158') {
        validateOwner158UpgradeProof(json(await readFile(path.join(directory,'upgrade-stable-157-smoke.json'))),version,digest);
        passiveExeHandoffVerified=true;proofFiles.push('upgrade-stable-157-smoke.json');
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
        assert.ok(!logs.includes('RECONCILED_WINDOWS_JAVA:'),'151 does not accept repaired Java failures');
        assert.equal(logs.includes('REUSED_WINDOWS_JAVA:'),status.validation.windowsBuild.freshFullJavaExecutionRequired===false,'Java execution must match the disclosed verification plan');
        if(!pendingOwnerBuild)assert.deepEqual(java,ownerDecision.windowsJava);
    }
    if(mode==='owner-requested-152') {
        assert.ok(!logs.includes('REUSED_WINDOWS_JAVA:')&&!logs.includes('RECONCILED_WINDOWS_JAVA:'),'1.5.2 requires a fresh full Windows Java run');
        if(!pendingOwnerBuild)assert.deepEqual(java,ownerDecision.windowsJava);
    }
    if(mode==='owner-requested-153') {
        assert.ok(!logs.includes('REUSED_WINDOWS_JAVA:')&&!logs.includes('RECONCILED_WINDOWS_JAVA:'),'1.5.3 requires a fresh full Windows Java run');
        if(!pendingOwnerBuild)assert.deepEqual(java,ownerDecision.windowsJava);
    }
    if(mode==='owner-requested-154') {
        assert.ok(!logs.includes('REUSED_WINDOWS_JAVA:')&&!logs.includes('RECONCILED_WINDOWS_JAVA:'),'1.5.4 requires a fresh full Windows Java run');
        if(!pendingOwnerBuild)assert.deepEqual(java,ownerDecision.windowsJava);
    }
    if(mode==='owner-requested-155') {
        assert.ok(!logs.includes('REUSED_WINDOWS_JAVA:')&&!logs.includes('RECONCILED_WINDOWS_JAVA:'),'1.5.5 requires a fresh full Windows Java run');
        if(!pendingOwnerBuild)assert.deepEqual(java,ownerDecision.windowsJava);
    }
    if(mode==='owner-requested-157') {
        assert.ok(!logs.includes('REUSED_WINDOWS_JAVA:')&&!logs.includes('RECONCILED_WINDOWS_JAVA:'),'1.5.7 requires fresh full Windows tests');
        assert.ok(java.tests>=2788);if(!pendingOwnerBuild)assert.deepEqual(java,ownerDecision.windowsJava);
    }
    if(mode==='owner-requested-158') {
        assert.ok(!logs.includes('REUSED_WINDOWS_JAVA:')&&!logs.includes('RECONCILED_WINDOWS_JAVA:'),'1.5.8 requires fresh full Windows tests');
        assert.ok(java.tests>=2838);if(!pendingOwnerBuild)assert.deepEqual(java,ownerDecision.windowsJava);
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
        if(mode==='owner-requested-151'&&status.validation.windowsBuild.freshFullJavaExecutionRequired===false)validateReuse151(javaVerificationOrigin,sha,java);
        else assert.equal(javaVerificationOrigin,undefined,'This authorization requires a fresh full Java run');
        ownerDecision={...ownerDecision,phase:'verified-for-publication',installerSha256:digest,installerBytes:bytes.length,
            windowsJava:java,verifiedNativeProofs:proofFiles,...(['owner-requested-153','owner-requested-154','owner-requested-155','owner-requested-157','owner-requested-158'].includes(mode)?{passiveExeHandoffVerified}:{}),
            ...(['owner-requested-155','owner-requested-157','owner-requested-158'].includes(mode)?{windowsArtifactId:buildArtifact.id}:{}),
            ...(mode==='owner-requested-151'&&javaVerificationOrigin?{freshJavaExecutedInThisRun:false,javaVerificationOrigin}:{})};
        if(mode==='owner-requested-151') {
            status.validation.windowsBuild={...status.validation.windowsBuild,state:'passed',upgradeFrom150Passed:true,fullJavaSuite:java};
            validateOwnerRequested151(ownerDecision,status,version,sha,runId);
        } else if(mode==='owner-requested-152') {
            status.validation.windowsBuild={...status.validation.windowsBuild,state:'passed',upgradeFrom151Passed:true,fullJavaSuite:java};
            validateOwnerRequested152(ownerDecision,status,version,sha,runId);
        } else if(mode==='owner-requested-153') {
            status.validation.windowsBuild={...status.validation.windowsBuild,state:'passed',upgradeFrom152Passed:true,allNativeChecksPassed:true,installerPackaged:true,passiveExeHandoffVerified,fullJavaSuite:java};
            validateOwnerRequested153(ownerDecision,status,version,sha,runId);
        } else if(mode==='owner-requested-154') {
            status.validation.windowsBuild={...status.validation.windowsBuild,state:'passed',upgradeFrom153Passed:true,allNativeChecksPassed:true,installerPackaged:true,passiveExeHandoffVerified,fullJavaSuite:java};
            validateOwnerRequested154(ownerDecision,status,version,sha,runId);
        } else if(mode==='owner-requested-155') {
            status.validation.windowsBuild={...status.validation.windowsBuild,state:'passed',windowsArtifactId:buildArtifact.id,upgradeFrom154Passed:true,allNativeChecksPassed:true,installerPackaged:true,passiveExeHandoffVerified,fullJavaSuite:java};
            validateOwnerRequested155(ownerDecision,status,version,sha,runId);
        } else if(mode==='owner-requested-158') {
            status.validation.windowsBuild={...status.validation.windowsBuild,state:'passed',windowsArtifactId:buildArtifact.id,upgradeFrom157Passed:true,allNativeChecksPassed:true,installerPackaged:true,passiveExeHandoffVerified,fullJavaSuite:java};
            validateOwnerRequested158(ownerDecision,status,version,sha,runId);
        } else if(mode==='owner-requested-157') {
            status.validation.windowsBuild={...status.validation.windowsBuild,state:'passed',windowsArtifactId:buildArtifact.id,upgradeFrom155Passed:true,allNativeChecksPassed:true,installerPackaged:true,passiveExeHandoffVerified,fullJavaSuite:java};
            validateOwnerRequested157(ownerDecision,status,version,sha,runId);
        } else {
            status.validation.windowsBuild={...status.validation.windowsBuild,state:'passed',upgradeFrom1418Passed:true};
            validateOwnerApproved1419(ownerDecision,status,version,sha,runId);
        }
    }
    const notes=(await readFile(`docs/releases/VN-code-${version}.md`,'utf8')).trim();assert.ok(notes.length>50);
    const info={version,sourceCommit:sha,runId:Number(runId),java,installer:{filename:name,bytes:bytes.length,sha256:digest},
        licenseProductionAcceptance:status.licenseIntegration.productionReady,native,javaVerificationOrigin,channel:'stable-after-publication',authenticodeSigned:false,
        ...(['owner-requested-155','owner-requested-158'].includes(mode)?{windowsArtifactId:buildArtifact.id,windowsArtifactDigest:buildArtifact.digest}:{})};
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
    if(mode==='owner-requested-152') {
        await writeFile(path.join(directory,'release-acceptance.json'),JSON.stringify(ownerDecision,null,2)+'\n');
        await writeFile(path.join(directory,'request-report.md'),await readFile('docs/releases/VN-code-1.5.2-request-report.md'));
        names.push('release-acceptance.json','request-report.md');
    }
    if(mode==='owner-requested-153') {
        await writeFile(path.join(directory,'release-acceptance.json'),JSON.stringify(ownerDecision,null,2)+'\n');
        await writeFile(path.join(directory,'request-report.md'),await readFile('docs/releases/VN-code-1.5.3-request-report.md'));
        names.push('release-acceptance.json','request-report.md');
    }
    if(mode==='owner-requested-154') {
        await writeFile(path.join(directory,'release-acceptance.json'),JSON.stringify(ownerDecision,null,2)+'\n');
        await writeFile(path.join(directory,'request-report.md'),formatOwner154RequestReport(await readFile('docs/releases/VN-code-1.5.4-request-report.md','utf8'),ownerDecision,status));
        names.push('release-acceptance.json','request-report.md');
    }
    if(mode==='owner-requested-155') {
        await writeFile(path.join(directory,'release-acceptance.json'),JSON.stringify(ownerDecision,null,2)+'\n');
        await writeFile(path.join(directory,'request-report.md'),formatOwner155RequestReport(await readFile('docs/releases/VN-code-1.5.5-request-report.md','utf8'),ownerDecision,status));
        names.push('release-acceptance.json','request-report.md');
    }
    if(mode==='owner-requested-157') {
        await writeFile(path.join(directory,'release-acceptance.json'),JSON.stringify(ownerDecision,null,2)+'\n');
        await writeFile(path.join(directory,'request-report.md'),await readFile('docs/VNCODE_ZNACK_TEST_REPORT.md'));
        names.push('release-acceptance.json','request-report.md');
    }
    if(mode==='owner-requested-158') {
        status.state='windows-verified-authorized-publication';
        status.blocked=['Production license/CryptoPro/seller acceptance remains unverified.'];
        await writeFile(path.join(directory,'release-acceptance.json'),JSON.stringify(ownerDecision,null,2)+'\n');
        await writeFile(path.join(directory,'release-status.json'),JSON.stringify(status,null,2)+'\n');
        await writeFile(path.join(directory,'request-report.md'),formatOwner158RequestReport(await readFile('docs/ZNACK_1.5.8_UPDATE.md','utf8'),ownerDecision,status));
        names.push('release-acceptance.json','release-status.json','request-report.md');
    }
    const digests={};for(const file of names)digests[file]=hash(await readFile(path.join(directory,file)));
    if(mode==='owner-requested-157')assert.equal(api('releases/latest').tag_name,'v1.5.5','Latest changed since owner authorization; recheck upgrade baseline');
    if(mode==='owner-requested-158')assert.equal(api('releases/latest').tag_name,'v1.5.7','Latest changed; recheck the verified upgrade baseline');
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
    const publishing=mode==='publish'||mode==='owner-approved-1418'||mode==='owner-approved-1419'||mode==='owner-requested-150'||mode==='owner-requested-151'||mode==='owner-requested-152'||mode==='owner-requested-153'||mode==='owner-requested-154'||mode==='owner-requested-155'||mode==='owner-requested-157'||mode==='owner-requested-158';
    let publicDownload;
    if(publishing) {
        if(mode==='owner-requested-157')assert.equal(api('releases/latest').tag_name,'v1.5.5','Do not supersede a newer concurrent publication');
        if(mode==='owner-requested-158')assert.equal(api('releases/latest').tag_name,'v1.5.7','Do not supersede a concurrent publication');
        const request=path.join(directory,'publish.json');await writeFile(request,JSON.stringify({draft:false,prerelease:false,make_latest:'true'}));
        gh(['api','-X','PATCH',`repos/${REPO}/releases/${release.id}`,'--input',request]);
        validatePublishedStable(api('releases/latest'),version);
        validateStableAsset(api(`releases/${release.id}`).assets.find(a=>a.name===name),version,digest,bytes.length);
        if(['owner-requested-155','owner-requested-157','owner-requested-158'].includes(mode))publicDownload=await verifyPublicInstaller(api(`releases/${release.id}`).assets.find(a=>a.name===name),version,digest,bytes.length);
    }
    console.log(JSON.stringify({version,mode,url:api(`releases/${release.id}`).html_url,sourceCommit:sha,sha256:digest,published:publishing,...(publicDownload?{publicDownload}:{})}));
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))await main(...process.argv.slice(2));
