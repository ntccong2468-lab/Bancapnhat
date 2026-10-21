import {origin151,plan151} from './reuse-verified-windows-java-151.mjs';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {validateStableAsset,validatePublishedStable,validateReusedJava,validateOwnerApproved1418,validateOwnerApproved1419,validateOwnerRequest1419,validateOwnerRequested150,validateOwnerRequest151,validateOwnerRequested151,validateOwnerRequest152,validateOwnerRequested152,validateOwnerRequest153,validateOwnerRequested153,validateOwner153UpgradeProof,owner154Binding,owner154RequiredNativeProofs,validateOwner154UpgradeProof,validateOwner154Evidence,validateOwnerRequest154,validateOwnerRequested154,formatOwner154RequestReport,validateFixtureRecovery} from './publish-vncode-stable.mjs';
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
 const version='1.5.1',sha='f9da70ca98734b8c5daed0a6ef49b38df9723f54',runId='39294782641';
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
 const reusedStatus={...status,validation:{...status.validation,windowsBuild:{...status.validation.windowsBuild,freshFullJavaExecutionRequired:false,javaExecutionPlan:plan151}}};
 const reuseProof={version,sourceCommit:sha,originRunId:origin151.run,originSourceCommit:origin151.sha,artifactId:origin151.artifact,archiveSha256:origin151.digest,applicationJarSha256:origin151.jar,applicationAndJavaTestInputsUnchanged:true,javaExecutedInOriginRun:true,nativeChecksMustRunAgain:true,fixtureClassesCompiledWithoutChangingJar:true,tests:{tests:1924,failures:0,errors:0,skipped:2}};
 const reusedDecision={...decision,freshJavaExecutedInThisRun:false,javaVerificationOrigin:reuseProof};
 validateOwnerRequested151(reusedDecision,reusedStatus,version,sha,runId);
 assert.throws(()=>validateOwnerRequested151(decision,reusedStatus,version,sha,runId));
 assert.throws(()=>validateOwnerRequested151({...reusedDecision,freshJavaExecutedInThisRun:true},reusedStatus,version,sha,runId));
 assert.throws(()=>validateOwnerRequested151({...reusedDecision,javaVerificationOrigin:{...reuseProof,applicationJarSha256:'f'.repeat(64)}},reusedStatus,version,sha,runId));
 assert.throws(()=>validateOwnerRequested151(reusedDecision,{...reusedStatus,validation:{...reusedStatus.validation,windowsBuild:{...reusedStatus.validation.windowsBuild,javaExecutionPlan:{...plan151,runId:1}}}},version,sha,runId));
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

