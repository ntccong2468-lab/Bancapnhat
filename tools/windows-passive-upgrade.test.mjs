import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';

const probe=readFileSync(new URL('./windows-upgrade-smoke.ps1',import.meta.url),'utf8');
const workflow=readFileSync(new URL('../.github/workflows/build-java.yml',import.meta.url),'utf8');
const installer=readFileSync(new URL('../src/main/java/com/vncode/app/integration/update/UpdateInstallerService.java',import.meta.url),'utf8');

test('latest upgrade executes the final EXE with exactly the runtime passive arguments',()=>{
    const start=probe.indexOf('    if($FromStable152){',probe.indexOf('Invalid pre-upgrade fingerprint.'));
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
    assert.match(selection,/if\(\$FromStable152\)\{/);
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
    assert.match(probe,/if\(\$FromStable152\)\{\s+if\(-not \$passiveExeUpgradeSucceeded\)\{throw 'Passive EXE handoff was not exercised\.'/);
    assert.ok(probe.indexOf('$proof | ConvertTo-Json | Set-Content')>flag);
});
