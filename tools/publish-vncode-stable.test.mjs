import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {validateStableAsset,validatePublishedStable,validateReusedJava,validateOwnerApproved1418,validateOwnerApproved1419,validateOwnerRequest1419,validateOwnerRequested150,validateOwnerRequest151,validateOwnerRequested151,validateFixtureRecovery} from './publish-vncode-stable.mjs';
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

test('1.4.19 owner publication requires the exact fresh build and distinguishes live API from desktop writes',()=>{
 const sha='d25718c6a69f94f08d9515d36f7444650b1eebd1',runId='39114287816';
 const decision={version:'1.4.19',sourceCommit:sha,runId:Number(runId),ownerRequestedPublication:true,
  ownerInstruction:'phát hành bản mới nhưng báo cáo trước cho tôi những gì chỉnh sửa tôi giao đã sửa chưa',
  ownerInformedOfUnverifiedChecks:true,requestReportDeliveredBeforePublication:true,standardFullAcceptanceClaimed:false,
  productionLicenseAcceptance:false,enterpriseCryptoProSigningAccepted:false,licensedDesktopGtinWriteAccepted:false,
  liveApiWbGtinWriteVerified:true,installerSha256:'a'.repeat(64),installerBytes:145000000};
 const status={version:'1.4.19',complete:false,databaseSchema:6,licenseIntegration:{productionReady:false,changedInThisVersion:false},
  liveWbApiGtinWriteVerified:true,liveLicensedDesktopGtinWriteVerified:false,requestReport:'VN-code-1.4.19-request-report.md',
  validation:{productionLicenseVerified:false,liveCryptoProSigningThisVersion:false,liveLicensedDesktopGtinWriteThisVersion:false,liveApiWbGtinWriteThisVersion:true,
   windowsBuild:{state:'passed',runId:Number(runId),sourceCommit:sha,freshFullJavaExecutionRequired:true,upgradeFrom1418Passed:true}}};
 validateOwnerApproved1419(decision,status,'1.4.19',sha,runId);
 const pending={...decision,phase:'authorized-awaiting-verified-build',installerSha256:null,installerBytes:null};
 validateOwnerRequest1419(pending,'1.4.19',sha,runId);
 assert.throws(()=>validateOwnerApproved1419(pending,status,'1.4.19',sha,runId),'A request is not proof of a verified installer');
 assert.throws(()=>validateOwnerRequest1419(pending,'1.4.20',sha,runId));
 assert.throws(()=>validateOwnerRequest1419(pending,'1.4.19',sha,'1'));
 for(const patch of [{ownerRequestedPublication:false},{ownerInformedOfUnverifiedChecks:false},{requestReportDeliveredBeforePublication:false},
  {standardFullAcceptanceClaimed:true},{productionLicenseAcceptance:true},{enterpriseCryptoProSigningAccepted:true},
  {licensedDesktopGtinWriteAccepted:true},{liveApiWbGtinWriteVerified:false},{installerSha256:'bad'},{installerBytes:0},{runId:1},{sourceCommit:'b'.repeat(40)}])
  assert.throws(()=>validateOwnerApproved1419({...decision,...patch},status,'1.4.19',sha,runId));
 for(const patch of [{complete:true},{databaseSchema:5},{liveLicensedDesktopGtinWriteVerified:true},{liveWbApiGtinWriteVerified:false}])
  assert.throws(()=>validateOwnerApproved1419(decision,{...status,...patch},'1.4.19',sha,runId));
 for(const patch of [{state:'failed'},{runId:1},{sourceCommit:'b'.repeat(40)},{freshFullJavaExecutionRequired:false},{upgradeFrom1418Passed:false}])
  assert.throws(()=>validateOwnerApproved1419(decision,{...status,validation:{...status.validation,windowsBuild:{...status.validation.windowsBuild,...patch}}},'1.4.19',sha,runId));
 for(const [version,source,run] of [['1.4.18',sha,runId],['1.4.20',sha,runId],['1.4.19','b'.repeat(40),runId],['1.4.19',sha,'1']])
  assert.throws(()=>validateOwnerApproved1419(decision,status,version,source,run));
});