test('1.5.2 requires fresh Java, latest upgrade and honest live limits',()=>{
 const version='1.5.2',sha='f9da70ca98734b8c5daed0a6ef49b38df9723f54',runId='39294782641';
 const java={tests:1940,failures:0,errors:0,skipped:2,passed:1938};
 const decision={version,sourceCommit:sha,runId,phase:'verified-for-publication',ownerRequestedPublication:true,
  ownerInstruction:'xong phần này thì đóng gói bản cập nhật mới lên latest để phát hành xuống app.',
  unverifiedChecksDisclosedBeforePublication:true,standardFullAcceptanceClaimed:false,productionLicenseAcceptance:false,
  enterpriseCryptoProSigningAccepted:false,licensedDesktopGtinWriteAccepted:false,liveApiWbGtinWriteThisVersion:false,
  liveApiOzonGtinWriteThisVersion:false,liveCatalogCardCreatedThisVersion:false,liveCatalogCardRepairedThisVersion:false,
  installerSha256:'a'.repeat(64),installerBytes:145000000,windowsJava:java};
 const status={version,complete:false,databaseSchema:6,requestReport:'VN-code-1.5.2-request-report.md',licenseIntegration:{productionReady:false,changedInThisVersion:false},
  validation:{productionLicenseVerified:false,liveCryptoProSigningThisVersion:false,liveLicensedDesktopGtinWriteThisVersion:false,
   liveApiWbGtinWriteThisVersion:false,liveApiOzonGtinWriteThisVersion:false,liveCatalogCardCreatedThisVersion:false,liveCatalogCardRepairedThisVersion:false,
   windowsBuild:{state:'passed',runId,sourceCommit:sha,freshFullJavaExecutionRequired:true,upgradeFrom151Passed:true,fullJavaSuite:java}}};
 validateOwnerRequested152(decision,status,version,sha,runId);
 assert.throws(()=>validateOwnerRequested152(decision,{...status,validation:{...status.validation,windowsBuild:{...status.validation.windowsBuild,freshFullJavaExecutionRequired:false}}},version,sha,runId));
 const pending={...decision,phase:'authorized-awaiting-verified-build',installerSha256:null,installerBytes:null,windowsJava:null};
 const pendingStatus={...status,validation:{...status.validation,windowsBuild:{state:'running',runId,sourceCommit:sha,freshFullJavaExecutionRequired:true}}};
 validateOwnerRequest152(pending,pendingStatus,version,sha,runId);
 assert.throws(()=>validateOwnerRequested152(pending,pendingStatus,version,sha,runId),'Authorization alone is not a verified installer');
 for(const patch of [{installerSha256:'a'.repeat(64)},{installerBytes:145000000},{windowsJava:java},{ownerRequestedPublication:false},
  {unverifiedChecksDisclosedBeforePublication:false},{standardFullAcceptanceClaimed:true},{liveCatalogCardRepairedThisVersion:true},
  {sourceCommit:'b'.repeat(40)},{runId:'1'}])
  assert.throws(()=>validateOwnerRequest152({...pending,...patch},pendingStatus,version,sha,runId));
 for(const patch of [{state:'passed'},{state:'failed'},{fullJavaSuite:java},{upgradeFrom151Passed:true},{sourceCommit:'b'.repeat(40)},{runId:'1'}])
  assert.throws(()=>validateOwnerRequest152(pending,{...pendingStatus,validation:{...pendingStatus.validation,windowsBuild:{...pendingStatus.validation.windowsBuild,...patch}}},version,sha,runId));
 for(const patch of [{phase:'awaiting-build'},{ownerRequestedPublication:false},{standardFullAcceptanceClaimed:true},
  {enterpriseCryptoProSigningAccepted:true},{liveCatalogCardRepairedThisVersion:true},{installerSha256:null},{windowsJava:{...java,errors:1}},
  {windowsJava:{tests:1939,failures:0,errors:0,skipped:2,passed:1937}}])
  assert.throws(()=>validateOwnerRequested152({...decision,...patch},status,version,sha,runId));
 assert.throws(()=>validateOwnerRequested152(decision,status,version,'a'.repeat(40),runId));
 assert.throws(()=>validateOwnerRequested152(decision,status,version,sha,'39277937973'));
 assert.throws(()=>validateOwnerRequested152(decision,{...status,validation:{...status.validation,windowsBuild:{...status.validation.windowsBuild,upgradeFrom151Passed:false}}},version,sha,runId));
});

