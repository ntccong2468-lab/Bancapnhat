import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {owner155Binding,owner155RequiredNativeProofs,validateOwner155Evidence,validateOwnerRequest155,
    validateOwnerRequested155,validateOwner155UpgradeProof,validateOwner155Artifact,formatOwner155RequestReport,
    verifyPublicInstaller} from './publish-vncode-stable.mjs';

function fixture(pending=false){
    const version='1.5.5',sha=owner155Binding.sourceCommit??'a'.repeat(40),runId=String(owner155Binding.runId??123456);
    const java={tests:2300,failures:0,errors:0,skipped:2,passed:2298};
    const decision={version,sourceCommit:sha,runId:Number(runId),phase:pending?'authorized-awaiting-verified-build':'verified-for-publication',
        ownerRequestedPublication:true,ownerInstruction:'áp dụng cho bản cập nhật mới và đưa lên latest',sourceRuntimeIsFinal:true,
        unverifiedChecksDisclosedBeforePublication:true,standardFullAcceptanceClaimed:false,
        productionLicenseAcceptance:false,enterpriseCryptoProSigningAccepted:false,licensedDesktopGtinWriteAccepted:false,
        liveApiWbGtinWriteThisVersion:false,liveApiOzonGtinWriteThisVersion:false,liveCatalogCardCreatedThisVersion:false,liveCatalogCardRepairedThisVersion:false,
        liveKizPurchaseThisVersion:false,livePaidKizOrderThisVersion:false,liveApiWbKizPurchaseThisVersion:false,liveApiOzonKizPurchaseThisVersion:false,
        installerSha256:pending?null:'b'.repeat(64),installerBytes:pending?null:149000000,windowsJava:pending?null:java,
        verifiedNativeProofs:pending?null:[...owner155RequiredNativeProofs],passiveExeHandoffVerified:pending?null:true,windowsArtifactId:pending?null:123456789};
    const build={state:pending?'running':'passed',sourceCommit:sha,runId:Number(runId),freshFullJavaExecutionRequired:true,allNativeChecksMustRunAgain:true,windowsArtifactId:decision.windowsArtifactId,
        ...(pending?{}:{upgradeFrom154Passed:true,allNativeChecksPassed:true,installerPackaged:true,passiveExeHandoffVerified:true,fullJavaSuite:java})};
    const status={version,sourceCommit:sha,sourceRuntimeIsFinal:true,complete:false,databaseSchema:6,baseline:'v1.5.4',requestReport:'VN-code-1.5.5-request-report.md',
        licenseIntegration:{productionReady:false,changedInThisVersion:false},validation:{productionLicenseVerified:false,liveCryptoProSigningThisVersion:false,
        liveLicensedDesktopGtinWriteThisVersion:false,liveApiWbGtinWriteThisVersion:false,liveApiOzonGtinWriteThisVersion:false,
        liveCatalogCardCreatedThisVersion:false,liveCatalogCardRepairedThisVersion:false,liveKizPurchaseThisVersion:false,livePaidKizOrderThisVersion:false,
        liveApiWbKizPurchaseThisVersion:false,liveApiOzonKizPurchaseThisVersion:false,
        linuxFullSuite:{state:'passed',sourceCommit:sha,fullSuiteExecuted:true,cleanVerifyExecuted:true,...java},windowsBuild:build}};
    return {version,sha,runId,decision,status};
}
test('155 owner evidence separates authorized pending work from observed final build results',()=>{
    for(const pending of [true,false]){
        const f=fixture(pending);validateOwner155Evidence(f.decision,f.status,f.version,f.sha,f.runId);
        if(owner155Binding.sourceCommit&&owner155Binding.runId){validateOwnerRequest155(f.decision,f.status,f.version,f.sha,f.runId);if(!pending)validateOwnerRequested155(f.decision,f.status,f.version,f.sha,f.runId);}
        else assert.throws(()=>validateOwnerRequest155(f.decision,f.status,f.version,f.sha,f.runId));
        for(const patch of [{ownerRequestedPublication:false},{ownerInstruction:'different'},{sourceRuntimeIsFinal:false},{standardFullAcceptanceClaimed:true},
            {unverifiedChecksDisclosedBeforePublication:false},{productionLicenseAcceptance:true},{enterpriseCryptoProSigningAccepted:true},
            {licensedDesktopGtinWriteAccepted:true},{liveCatalogCardCreatedThisVersion:true},{liveCatalogCardRepairedThisVersion:true},
            {livePaidKizOrderThisVersion:true},{liveApiWbKizPurchaseThisVersion:true},{liveApiOzonKizPurchaseThisVersion:true}])
            assert.throws(()=>validateOwner155Evidence({...f.decision,...patch},f.status,f.version,f.sha,f.runId));
    }
});
test('155 final authorization cannot be reused for another internally consistent source or run',()=>{
    const f=fixture();
    for(const other of [{sha:'f'.repeat(40),runId:f.runId},{sha:f.sha,runId:String(Number(f.runId)+1)}]){
        const decision={...f.decision,sourceCommit:other.sha,runId:Number(other.runId)};
        const status={...f.status,sourceCommit:other.sha,validation:{...f.status.validation,
            linuxFullSuite:{...f.status.validation.linuxFullSuite,sourceCommit:other.sha},
            windowsBuild:{...f.status.validation.windowsBuild,sourceCommit:other.sha,runId:Number(other.runId)}}};
        validateOwner155Evidence(decision,status,f.version,other.sha,other.runId);
        assert.throws(()=>validateOwnerRequest155(decision,status,f.version,other.sha,other.runId));
    }
});
test('155 pending records cannot invent installer Java native artifact or passive handoff evidence',()=>{
    const f=fixture(true);
    for(const patch of [{installerSha256:'b'.repeat(64)},{installerBytes:149000000},{windowsJava:fixture().decision.windowsJava},
        {verifiedNativeProofs:[...owner155RequiredNativeProofs]},{passiveExeHandoffVerified:true},{windowsArtifactId:123456789}])
        assert.throws(()=>validateOwner155Evidence({...f.decision,...patch},f.status,f.version,f.sha,f.runId));
    for(const patch of [{fullJavaSuite:fixture().decision.windowsJava},{windowsArtifactId:123456789},{upgradeFrom154Passed:true},{allNativeChecksPassed:true},{installerPackaged:true},{passiveExeHandoffVerified:true}])
        assert.throws(()=>validateOwner155Evidence(f.decision,{...f.status,validation:{...f.status.validation,windowsBuild:{...f.status.validation.windowsBuild,...patch}}},f.version,f.sha,f.runId));
    assert.throws(()=>validateOwnerRequested155(f.decision,f.status,f.version,f.sha,f.runId));
});
test('155 final records require complete fresh Java exact artifacts and every historic plus 154 proof',()=>{
    const f=fixture();assert.equal(owner155RequiredNativeProofs.length,25);assert.equal(owner155RequiredNativeProofs.filter(name=>name.startsWith('upgrade-')).length,20);
    for(const patch of [{windowsArtifactId:null},{windowsArtifactId:0},{verifiedNativeProofs:owner155RequiredNativeProofs.filter(name=>name!=='upgrade-stable-154-smoke.json')},
        {windowsJava:{...f.decision.windowsJava,failures:1}},{windowsJava:{...f.decision.windowsJava,skipped:3}},{passiveExeHandoffVerified:false}])
        assert.throws(()=>validateOwner155Evidence({...f.decision,...patch},f.status,f.version,f.sha,f.runId));
    for(const patch of [{freshFullJavaExecutionRequired:false},{allNativeChecksMustRunAgain:false},{upgradeFrom154Passed:false},{allNativeChecksPassed:false},{windowsArtifactId:987654321}])
        assert.throws(()=>validateOwner155Evidence(f.decision,{...f.status,validation:{...f.status.validation,windowsBuild:{...f.status.validation.windowsBuild,...patch}}},f.version,f.sha,f.runId));
    for(const patch of [{state:'running'},{sourceCommit:'f'.repeat(40)},{fullSuiteExecuted:false},{cleanVerifyExecuted:false},{passed:2297}])
        assert.throws(()=>validateOwner155Evidence(f.decision,{...f.status,validation:{...f.status.validation,linuxFullSuite:{...f.status.validation.linuxFullSuite,...patch}}},f.version,f.sha,f.runId));
});
test('155 upgrade proof pins published154 bytes and the exact target passive EXE',()=>{
    const f=fixture(),proof={appName:'VN code',version:'1.5.5',fromVersion:'1.5.4',result:'passed',installerUpgradeUuid:'8CBBA0E2-6E73-4F56-9101-6BC0948D3C72',
        singleRegistration:true,dataPreserved:true,liveMarketplaceMutations:false,fingerprint:'a'.repeat(64),baselineTag:'v1.5.4',baselineInstallerBytes:146868224,
        baselineInstallerSha256:'e7079a4aca07c1ae2fec26e46c4e3a3d77096a14b1c0485a88cefe95a3f7a18b',
        passiveExeHandoffVerified:true,passiveExeInstallerSha256:f.decision.installerSha256,passiveExeEmbeddedMsiSha256:'c'.repeat(64)};
    validateOwner155UpgradeProof(proof,f.version,f.decision.installerSha256);
    for(const patch of [{fromVersion:'1.5.3'},{baselineTag:'v1.5.3'},{baselineInstallerBytes:146868225},{baselineInstallerSha256:'a'.repeat(64)},
        {passiveExeHandoffVerified:false},{passiveExeInstallerSha256:'a'.repeat(64)},{passiveExeEmbeddedMsiSha256:'bad'},{dataPreserved:false},{singleRegistration:false},{liveMarketplaceMutations:true}])
        assert.throws(()=>validateOwner155UpgradeProof({...proof,...patch},f.version,f.decision.installerSha256));
});
test('155 artifact identity is bound to the exact workflow run source digest and unexpired ID',()=>{
    const f=fixture(),artifact={id:f.decision.windowsArtifactId,name:'VN-code-1.5.5-Windows-x64',expired:false,digest:'sha256:'+'c'.repeat(64),workflow_run:{id:Number(f.runId),head_sha:f.sha}};
    validateOwner155Artifact(artifact,f.version,f.sha,f.runId,f.decision.windowsArtifactId);
    for(const patch of [{id:987654321},{name:'VN-code-1.5.4-Windows-x64'},{expired:true},{digest:'bad'},
        {workflow_run:{id:1,head_sha:f.sha}},{workflow_run:{id:Number(f.runId),head_sha:'f'.repeat(40)}}])
        assert.throws(()=>validateOwner155Artifact({...artifact,...patch},f.version,f.sha,f.runId,f.decision.windowsArtifactId));
});
test('155 report keeps seller scope and writes only observed build results with production limits',()=>{
    const f=fixture(),preamble='# Báo cáo VNCode 1.5.5\n\n## Phạm vi sửa\n\n- SCOPE: Znack, Россия, dữ liệu và nhãn Ozon.';
    const report=formatOwner155RequestReport(preamble+'\n\n## Kiểm tra\n\nPENDING_SENTINEL',f.decision,f.status);
    assert.ok(report.startsWith(preamble));assert.ok(!report.includes('PENDING_SENTINEL'));
    for(const text of ['25 bằng chứng native','20 ca nâng cấp','288 bố cục','1.5.4 đúng checksum','`/passive /norestart`',String(f.decision.windowsArtifactId),
        'Chưa hoàn tất','Mọi cờ nghiệm thu production tương ứng vẫn false','CryptoPro doanh nghiệp','không chứng minh thông báo đã xuất hiện trên desktop seller'])assert.ok(report.includes(text),text);
    const pending=fixture(true);assert.throws(()=>formatOwner155RequestReport(preamble+'\n\n## Kiểm tra\n\nPending',pending.decision,pending.status));
});
test('155 public byte verification checks anonymous downloaded bytes not only GitHub metadata',async()=>{
    const bytes=Buffer.from('MZ-fixture-public-installer'),digest=createHash('sha256').update(bytes).digest('hex');
    const asset={name:'VN-code-1.5.5-Windows-x64.exe',state:'uploaded',digest:'sha256:'+digest,size:bytes.length,
        browser_download_url:'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.5.5/VN-code-1.5.5-Windows-x64.exe'};
    const fetcher=async(url,options)=>{assert.equal(url,asset.browser_download_url);assert.equal(options.redirect,'follow');assert.equal(options.headers,undefined);return new Response(bytes,{status:200});};
    assert.deepEqual(await verifyPublicInstaller(asset,'1.5.5',digest,bytes.length,fetcher),{bytes:bytes.length,sha256:digest,anonymousDownloadVerified:true});
    await assert.rejects(()=>verifyPublicInstaller(asset,'1.5.5',digest,bytes.length,async()=>new Response('missing',{status:404})));
    await assert.rejects(()=>verifyPublicInstaller(asset,'1.5.5',digest,bytes.length,async()=>new Response(Buffer.alloc(bytes.length),{status:200})));
    await assert.rejects(()=>verifyPublicInstaller(asset,'1.5.5',digest,bytes.length,async()=>new Response('short',{status:200})));
});
test('155 workflow retains the normal production gate and verifies all evidence before release mutation',()=>{
    const source=readFileSync(new URL('./publish-vncode-stable.mjs',import.meta.url),'utf8');
    assert.match(source,/if\(mode==='publish'\) \{\s+assert\.equal\(status\.complete,true/);
    assert.ok(source.indexOf('validateOwner155UpgradeProof(upgrade154,version,digest)')<source.indexOf('const refs=api('));
    assert.ok(source.indexOf('validateOwner155Artifact(buildArtifact,version,sha,runId')<source.indexOf('const refs=api('));
    assert.match(source,/if\(mode==='owner-requested-155'\) \{\s+assert\.ok\(!logs\.includes\('REUSED_WINDOWS_JAVA:'\)&&!logs\.includes\('RECONCILED_WINDOWS_JAVA:'\)/);
    assert.ok(source.indexOf('publicDownload=await verifyPublicInstaller')>source.indexOf("make_latest:'true'"));
    assert.match(readFileSync(new URL('../.github/workflows/publish-vncode-stable.yml',import.meta.url),'utf8'),/owner-requested-155(?:,|\])/);
});