test('requested 1.5.0 publication binds its verified build without claiming private or new live-write acceptance',()=>{
 const version='1.5.0',sha='2eea069123737e2c9f7bcddcecaa51f21a1645dc',runId='39130866288';
 const java={tests:1792,failures:0,errors:0,skipped:2,passed:1790};
 const decision={version,sourceCommit:sha,runId:Number(runId),phase:'verified-for-publication',ownerRequestedPublication:true,
  ownerInstruction:'các mục vừa chỉnh sửa cũng đưa vào bản cập nhật mới, bản này có tên 1.5.0',
  requestReportDeliveredBeforePublication:true,unverifiedChecksDisclosedBeforePublication:true,standardFullAcceptanceClaimed:false,
  productionLicenseAcceptance:false,enterpriseCryptoProSigningAccepted:false,licensedDesktopGtinWriteAccepted:false,
  liveApiWbGtinWriteThisVersion:false,liveApiOzonGtinWriteThisVersion:false,liveCatalogCardCreatedThisVersion:false,
  bulkMarketplaceKizValidationOnImport:false,installerSha256:'a'.repeat(64),installerBytes:145000000,windowsJava:java};
 const status={version,complete:false,databaseSchema:6,licenseIntegration:{productionReady:false,changedInThisVersion:false},
  bulkMarketplaceKizValidationOnImport:false,requestReport:'VN-code-1.5.0-request-report.md',
  validation:{productionLicenseVerified:false,liveCryptoProSigningThisVersion:false,liveLicensedDesktopGtinWriteThisVersion:false,
   liveApiWbGtinWriteThisVersion:false,liveApiOzonGtinWriteThisVersion:false,liveCatalogCardCreatedThisVersion:false,
   windowsBuild:{state:'passed',runId:Number(runId),sourceCommit:sha,freshFullJavaExecutionRequired:true,upgradeFrom1419Passed:true,fullJavaSuite:java}}};
 validateOwnerRequested150(decision,status,version,sha,runId);
 for(const patch of [{phase:'authorized-awaiting-verified-build'},{ownerRequestedPublication:false},{requestReportDeliveredBeforePublication:false},
  {unverifiedChecksDisclosedBeforePublication:false},{standardFullAcceptanceClaimed:true},{productionLicenseAcceptance:true},
  {enterpriseCryptoProSigningAccepted:true},{licensedDesktopGtinWriteAccepted:true},{liveApiWbGtinWriteThisVersion:true},
  {liveApiOzonGtinWriteThisVersion:true},{liveCatalogCardCreatedThisVersion:true},{bulkMarketplaceKizValidationOnImport:true},
  {installerSha256:'invalid'},{installerBytes:0},{windowsJava:{...java,errors:1}},{sourceCommit:'b'.repeat(40)},{runId:1}])
  assert.throws(()=>validateOwnerRequested150({...decision,...patch},status,version,sha,runId));
 for(const patch of [{state:'in_progress'},{runId:1},{sourceCommit:'b'.repeat(40)},{freshFullJavaExecutionRequired:false},
  {upgradeFrom1419Passed:false},{fullJavaSuite:{...java,failures:1}}])
  assert.throws(()=>validateOwnerRequested150(decision,{...status,validation:{...status.validation,windowsBuild:{...status.validation.windowsBuild,...patch}}},version,sha,runId));
 for(const field of ['productionLicenseVerified','liveCryptoProSigningThisVersion','liveLicensedDesktopGtinWriteThisVersion',
  'liveApiWbGtinWriteThisVersion','liveApiOzonGtinWriteThisVersion','liveCatalogCardCreatedThisVersion'])
  assert.throws(()=>validateOwnerRequested150(decision,{...status,validation:{...status.validation,[field]:true}},version,sha,runId));
 for(const patch of [{complete:true},{bulkMarketplaceKizValidationOnImport:true},{databaseSchema:5}])
  assert.throws(()=>validateOwnerRequested150(decision,{...status,...patch},version,sha,runId));
 for(const args of [['1.5.1',sha,runId],[version,'b'.repeat(40),runId],[version,sha,'1']])
  assert.throws(()=>validateOwnerRequested150(decision,status,...args));
});