test('1.5.3 requires fresh Java, latest upgrade and honest live limits',()=>{
 const version='1.5.3',sha='69ffc7ecb60a91c874510522c4aae3c5be069ccd',runId='39396769729';
 const java={tests:1990,failures:0,errors:0,skipped:2,passed:1988};
 const decision={version,sourceCommit:sha,runId,phase:'verified-for-publication',ownerRequestedPublication:true,
  ownerInstruction:'xong phần này thì đóng gói bản cập nhật mới lên latest để phát hành xuống app.',
  unverifiedChecksDisclosedBeforePublication:true,standardFullAcceptanceClaimed:false,productionLicenseAcceptance:false,
  enterpriseCryptoProSigningAccepted:false,licensedDesktopGtinWriteAccepted:false,liveApiWbGtinWriteThisVersion:false,
  liveApiOzonGtinWriteThisVersion:false,liveCatalogCardCreatedThisVersion:false,liveCatalogCardRepairedThisVersion:false,
  installerSha256:'a'.repeat(64),installerBytes:145000000,windowsJava:java,verifiedNativeProofs:['upgrade-stable-152-smoke.json'],passiveExeHandoffVerified:true};
 const status={version,complete:false,databaseSchema:6,requestReport:'VN-code-1.5.3-request-report.md',licenseIntegration:{productionReady:false,changedInThisVersion:false},
  validation:{productionLicenseVerified:false,liveCryptoProSigningThisVersion:false,liveLicensedDesktopGtinWriteThisVersion:false,
   liveApiWbGtinWriteThisVersion:false,liveApiOzonGtinWriteThisVersion:false,liveCatalogCardCreatedThisVersion:false,liveCatalogCardRepairedThisVersion:false,
   windowsBuild:{state:'passed',runId,sourceCommit:sha,freshFullJavaExecutionRequired:true,allNativeChecksMustRunAgain:true,allNativeChecksPassed:true,installerPackaged:true,passiveExeHandoffVerified:true,upgradeFrom152Passed:true,fullJavaSuite:java}}};
 validateOwnerRequested153(decision,status,version,sha,runId);
 assert.throws(()=>validateOwnerRequested153(decision,{...status,validation:{...status.validation,windowsBuild:{...status.validation.windowsBuild,freshFullJavaExecutionRequired:false}}},version,sha,runId));
 const pending={...decision,phase:'authorized-awaiting-verified-build',installerSha256:null,installerBytes:null,windowsJava:null,verifiedNativeProofs:null,passiveExeHandoffVerified:null};
 const pendingStatus={...status,validation:{...status.validation,windowsBuild:{state:'running',runId,sourceCommit:sha,freshFullJavaExecutionRequired:true,allNativeChecksMustRunAgain:true}}};
 validateOwnerRequest153(pending,pendingStatus,version,sha,runId);
 assert.throws(()=>validateOwnerRequested153(pending,pendingStatus,version,sha,runId),'Authorization alone is not a verified installer');
 for(const patch of [{installerSha256:'a'.repeat(64)},{installerBytes:145000000},{windowsJava:java},{verifiedNativeProofs:[]},{passiveExeHandoffVerified:true},{ownerRequestedPublication:false},
  {unverifiedChecksDisclosedBeforePublication:false},{standardFullAcceptanceClaimed:true},{liveCatalogCardRepairedThisVersion:true},
  {sourceCommit:'b'.repeat(40)},{runId:'1'}])
  assert.throws(()=>validateOwnerRequest153({...pending,...patch},pendingStatus,version,sha,runId));
 for(const patch of [{state:'passed'},{state:'failed'},{fullJavaSuite:java},{upgradeFrom152Passed:true},{allNativeChecksPassed:true},{installerPackaged:true},{passiveExeHandoffVerified:true},{allNativeChecksMustRunAgain:false},{sourceCommit:'b'.repeat(40)},{runId:'1'}])
  assert.throws(()=>validateOwnerRequest153(pending,{...pendingStatus,validation:{...pendingStatus.validation,windowsBuild:{...pendingStatus.validation.windowsBuild,...patch}}},version,sha,runId));
 for(const patch of [{phase:'awaiting-build'},{ownerRequestedPublication:false},{standardFullAcceptanceClaimed:true},
  {enterpriseCryptoProSigningAccepted:true},{liveCatalogCardRepairedThisVersion:true},{verifiedNativeProofs:[]},{passiveExeHandoffVerified:false},{installerSha256:null},{windowsJava:{...java,errors:1}},
  {windowsJava:{tests:1989,failures:0,errors:0,skipped:2,passed:1987}}])
  assert.throws(()=>validateOwnerRequested153({...decision,...patch},status,version,sha,runId));
 assert.throws(()=>validateOwnerRequested153(decision,status,version,'a'.repeat(40),runId));
 assert.throws(()=>validateOwnerRequested153(decision,status,version,sha,'39277937973'));
 assert.throws(()=>validateOwnerRequested153(decision,{...status,validation:{...status.validation,windowsBuild:{...status.validation.windowsBuild,upgradeFrom152Passed:false}}},version,sha,runId));
 for(const patch of [{allNativeChecksPassed:false},{installerPackaged:false},{passiveExeHandoffVerified:false},{allNativeChecksMustRunAgain:false}])
  assert.throws(()=>validateOwnerRequested153(decision,{...status,validation:{...status.validation,windowsBuild:{...status.validation.windowsBuild,...patch}}},version,sha,runId));
 const otherSha='b'.repeat(40),otherRun='39382705240';
 assert.throws(()=>validateOwnerRequested153({...decision,sourceCommit:otherSha},{...status,validation:{...status.validation,windowsBuild:{...status.validation.windowsBuild,sourceCommit:otherSha}}},version,otherSha,runId),'A consistent but different source is outside this authorization');
 assert.throws(()=>validateOwnerRequested153({...decision,runId:otherRun},{...status,validation:{...status.validation,windowsBuild:{...status.validation.windowsBuild,runId:otherRun}}},version,sha,otherRun),'The cancelled run is outside this authorization');
});

