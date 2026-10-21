import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';

const probe=readFileSync(new URL('./windows-upgrade-smoke.ps1',import.meta.url),'utf8');
const workflow=readFileSync(new URL('../.github/workflows/build-java.yml',import.meta.url),'utf8');
const installer=readFileSync(new URL('../src/main/java/com/vncode/app/integration/update/UpdateInstallerService.java',import.meta.url),'utf8');
const latestPreviewMode=/\$previewMode=[^\r\n]*-or \$FromStable155\r?\n/;

test('latest upgrade executes the final EXE with exactly the runtime passive arguments',()=>{
    const start=probe.indexOf('    if($FromStable152 -or $FromStable153 -or $FromStable154 -or $FromStable155){',probe.indexOf('Invalid pre-upgrade fingerprint.'));
    const end=probe.indexOf('    $registrations=Registrations',start);
    assert.ok(start>=0&&end>start,'The EXE protocol must run for the latest baseline');
    const upgrade=probe.slice(start,end);
    const command=upgrade.match(/Start-Process -FilePath \$passiveExe -ArgumentList @\(([^)]+)\) -Wait -PassThru/);
    const runtime=installer.match(/Start-Process -FilePath \$installer -ArgumentList @\(([^)]+)\) -Wait -PassThru/);
    assert.ok(command&&runtime,'Both runtime and native probe must wait for the EXE wrapper');
    const args=value=>value.split(',').map(value=>value.trim());
    assert.deepEqual(args(command[1]),["'/passive'","'/norestart'"]);
    assert.deepEqual(args(command[1]),args(runtime[1]));
    assert.match(upgrade,/\$upgradeProcess\.ExitCode -notin @\(0,3010\)/);
    assert.match(upgrade,/\}else\{Msi '\/i' \$newMsi;\$activeMsi=\$newMsi\}/,
        'Other baselines must keep their existing MSI upgrade');
    assert.equal((upgrade.match(/Get-FileHash -LiteralPath \$passiveExe -Algorithm SHA256/g)??[]).length,2,
        'The executed EXE must match its recorded hash before and after execution');
});

test('native EXE proof is bound to the exact MSI and public artifact bytes',()=>{
    const selection=probe.slice(probe.indexOf('$passiveExeUpgradeSucceeded=$false'),probe.indexOf('function Property('));
    assert.match(selection,/if\(\$FromStable152 -or \$FromStable153 -or \$FromStable154 -or \$FromStable155\)\{/);
    assert.match(selection,/Get-ChildItem -LiteralPath \(Join-Path \$PWD 'out'\) -Filter 'VN code\*\.exe' -File/);
    assert.match(selection,/\$installers\.Count -ne 1/);
    assert.match(selection,/\$installers\[0\]\.Length -lt 1MB/);
    assert.match(selection,/ReparsePoint/);
    assert.match(selection,/EmbeddedMsi\]::Extract\(\$passiveExe,\$embeddedMsi\)/);
    assert.match(selection,/\$passiveExeEmbeddedMsiSha256 -cne \(Get-FileHash -LiteralPath \$newMsi -Algorithm SHA256\)/);
    assert.ok(probe.indexOf("throw 'Incorrect MSI versions.'")<probe.indexOf('$upgradeProcess=Start-Process'));
    assert.ok(workflow.indexOf('windows-upgrade-smoke.ps1 -Version $env:APP_VERSION -FromStable152')
        <workflow.indexOf('- name: Verify and name test installer'),
        'The tested EXE is only renamed, never repackaged after the native probe');
    const stable153Step=workflow.indexOf('windows-upgrade-smoke.ps1 -Version $env:APP_VERSION -FromStable153');
    assert.ok(stable153Step>=0&&stable153Step<workflow.indexOf('- name: Verify and name test installer'),
        'The latest published baseline must execute the same final EXE before it is renamed');
    assert.match(probe,/\$proof\.passiveExeInstallerSha256=\$passiveExeInstallerSha256/);
    assert.match(probe,/\$proof\.passiveExeEmbeddedMsiSha256=\$passiveExeEmbeddedMsiSha256/);
});

