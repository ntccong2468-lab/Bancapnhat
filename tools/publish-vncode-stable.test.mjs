import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {validateStableAsset,validatePublishedStable,validateReusedJava,validateOwnerApproved1418,validateFixtureRecovery} from './publish-vncode-stable.mjs';
import {origin} from './reuse-verified-windows-java.mjs';

const version='1.4.6',digest='a'.repeat(64),size=140000000;
const asset={name:`VN-code-${version}-Windows-x64.exe`,state:'uploaded',digest:`sha256:${digest}`,size,
    browser_download_url:`https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v${version}/VN-code-${version}-Windows-x64.exe`};
test('published stable release has notes and a platform installer the desktop can discover',()=>{
    validateStableAsset(asset,version,digest,size);
    validatePublishedStable({tag_name:`v${version}`,draft:false,prerelease:false,body:'Changes for sellers',assets:[asset]},version);
    for(const patch of [{draft:true},{prerelease:true},{body:''},{assets:[]}])
        assert.throws(()=>validatePublishedStable({tag_name:`v${version}`,draft:false,prerelease:false,body:'Notes',assets:[asset],...patch},version));
});
test('stable publication rejects the wrong repository, diagnostic filename, bytes or digest',()=>{
    for(const patch of [{name:'VN-code-1.4.6-License-Diagnostics-Windows-x64.exe'},{size:size+1},{digest:'sha256:'+'b'.repeat(64)},
        {browser_download_url:asset.browser_download_url.replace('/Bancapnhat/','/Vncode/')}])
        assert.throws(()=>validateStableAsset({...asset,...patch},version,digest,size));
});
test('only a draft may use GitHub temporary untagged download URLs',()=>{
    const draftAsset={...asset,browser_download_url:asset.browser_download_url.replace('/v1.4.6/','/untagged-91cab40497ec860f7175/')};
    validateStableAsset(draftAsset,version,digest,size,true);
    assert.throws(()=>validateStableAsset(draftAsset,version,digest,size));
    for(const url of [draftAsset.browser_download_url.replace('/Bancapnhat/','/other/'),
            draftAsset.browser_download_url.replace('untagged-91cab40497ec860f7175','untagged-../../evil'),
            draftAsset.browser_download_url+'?redirect=evil'])
        assert.throws(()=>validateStableAsset({...draftAsset,browser_download_url:url},version,digest,size,true));
});
test('draft preparation retains production acceptance gate and publish promotes GitHub latest',()=>{
    const source=readFileSync(new URL('./publish-vncode-stable.mjs',import.meta.url),'utf8');
    assert.match(source,/if\(mode==='publish'\)\s*\{[\s\S]*?validateLicenseReadiness/);
    assert.match(source,/make_latest:'true'/);
    assert.match(source,/validatePublishedStable\(api\('releases\/latest'\),version\)/);
    assert.match(source,/Never overwrite an already published version/);
});
test('installer proof retains the verified Windows Java origin after a probe-only repair',()=>{
 const summary={tests:1744,failures:0,errors:0,skipped:2};const sha='a'.repeat(40);
 const proof={version:'1.4.17',sourceCommit:sha,originRunId:origin.run,originSourceCommit:origin.sha,artifactId:origin.artifact,archiveSha256:origin.digest,applicationAndJavaTestInputsUnchanged:true,javaExecutedInOriginRun:true,nativeChecksMustRunAgain:true,fixtureClassesCompiledWithoutChangingJar:true,applicationJarSha256:'c'.repeat(64),tests:summary};
 validateReusedJava(proof,'1.4.17',sha,summary);
 for(const patch of [{sourceCommit:'b'.repeat(40)},{applicationAndJavaTestInputsUnchanged:false},{fixtureClassesCompiledWithoutChangingJar:false},{originRunId:1},{archiveSha256:'b'.repeat(64)},{tests:{...summary,tests:1743}}])assert.throws(()=>validateReusedJava({...proof,...patch},'1.4.17',sha,summary));
});
test('owner publication is limited to the exact requested 1.4.18 build and records incomplete live acceptance honestly',()=>{
 const sha='e77a746128fa51f183a2a193275b6b1ef894c251',runId='39102138545';
 const decision={version:'1.4.18',sourceCommit:sha,runId:Number(runId),ownerRequestedPublication:true,ownerInstruction:'xuất bản cập nhật mới',ownerInformedOfUnverifiedChecks:true,standardFullAcceptanceClaimed:false,productionLicenseAcceptance:false,enterpriseCryptoProSigningAccepted:false,marketplaceLiveGtinWritesAccepted:false,installerSha256:'a'.repeat(64),installerBytes:145000000};
 const status={version:'1.4.18',complete:false,licenseIntegration:{productionReady:false,changedInThisVersion:false},validation:{liveCryptoProSigningThisVersion:false,liveMarketplaceGtinWriteThisVersion:false,productionLicenseVerified:false,windowsBuild:{state:'passed',runId:Number(runId)},registrationPromptReview:{suggestionsReviewedInTable:true,applyBelowTableVerified:true,ambiguousValuesNotInvented:true}}};
 const args=['1.4.18',sha,runId];validateOwnerApproved1418(decision,status,...args);
 for(const patch of [{ownerRequestedPublication:false},{ownerInformedOfUnverifiedChecks:false},{standardFullAcceptanceClaimed:true},{productionLicenseAcceptance:true},{enterpriseCryptoProSigningAccepted:true},{marketplaceLiveGtinWritesAccepted:true},{installerSha256:'invalid'},{installerBytes:0},{sourceCommit:'b'.repeat(40)},{runId:1}])assert.throws(()=>validateOwnerApproved1418({...decision,...patch},status,...args));
 for(const invalid of [['1.4.19',sha,runId],['1.4.18','b'.repeat(40),runId],['1.4.18',sha,'1']])assert.throws(()=>validateOwnerApproved1418(decision,status,...invalid));
 for(const field of ['liveCryptoProSigningThisVersion','liveMarketplaceGtinWriteThisVersion','productionLicenseVerified'])assert.throws(()=>validateOwnerApproved1418(decision,{...status,validation:{...status.validation,[field]:true}},...args));
 for(const field of ['suggestionsReviewedInTable','applyBelowTableVerified','ambiguousValuesNotInvented'])assert.throws(()=>validateOwnerApproved1418(decision,{...status,validation:{...status.validation,registrationPromptReview:{...status.validation.registrationPromptReview,[field]:false}}},...args));
 assert.throws(()=>validateOwnerApproved1418(decision,{...status,complete:true},...args));
 assert.throws(()=>validateOwnerApproved1418(decision,{...status,licenseIntegration:{...status.licenseIntegration,productionReady:true}},...args));
});
test('a corrected Windows fixture rerun cannot hide its original error or claim unchanged tests were freshly executed',()=>{
 const sha='a'.repeat(40),summary={tests:1748,failures:0,errors:0,skipped:2};
 const proof={kind:'fixture-cleanup-only-recovery',version:'1.4.18',sourceCommit:sha,applicationSourceCommit:'62a5d0ea5f1abaf0ea638319b0b18231dfd0c238',originRunId:39099056215,originArtifactId:12126526148,originArchiveSha256:'ae82ace1389c81bac696ba57f1d53029a99eaa2ec10d02a5b3cedb6d66a97d65',originSuite:{tests:1748,failures:0,errors:1,skipped:2},originNamedTests:1747,originalContextFailureRetainedInProvenance:true,failedFixtureClassRerunPassed:true,newHandleCleanupTestPassed:true,applicationAndDependenciesUnchanged:true,originalAssertionsUnchanged:true,nativeChecksMustRunAgain:true,packageCompiledAfterFocusedTests:true,freshClasses:['com.vncode.app.ui.FxmlSmokeTest','com.vncode.app.testsupport.FinanceTestCleanupTest'],freshTestsExecuted:56,reusedPassingTests:1692,freshlyPackagedJarSha256:'b'.repeat(64),tests:summary};
 validateFixtureRecovery(proof,'1.4.18',sha,summary);
 for(const patch of [{originSuite:{...proof.originSuite,errors:0}},{applicationAndDependenciesUnchanged:false},{failedFixtureClassRerunPassed:false},{newHandleCleanupTestPassed:false},{originalAssertionsUnchanged:false},{freshClasses:[]},{freshTestsExecuted:55},{reusedPassingTests:1691},{sourceCommit:'c'.repeat(40)},{originRunId:1},{originArchiveSha256:'c'.repeat(64)}])assert.throws(()=>validateFixtureRecovery({...proof,...patch},'1.4.18',sha,summary));
});