test('1.5.3 must verify and ship the actual 1.5.2 upgrade proof before release mutation',()=>{
 const source=readFileSync(new URL('./publish-vncode-stable.mjs',import.meta.url),'utf8');
 assert.match(source,/if\(version\.localeCompare\('1\.5\.2','en',\{numeric:true\}\)>0\) \{\s+const upgrade152=json\(await readFile\(path\.join\(directory,'upgrade-stable-152-smoke\.json'\)\)\);/);
 assert.match(source,/if\(mode==='owner-requested-153'\) \{\s+validateOwner153UpgradeProof\(upgrade152,version,digest\);\s+passiveExeHandoffVerified=upgrade152\.passiveExeHandoffVerified;/);
 assert.match(source,/else validateUpgradeSmoke\(upgrade152,version,'1\.5\.2'\);\s+proofFiles\.push\('upgrade-stable-152-smoke\.json'\);/);
 assert.ok(source.indexOf("'upgrade-stable-152-smoke.json'",source.indexOf('async function main'))<source.indexOf("const refs=api("));
 const workflow=readFileSync(new URL('../.github/workflows/publish-vncode-stable.yml',import.meta.url),'utf8');
 assert.match(workflow,/options: \[[^\]]*owner-requested-153(?:,|\])/);
 assert.match(source,/if\(mode==='owner-requested-153'\) \{\s+assert\.ok\(!logs\.includes\('REUSED_WINDOWS_JAVA:'\)&&!logs\.includes\('RECONCILED_WINDOWS_JAVA:'\)/);
});

test('1.5.3 passive EXE handoff proof still requires preserved data and the exact prior installer identity',()=>{
 const proof={appName:'VN code',version:'1.5.3',fromVersion:'1.5.2',result:'passed',installerUpgradeUuid:'8CBBA0E2-6E73-4F56-9101-6BC0948D3C72',singleRegistration:true,dataPreserved:true,liveMarketplaceMutations:false,fingerprint:'a'.repeat(64),passiveExeHandoffVerified:true,passiveExeInstallerSha256:'b'.repeat(64),passiveExeEmbeddedMsiSha256:'c'.repeat(64)};
 validateOwner153UpgradeProof(proof,'1.5.3','b'.repeat(64));
 for(const patch of [{passiveExeHandoffVerified:false},{passiveExeHandoffVerified:undefined},{fromVersion:'1.5.1'},{version:'1.5.2'},{dataPreserved:false},{singleRegistration:false},{liveMarketplaceMutations:true},{installerUpgradeUuid:'other'},{fingerprint:'bad'},{passiveExeInstallerSha256:'a'.repeat(64)},{passiveExeEmbeddedMsiSha256:'bad'}])
  assert.throws(()=>validateOwner153UpgradeProof({...proof,...patch},'1.5.3','b'.repeat(64)));
 assert.throws(()=>validateOwner153UpgradeProof(proof,'1.5.4','b'.repeat(64)));
 assert.throws(()=>validateOwner153UpgradeProof(proof,'1.5.3'));
 assert.throws(()=>validateOwner153UpgradeProof(proof,'1.5.3','a'.repeat(64)));
});

function owner154Fixture(pending=false){
 const version='1.5.4',sha=owner154Binding.sourceCommit??'a'.repeat(40),runId=String(owner154Binding.runId??'123456789');
 const java={tests:2088,failures:0,errors:0,skipped:2,passed:2086};
 const decision={version,sourceCommit:sha,sourceRuntimeIsFinal:true,runId,phase:pending?'authorized-awaiting-verified-build':'verified-for-publication',
  ownerRequestedPublication:true,ownerInstruction:'xong phần này thì đóng gói bản cập nhật mới lên latest để phát hành xuống app.',
  unverifiedChecksDisclosedBeforePublication:true,standardFullAcceptanceClaimed:false,
  productionLicenseAcceptance:false,enterpriseCryptoProSigningAccepted:false,licensedDesktopGtinWriteAccepted:false,
  liveApiWbGtinWriteThisVersion:false,liveApiOzonGtinWriteThisVersion:false,liveCatalogCardCreatedThisVersion:false,
  liveCatalogCardRepairedThisVersion:false,liveKizPurchaseThisVersion:false,livePaidKizOrderThisVersion:false,liveApiWbKizPurchaseThisVersion:false,liveApiOzonKizPurchaseThisVersion:false,
  installerSha256:pending?null:'b'.repeat(64),installerBytes:pending?null:146900000,windowsJava:pending?null:java,
  verifiedNativeProofs:pending?null:[...owner154RequiredNativeProofs],passiveExeHandoffVerified:pending?null:true};
 const build={state:pending?'running':'passed',runId,sourceCommit:sha,freshFullJavaExecutionRequired:true,allNativeChecksMustRunAgain:true,
  ...(pending?{}:{upgradeFrom153Passed:true,allNativeChecksPassed:true,installerPackaged:true,passiveExeHandoffVerified:true,fullJavaSuite:java})};
 const status={version,sourceCommit:sha,sourceRuntimeIsFinal:true,baseline:'v1.5.3',complete:false,databaseSchema:6,
  requestReport:'VN-code-1.5.4-request-report.md',licenseIntegration:{productionReady:false,changedInThisVersion:false},
  validation:{productionLicenseVerified:false,liveCryptoProSigningThisVersion:false,liveLicensedDesktopGtinWriteThisVersion:false,
   liveApiWbGtinWriteThisVersion:false,liveApiOzonGtinWriteThisVersion:false,liveCatalogCardCreatedThisVersion:false,
   liveCatalogCardRepairedThisVersion:false,liveKizPurchaseThisVersion:false,livePaidKizOrderThisVersion:false,liveApiWbKizPurchaseThisVersion:false,liveApiOzonKizPurchaseThisVersion:false,
   linuxFullSuite:{state:'passed',sourceCommit:sha,fullSuiteExecuted:true,cleanVerifyExecuted:true,tests:2088,passed:2086,skipped:2,failures:0,errors:0},windowsBuild:build}};
 return {version,sha,runId,java,decision,status};
}

test('1.5.4 evidence cannot fabricate build results or claim a production purchase',()=>{
 for(const pending of [true,false]){
  const f=owner154Fixture(pending),{decision,status,version,sha,runId}=f;
  validateOwner154Evidence(decision,status,version,sha,runId);
  for(const patch of [{sourceCommit:null},{runId:null},{sourceRuntimeIsFinal:false},{ownerRequestedPublication:false},
   {standardFullAcceptanceClaimed:true},{unverifiedChecksDisclosedBeforePublication:false},{productionLicenseAcceptance:true},
   {enterpriseCryptoProSigningAccepted:true},{liveKizPurchaseThisVersion:true},{livePaidKizOrderThisVersion:true},{liveApiWbKizPurchaseThisVersion:true},{liveApiOzonKizPurchaseThisVersion:true}])
   assert.throws(()=>validateOwner154Evidence({...decision,...patch},status,version,sha,runId));
  for(const patch of [{sourceCommit:'b'.repeat(40)},{sourceRuntimeIsFinal:false},{baseline:'v1.5.2'},{complete:true},
   {licenseIntegration:{productionReady:true,changedInThisVersion:false}}])
   assert.throws(()=>validateOwner154Evidence(decision,{...status,...patch},version,sha,runId));
  for(const patch of [{state:'running'},{sourceCommit:'b'.repeat(40)},{fullSuiteExecuted:false},{cleanVerifyExecuted:false},{tests:2087},{failures:1},{errors:1}])
   assert.throws(()=>validateOwner154Evidence(decision,{...status,validation:{...status.validation,linuxFullSuite:{...status.validation.linuxFullSuite,...patch}}},version,sha,runId));
  for(const field of ['productionLicenseVerified','liveCryptoProSigningThisVersion','liveKizPurchaseThisVersion','livePaidKizOrderThisVersion','liveApiWbKizPurchaseThisVersion','liveApiOzonKizPurchaseThisVersion'])
   assert.throws(()=>validateOwner154Evidence(decision,{...status,validation:{...status.validation,[field]:true}},version,sha,runId));
 }
 const f=owner154Fixture(true),{decision,status,version,sha,runId}=f;
 for(const patch of [{installerSha256:'b'.repeat(64)},{installerBytes:146900000},{windowsJava:f.java},{verifiedNativeProofs:[]},{passiveExeHandoffVerified:true}])
  assert.throws(()=>validateOwner154Evidence({...decision,...patch},status,version,sha,runId));
 for(const patch of [{state:'passed'},{fullJavaSuite:f.java},{freshFullJavaExecutionRequired:false},{allNativeChecksMustRunAgain:false},
  {upgradeFrom153Passed:true},{allNativeChecksPassed:true},{installerPackaged:true},{passiveExeHandoffVerified:true}])
  assert.throws(()=>validateOwner154Evidence(decision,{...status,validation:{...status.validation,windowsBuild:{...status.validation.windowsBuild,...patch}}},version,sha,runId));
 const verified=owner154Fixture();
 for(const patch of [{verifiedNativeProofs:owner154RequiredNativeProofs.filter(n=>n!=='upgrade-stable-152-smoke.json')},
  {verifiedNativeProofs:owner154RequiredNativeProofs.filter(n=>n!=='upgrade-stable-153-smoke.json')},
  {windowsJava:{...verified.java,tests:2087}},{windowsJava:{...verified.java,failures:1}},{windowsJava:{...verified.java,skipped:3}},
  {passiveExeHandoffVerified:false},{installerSha256:null}])
  assert.throws(()=>validateOwner154Evidence({...verified.decision,...patch},verified.status,verified.version,verified.sha,verified.runId));
 for(const patch of [{state:'running'},{upgradeFrom153Passed:false},{allNativeChecksPassed:false},{installerPackaged:false},{passiveExeHandoffVerified:false}])
  assert.throws(()=>validateOwner154Evidence(verified.decision,{...verified.status,validation:{...verified.status.validation,windowsBuild:{...verified.status.validation.windowsBuild,...patch}}},verified.version,verified.sha,verified.runId));
});

test('1.5.4 authorization rejects unbound preparation and any other consistent source or run',()=>{
 for(const pending of [true,false]){
  const {decision,status,version,sha,runId}=owner154Fixture(pending);
  if(owner154Binding.sourceCommit===null||owner154Binding.runId===null){
   assert.throws(()=>validateOwnerRequest154(decision,status,version,sha,runId));
   assert.throws(()=>validateOwnerRequested154(decision,status,version,sha,runId));
  }else{
   validateOwnerRequest154(decision,status,version,sha,runId);
   if(!pending)validateOwnerRequested154(decision,status,version,sha,runId);
  }
  const other='b'.repeat(40),otherRun='999999999';
  assert.throws(()=>validateOwnerRequest154({...decision,sourceCommit:other},{...status,sourceCommit:other,validation:{...status.validation,linuxFullSuite:{...status.validation.linuxFullSuite,sourceCommit:other},windowsBuild:{...status.validation.windowsBuild,sourceCommit:other}}},version,other,runId));
  assert.throws(()=>validateOwnerRequest154({...decision,runId:otherRun},{...status,validation:{...status.validation,windowsBuild:{...status.validation.windowsBuild,runId:otherRun}}},version,sha,otherRun));
 }
 const receipt=JSON.parse(readFileSync(new URL('../docs/validation/2026-10-20-vncode-154-owner-publication.json',import.meta.url),'utf8'));
 const status=JSON.parse(readFileSync(new URL('../docs/releases/VN-code-1.5.4-status.json',import.meta.url),'utf8'));
 if(receipt.sourceCommit===null||receipt.runId===null||status.validation.linuxFullSuite.state!=='passed')
  assert.throws(()=>validateOwnerRequest154(receipt,status,'1.5.4',receipt.sourceCommit,receipt.runId),'An actual unbound or unfinished Linux preparation record never authorizes publication');
});

test('1.5.4 upgrade proof binds immutable published1.5.3 and the exact target EXE',()=>{
 const proof={appName:'VN code',version:'1.5.4',fromVersion:'1.5.3',result:'passed',installerUpgradeUuid:'8CBBA0E2-6E73-4F56-9101-6BC0948D3C72',
  singleRegistration:true,dataPreserved:true,liveMarketplaceMutations:false,fingerprint:'a'.repeat(64),passiveExeHandoffVerified:true,
  passiveExeInstallerSha256:'b'.repeat(64),passiveExeEmbeddedMsiSha256:'c'.repeat(64),baselineTag:'v1.5.3',baselineInstallerBytes:146864128,
  baselineInstallerSha256:'ec608d3677581c12901ee702e516cb2eea2757a5a9ee8fd84a2ce2438e8a3f31'};
 validateOwner154UpgradeProof(proof,'1.5.4','b'.repeat(64));
 for(const patch of [{baselineTag:'v1.5.2'},{baselineInstallerBytes:146864129},{baselineInstallerSha256:'a'.repeat(64)},
  {fromVersion:'1.5.2'},{passiveExeHandoffVerified:false},{passiveExeInstallerSha256:'a'.repeat(64)},
  {passiveExeEmbeddedMsiSha256:'bad'},{dataPreserved:false},{singleRegistration:false},{liveMarketplaceMutations:true}])
  assert.throws(()=>validateOwner154UpgradeProof({...proof,...patch},'1.5.4','b'.repeat(64)));
 assert.throws(()=>validateOwner154UpgradeProof(proof,'1.5.5','b'.repeat(64)));
});

test('1.5.4 keeps all prior native proofs and verifies passive upgrade before release mutation',()=>{
 const source=readFileSync(new URL('./publish-vncode-stable.mjs',import.meta.url),'utf8');
 assert.equal(owner154RequiredNativeProofs.length,24);
 assert.ok(owner154RequiredNativeProofs.includes('upgrade-stable-152-smoke.json'));
 assert.ok(owner154RequiredNativeProofs.includes('upgrade-stable-153-smoke.json'));
 assert.ok(source.indexOf("validateOwner154UpgradeProof(upgrade153,version,digest)")<source.indexOf('const refs=api('));
 assert.match(source,/if\(mode==='owner-requested-154'\) \{\s+assert\.ok\(!logs\.includes\('REUSED_WINDOWS_JAVA:'\)&&!logs\.includes\('RECONCILED_WINDOWS_JAVA:'\)/);
 const workflow=readFileSync(new URL('../.github/workflows/publish-vncode-stable.yml',import.meta.url),'utf8');
 assert.match(workflow,/owner-requested-154(?:,|\])/);
});

test('1.5.4 final attached report preserves repair scope and replaces pending text with observed evidence',()=>{
 const f=owner154Fixture(),preamble='# Báo cáo VNCode 1.5.4\n\n## Phạm vi sửa\n\n- SCOPE: giữ dữ liệu khi sao lưu WAL và không mua KIZ trùng.\n\nGiữ license, TLS và chữ ký.';
 const template=preamble+'\n\n## Bản nền và kiểm tra bắt buộc\n\nWindows đang chạy. Latest hiện vẫn là 1.5.3. PENDING_SENTINEL\n\n## Chưa hoàn tất\n\nOLD_LIMITS_SENTINEL';
 const report=formatOwner154RequestReport(template,f.decision,f.status);
 assert.ok(report.startsWith(preamble+'\n\n## Kiểm thử bộ cài phát hành\n'));
 assert.ok(!report.includes('PENDING_SENTINEL')&&!report.includes('OLD_LIMITS_SENTINEL'));
 assert.ok(!report.includes('Windows đang chạy')&&!report.includes('Latest hiện vẫn là 1.5.3'));
 assert.ok(report.includes(f.sha)&&report.includes(`/actions/runs/${f.runId}`));
 assert.ok(report.includes('Linux `clean verify`: 2.088 kiểm thử, 2.086 đạt'));
 assert.ok(report.includes('Windows Java mới: 2.088 kiểm thử, 2.086 đạt'));
 assert.ok(report.includes('24 bằng chứng native và 19 ca nâng cấp'));
 assert.ok(report.includes('288 bố cục')&&report.includes('`/passive /norestart`'));
 assert.ok(report.includes('146.900.000 byte')&&report.includes(f.decision.installerSha256));
 assert.ok(report.includes('## Chưa hoàn tất')&&report.includes('mua KIZ trả phí thật')&&report.includes('CryptoPro doanh nghiệp'));
 assert.ok(report.includes('Mọi cờ nghiệm thu production tương ứng vẫn false'));
 assert.ok(report.includes('không chứng minh thông báo đã xuất hiện trên desktop seller'));
});

test('1.5.4 report formatting refuses pending or inconsistent evidence instead of inventing final results',()=>{
 const template='# Báo cáo VNCode 1.5.4\n\n## Phạm vi sửa\n\nSCOPE\n\n## Kiểm tra\n\nPending';
 const f=owner154Fixture();
 for(const broken of ['# Wrong release\n\n## Phạm vi sửa\n\nSCOPE\n\n## Kiểm tra\nPending',
  '# Báo cáo VNCode 1.5.4\n\n## Other scope\n\nSCOPE\n\n## Kiểm tra\nPending',
  '# Báo cáo VNCode 1.5.4\n\n## Phạm vi sửa\n\nUnseparated pending verification'])
  assert.throws(()=>formatOwner154RequestReport(broken,f.decision,f.status));
 const pending=owner154Fixture(true);
 assert.throws(()=>formatOwner154RequestReport(template,pending.decision,pending.status));
 for(const patch of [{phase:'authorized-awaiting-verified-build'},{installerSha256:null},{livePaidKizOrderThisVersion:true},
  {verifiedNativeProofs:owner154RequiredNativeProofs.filter(name=>name!=='upgrade-stable-153-smoke.json')}])
  assert.throws(()=>formatOwner154RequestReport(template,{...f.decision,...patch},f.status));
 const linux=f.status.validation.linuxFullSuite;
 assert.throws(()=>formatOwner154RequestReport(template,f.decision,{...f.status,validation:{...f.status.validation,linuxFullSuite:{...linux,passed:2085}}}));
});


test('157 owner release is bounded to its exact build and never claims live acceptance',async()=>{
    const {validateOwnerRequest157,validateOwnerRequested157}=await import('./publish-vncode-stable.mjs');
    const sha='2a44ebda0879ad844cefb86a3e4b97dfaa45c7de',run='39549870900';
    const decision={version:'1.5.7',buildSourceCommit:sha,runId:Number(run),ownerRequestedPublication:true,ownerInstruction:'tạo bộ cài và thay latest',unverifiedChecksDisclosedBeforePublication:true,standardFullAcceptanceClaimed:false,productionLicenseAcceptance:false,enterpriseCryptoProSigningAccepted:false,liveMarketplaceWritesAccepted:false,phase:'authorized-awaiting-verified-windows-build'};
    const status={version:'1.5.7',complete:false,databaseSchema:6,licenseIntegration:{productionReady:false,changedInThisVersion:false},validation:{productionLicenseVerified:false,liveCryptoProSigningThisVersion:false,liveMarketplaceWritesThisVersion:false,windowsBuild:{state:'running',sourceCommit:sha,runId:Number(run),freshFullJavaExecutionRequired:true}}};
    assert.doesNotThrow(()=>validateOwnerRequest157(decision,status,'1.5.7',sha,run));
    for(const patch of [{ownerInstruction:'publish anything'},{buildSourceCommit:'0'.repeat(40)},{runId:1},{ownerRequestedPublication:false},{productionLicenseAcceptance:true},{standardFullAcceptanceClaimed:true}])assert.throws(()=>validateOwnerRequest157({...decision,...patch},status,'1.5.7',sha,run));
    assert.throws(()=>validateOwnerRequested157(decision,status,'1.5.7',sha,run));
    const java={tests:2788,failures:0,errors:0,skipped:2,passed:2786};
    const verified={...decision,phase:'verified-for-publication',installerSha256:'a'.repeat(64),installerBytes:140000000,passiveExeHandoffVerified:true,windowsJava:java};
    const passed=structuredClone(status);Object.assign(passed.validation.windowsBuild,{state:'passed',upgradeFrom155Passed:true,allNativeChecksPassed:true,passiveExeHandoffVerified:true,fullJavaSuite:java});
    assert.doesNotThrow(()=>validateOwnerRequested157(verified,passed,'1.5.7',sha,run));
    for(const patch of [{upgradeFrom155Passed:false},{freshFullJavaExecutionRequired:false},{allNativeChecksPassed:false},{fullJavaSuite:{...java,errors:1}}]){const bad=structuredClone(passed);Object.assign(bad.validation.windowsBuild,patch);assert.throws(()=>validateOwnerRequested157(verified,bad,'1.5.7',sha,run));}
});

test('157 adds its latest upgrade checks before mutation and preserves the ordinary publish gate',()=>{
    const source=readFileSync(new URL('./publish-vncode-stable.mjs',import.meta.url),'utf8');
    const checks=source.indexOf("const upgrade155=json(await readFile(path.join(directory,'upgrade-stable-155-smoke.json')))");
    const mutation=source.indexOf("const refs=api(`git/matching-refs/tags/${tag}`)");
    assert.ok(checks>0&&checks<mutation);
    assert.match(source,/assert\.equal\(status\.complete,true,'Release functionality acceptance is incomplete'\)/);
    assert.match(source,/mode==='owner-requested-157'\)publicDownload|\['owner-requested-155','owner-requested-157'\]\.includes\(mode\)\)publicDownload/);
});