test('passive handoff is reported only after real upgrade and all preserved-data checks',()=>{
    const success=probe.indexOf('$passiveExeUpgradeSucceeded=$true');
    const flag=probe.indexOf('$proof.passiveExeHandoffVerified=$true');
    assert.ok(success>probe.indexOf('$upgradeProcess.ExitCode -notin @(0,3010)'));
    assert.ok(flag>success);
    assert.ok(flag>probe.indexOf("throw 'Upgrade created a second VN code registration or retained the old version.'"));
    assert.ok(flag>probe.indexOf('Wait-VncodeWindow -Process $app -Version $Version'));
    assert.ok(flag>probe.indexOf("throw 'Existing VN code data or saved templates changed during upgrade.'"));
    assert.ok(flag>probe.indexOf("throw 'Uninstall did not preserve upgrade data.'"));
    assert.equal((probe.match(/\$proof\.passiveExeHandoffVerified=\$true/g)??[]).length,1);
    assert.match(probe,/if\(\$FromStable152 -or \$FromStable153 -or \$FromStable154 -or \$FromStable155\)\{\s+if\(-not \$passiveExeUpgradeSucceeded\)\{throw 'Passive EXE handoff was not exercised\.'/);
    assert.ok(probe.indexOf('$proof | ConvertTo-Json | Set-Content')>flag);
});

test('1.5.3 upgrade pins the published bytes and preserves the complete 1.5.2 proof chain',()=>{
    const published=JSON.parse(readFileSync(new URL('../docs/releases/VN-code-1.5.3-status.json',import.meta.url),'utf8')).publication;
    const selected=(name,flag)=>{
        const line=probe.split('\n').find(value=>value.startsWith('$'+name+'='));
        return new Map([...line.matchAll(/(?:if|elseif)\(\$(\w+)\)\{'([^']+)'\}/g)].map(match=>[match[1],match[2]])).get(flag);
    };
    assert.equal(selected('fromVersion','FromStable153'),'1.5.3');
    assert.equal(selected('baselineUrl','FromStable153'),published.releaseUrl.replace('/tag/','/download/')+'/VN-code-1.5.3-Windows-x64.exe');
    assert.equal(selected('baselineHash','FromStable153'),published.installerSha256);
    const size=probe.match(/\$baselineBytes=if\(\$FromStable153\)\{([0-9]+)\}/);
    assert.ok(size);assert.equal(Number(size[1]),published.installerBytes);
    const sizeGuard=probe.indexOf("throw 'Incorrect baseline VN code installer size.'");
    const hashGuard=probe.indexOf("throw 'Incorrect baseline VN code installer checksum.'");
    const extract=probe.indexOf('[VNcode.Smoke.EmbeddedMsi]::Extract($oldExe,$oldMsi)');
    assert.ok(sizeGuard>hashGuard&&sizeGuard<extract,'Both checksum and exact size must pass before the old installer is consumed');
    assert.match(probe,/if\(\$FromStable153 -and \[version\]\$Version -le \[version\]'1\.5\.3'\)/);
    assert.match(probe,/\$previous=Get-Content \$\(if\(\$FromStable152\)\{'out\\upgrade-stable-151-smoke\.json'\}elseif\(\$FromStable153\)\{'out\\upgrade-stable-152-smoke\.json'\}/);
    assert.match(probe,/Set-Content \$\(if\(\$FromStable152\)\{'out\\upgrade-stable-152-smoke\.json'\}elseif\(\$FromStable153\)\{'out\\upgrade-stable-153-smoke\.json'\}/);
    assert.match(probe,/if\(\$FromStable153\)\{\s+\$proof\.baselineTag='v1\.5\.3'\s+\$proof\.baselineInstallerSha256=\$baselineHash\s+\$proof\.baselineInstallerBytes=\$baselineBytes/);
    assert.equal((probe.match(/if\(\$FromStable152 -or \$FromStable153 -or \$FromStable154 -or \$FromStable155\)\{/g)??[]).length,3,
        'All four baselines must select, execute and attest the verified passive EXE protocol');
});

test('1.5.4 upgrade pins the public installer and extends the preserved-data passive handoff chain',()=>{
    const published=JSON.parse(readFileSync(new URL('../docs/releases/VN-code-1.5.4-status.json',import.meta.url),'utf8')).publication;
    const selected=(name,flag)=>{
        const line=probe.split('\n').find(value=>value.startsWith('$'+name+'='));
        return new Map([...line.matchAll(/(?:if|elseif)\(\$(\w+)\)\{'([^']+)'\}/g)].map(match=>[match[1],match[2]])).get(flag);
    };
    assert.equal(selected('fromVersion','FromStable154'),'1.5.4');
    assert.equal(selected('baselineUrl','FromStable154'),published.releaseUrl.replace('/tag/','/download/')+'/VN-code-1.5.4-Windows-x64.exe');
    assert.equal(selected('baselineHash','FromStable154'),published.installerSha256);
    assert.equal(published.installerSha256,'e7079a4aca07c1ae2fec26e46c4e3a3d77096a14b1c0485a88cefe95a3f7a18b');
    const size=probe.match(/\$baselineBytes=[^\n]*elseif\(\$FromStable154\)\{([0-9]+)\}elseif\(\$FromStable155\)\{147044352\}else\{\$null\}/);
    assert.ok(size);assert.equal(Number(size[1]),published.installerBytes);assert.equal(published.installerBytes,146868224);
    assert.match(probe,/\[switch\] \$FromStable154,\[switch\] \$FromStable155\)/);
    assert.match(probe,/if\(@\([^\n]*\$FromStable154,\$FromStable155\)\.Where\(\{\$_\}\)\.Count -gt 1\)/,
        'The new baseline cannot be combined with another baseline');
    assert.match(probe,latestPreviewMode,
        'The old fixture must be preserved before starting the published baseline');
    assert.match(probe,/if\(\$FromStable154 -and \[version\]\$Version -le \[version\]'1\.5\.4'\)/);
    assert.match(probe,/\$previous=Get-Content [^\n]*elseif\(\$FromStable154\)\{'out\\upgrade-stable-153-smoke\.json'\}/);
    assert.match(probe,/Set-Content [^\n]*elseif\(\$FromStable154\)\{'out\\upgrade-stable-154-smoke\.json'\}/);
    assert.match(probe,/if\(\$FromStable154\)\{\s+\$proof\.baselineTag='v1\.5\.4'\s+\$proof\.baselineInstallerSha256=\$baselineHash\s+\$proof\.baselineInstallerBytes=\$baselineBytes/);
    assert.equal((probe.match(/if\(\$FromStable152 -or \$FromStable153 -or \$FromStable154 -or \$FromStable155\)\{/g)??[]).length,3,
        'The new baseline selects, executes and attests the exact final EXE');
    const previousStep=workflow.indexOf('windows-upgrade-smoke.ps1 -Version $env:APP_VERSION -FromStable153');
    const latestStep=workflow.indexOf('windows-upgrade-smoke.ps1 -Version $env:APP_VERSION -FromStable154');
    assert.ok(latestStep>previousStep&&latestStep<workflow.indexOf('- name: Verify and name test installer'));
    assert.match(workflow,/if \(\[version\]\$env:APP_VERSION -gt \[version\]"1\.5\.4"\) \{\s+\.\/tools\/windows-upgrade-smoke\.ps1 -Version \$env:APP_VERSION -FromStable154/);
    assert.match(workflow,/out\/upgrade-\*\.json/,'The 1.5.4 proof is retained with all prior upgrade proofs');
});

test('latest baseline fixture preservation is required for both LF and Windows CRLF checkouts',()=>{
    for(const ending of ['\n','\r\n']){
        const checkout=probe.replace(/\r?\n/g,ending);
        assert.match(checkout,latestPreviewMode,'Either checkout format must retain the 1.5.5 fixture-preservation gate');
        const missingLatestMode=checkout.replace(/(\$previewMode=[^\r\n]*) -or \$FromStable155(?=\r?\n)/,'$1');
        assert.notEqual(missingLatestMode,checkout,'The negative fixture removes the actual latest-baseline branch');
        assert.doesNotMatch(missingLatestMode,latestPreviewMode,'Line-ending compatibility must not allow omitting fixture preservation');
    }
});


test('1.5.5 upgrade pins Latest bytes and validates passive handoff before publication',()=>{
    assert.match(probe,/elseif\(\$FromStable155\)\{'1\.5\.5'\}/);
    assert.match(probe,/elseif\(\$FromStable155\)\{'e966b8d36101f8d0c62b5340e7804ff574fb1453e89d713dbcd604b7446b4b13'\}/);
    assert.match(probe,/elseif\(\$FromStable155\)\{147044352\}/);
    assert.match(probe,/elseif\(\$FromStable155\)\{'out\\upgrade-stable-154-smoke\.json'\}/);
    assert.match(probe,/elseif\(\$FromStable155\)\{'out\\upgrade-stable-155-smoke\.json'\}/);
    const step=workflow.indexOf('windows-upgrade-smoke.ps1 -Version $env:APP_VERSION -FromStable155');
    assert.ok(step>workflow.indexOf('windows-upgrade-smoke.ps1 -Version $env:APP_VERSION -FromStable154'));
    assert.ok(step<workflow.indexOf('- name: Verify and name test installer'));
    assert.match(probe,/if\(\$FromStable155\)\{\s+\$proof\.baselineTag='v1\.5\.5'/);
});