test('1.5.1 publication binds the exact fresh build and keeps live acceptance limits explicit',()=>{
 const version='1.5.1',sha='96e20614882488a2d60319fc29f4ca62b6cb9b81',runId='39286749991';
 const java={tests:1924,failures:0,errors:0,skipped:2,passed:1922};
 const decision={version,sourceCommit:sha,runId,phase:'verified-for-publication',ownerRequestedPublication:true,
  ownerInstruction:'sau khi xong hãy đưa bản mới này lên latest để thông báo xuống app cho seller cập nhật',
  unverifiedChecksDisclosedBeforePublication:true,standardFullAcceptanceClaimed:false,productionLicenseAcceptance:false,
  enterpriseCryptoProSigningAccepted:false,licensedDesktopGtinWriteAccepted:false,liveApiWbGtinWriteThisVersion:false,
  liveApiOzonGtinWriteThisVersion:false,liveCatalogCardCreatedThisVersion:false,liveCatalogCardRepairedThisVersion:false,
  installerSha256:'a'.repeat(64),installerBytes:145000000,windowsJava:java};
 const status={version,complete:false,databaseSchema:6,requestReport:'VN-code-1.5.1-request-report.md',licenseIntegration:{productionReady:false,changedInThisVersion:false},
  validation:{productionLicenseVerified:false,liveCryptoProSigningThisVersion:false,liveLicensedDesktopGtinWriteThisVersion:false,
   liveApiWbGtinWriteThisVersion:false,liveApiOzonGtinWriteThisVersion:false,liveCatalogCardCreatedThisVersion:false,liveCatalogCardRepairedThisVersion:false,
   windowsBuild:{state:'passed',runId,sourceCommit:sha,freshFullJavaExecutionRequired:true,upgradeFrom150Passed:true,fullJavaSuite:java}}};
 validateOwnerRequested151(decision,status,version,sha,runId);
 const pending={...decision,phase:'authorized-awaiting-verified-build',installerSha256:null,installerBytes:null,windowsJava:null};
 const pendingStatus={...status,validation:{...status.validation,windowsBuild:{state:'running',runId,sourceCommit:sha,freshFullJavaExecutionRequired:true}}};
 validateOwnerRequest151(pending,pendingStatus,version,sha,runId);
 assert.throws(()=>validateOwnerRequested151(pending,pendingStatus,version,sha,runId),'Authorization alone is not a verified installer');
 for(const patch of [{installerSha256:'a'.repeat(64)},{installerBytes:145000000},{windowsJava:java},{ownerRequestedPublication:false},
  {unverifiedChecksDisclosedBeforePublication:false},{standardFullAcceptanceClaimed:true},{liveCatalogCardRepairedThisVersion:true},
  {sourceCommit:'b'.repeat(40)},{runId:'1'}])
  assert.throws(()=>validateOwnerRequest151({...pending,...patch},pendingStatus,version,sha,runId));
 for(const patch of [{state:'passed'},{state:'failed'},{fullJavaSuite:java},{upgradeFrom150Passed:true},{sourceCommit:'b'.repeat(40)},{runId:'1'}])
  assert.throws(()=>validateOwnerRequest151(pending,{...pendingStatus,validation:{...pendingStatus.validation,windowsBuild:{...pendingStatus.validation.windowsBuild,...patch}}},version,sha,runId));
 for(const patch of [{phase:'awaiting-build'},{ownerRequestedPublication:false},{standardFullAcceptanceClaimed:true},
  {enterpriseCryptoProSigningAccepted:true},{liveCatalogCardRepairedThisVersion:true},{installerSha256:null},{windowsJava:{...java,errors:1}},
  {windowsJava:{tests:1923,failures:0,errors:0,skipped:2,passed:1921}}])
  assert.throws(()=>validateOwnerRequested151({...decision,...patch},status,version,sha,runId));
 assert.throws(()=>validateOwnerRequested151(decision,status,version,'a'.repeat(40),runId));
 assert.throws(()=>validateOwnerRequested151(decision,status,version,sha,'39277937973'));
 assert.throws(()=>validateOwnerRequested151(decision,{...status,validation:{...status.validation,windowsBuild:{...status.validation.windowsBuild,upgradeFrom150Passed:false}}},version,sha,runId));
});
