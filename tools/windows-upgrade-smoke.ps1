param([Parameter(Mandatory=$true)][string] $Version,[switch] $FromPreview,[switch] $FromLatestPreview,[switch] $FromCurrentPreview,[switch] $FromGs1Preview,[switch] $FromLicenseTest,[switch] $FromStable146,[switch] $FromStable1410,[switch] $FromStable1411,[switch] $FromStable1412,[switch] $FromStable1413,[switch] $FromStable1415,[switch] $FromStable1416,[switch] $FromStable1418,[switch] $FromStable1419,[switch] $FromStable150,[switch] $FromStable151,[switch] $FromStable152,[switch] $FromStable153,[switch] $FromStable154)
$ErrorActionPreference='Stop'
if(@($FromPreview,$FromLatestPreview,$FromCurrentPreview,$FromGs1Preview,$FromLicenseTest,$FromStable146,$FromStable1410,$FromStable1411,$FromStable1412,$FromStable1413,$FromStable1415,$FromStable1416,$FromStable1418,$FromStable1419,$FromStable150,$FromStable151,$FromStable152,$FromStable153,$FromStable154).Where({$_}).Count -gt 1){throw 'Choose exactly one preview baseline.'}
$previewMode=$FromPreview -or $FromLatestPreview -or $FromCurrentPreview -or $FromGs1Preview -or $FromLicenseTest -or $FromStable146 -or $FromStable1410 -or $FromStable1411 -or $FromStable1412 -or $FromStable1413 -or $FromStable1415 -or $FromStable1416 -or $FromStable1418 -or $FromStable1419 -or $FromStable150 -or $FromStable151 -or $FromStable152 -or $FromStable153 -or $FromStable154
if ($env:GITHUB_ACTIONS -cne 'true' -or $env:RUNNER_OS -cne 'Windows' -or $env:RUNNER_ENVIRONMENT -cne 'github-hosted') {
    throw 'Upgrade installation probe requires a disposable GitHub-hosted Windows runner.'
}
. (Join-Path $PSScriptRoot 'windows-smoke-common.ps1')
$prior=Get-Content out\side-by-side-smoke.json -Raw | ConvertFrom-Json
if ($prior.result -cne 'passed' -or $prior.version -cne $Version -or -not $prior.freshVncodeData) { throw 'Require the immediately preceding clean coexistence probe.' }
$root=Join-Path $env:RUNNER_TEMP ('vncode-upgrade-'+[guid]::NewGuid())
New-Item -ItemType Directory -Path $root | Out-Null
$data=Join-Path $env:LOCALAPPDATA 'VNcodeData'
$program=Join-Path $env:LOCALAPPDATA 'VNcodeApp'
if (Test-Path $program) { throw 'VN code must have been uninstalled by the preceding probe.' }
$classpath="$PWD\target\VNcode-$Version.jar;$PWD\target\lib\*"
& javac -cp $classpath -d $root tools\WindowsDataProbe.java tools\WindowsUpgradeDataProbe.java
if ($LASTEXITCODE -ne 0) { throw 'Cannot compile upgrade data probes.' }
$probeClasspath="$root;$classpath"
if($previewMode){
    $previous=Get-Content $(if($FromStable152){'out\upgrade-stable-151-smoke.json'}elseif($FromStable153){'out\upgrade-stable-152-smoke.json'}elseif($FromStable154){'out\upgrade-stable-153-smoke.json'}elseif($FromStable151){'out\upgrade-stable-150-smoke.json'}elseif($FromStable150){'out\upgrade-stable-1419-smoke.json'}elseif($FromStable1419){'out\upgrade-stable-1418-smoke.json'}elseif($FromStable1418){'out\upgrade-stable-1416-smoke.json'}elseif($FromStable1416){'out\upgrade-stable-1415-smoke.json'}elseif($FromStable1415){'out\upgrade-stable-1413-smoke.json'}elseif($FromStable1413){'out\upgrade-stable-1412-smoke.json'}elseif($FromStable1412){'out\upgrade-stable-1411-smoke.json'}elseif($FromStable1411){'out\upgrade-stable-1410-smoke.json'}elseif($FromStable1410){'out\upgrade-stable-146-smoke.json'}elseif($FromStable146){'out\upgrade-license-test-smoke.json'}elseif($FromLicenseTest){'out\upgrade-gs1-preview-smoke.json'}elseif($FromGs1Preview){'out\upgrade-current-preview-smoke.json'}elseif($FromCurrentPreview){'out\upgrade-latest-preview-smoke.json'}elseif($FromLatestPreview){'out\upgrade-preview-smoke.json'}else{'out\upgrade-smoke.json'}) -Raw | ConvertFrom-Json
    if($previous.result -cne 'passed' -or $previous.version -cne $Version -or -not $previous.dataPreserved){throw 'Require successful baseline upgrade before preview upgrade.'}
    $fingerprint=& java --enable-native-access=ALL-UNNAMED -cp $probeClasspath WindowsUpgradeDataProbe fingerprint $data
    if($LASTEXITCODE -ne 0 -or $fingerprint -cne $previous.fingerprint){throw 'Unexpected fixture data before preview upgrade.'}
    Move-Item -LiteralPath $data -Destination (Join-Path $root 'preserved-baseline-fixture')
    if(Test-Path $data){throw 'Preview upgrade requires a fresh data directory after preserving the baseline fixture.'}
}else{
    & java --enable-native-access=ALL-UNNAMED -cp $probeClasspath WindowsDataProbe fresh $data
    if ($LASTEXITCODE -ne 0) { throw 'Previous CI data is not the expected empty fixture.' }
    # Coexistence just launched the new version and left its newer, empty schema behind.
    # Preserve that verified fixture before starting an older binary, which correctly refuses
    # newer schemas. The old launcher must create its own baseline database for this upgrade.
    Move-Item -LiteralPath $data -Destination (Join-Path $root 'preserved-new-version-fresh-fixture')
    if (Test-Path $data) { throw 'The older baseline requires a fresh data directory.' }
}
$fromVersion=if($FromStable152){'1.5.2'}elseif($FromStable153){'1.5.3'}elseif($FromStable154){'1.5.4'}elseif($FromStable151){'1.5.1'}elseif($FromStable150){'1.5.0'}elseif($FromStable1419){'1.4.19'}elseif($FromStable1418){'1.4.18'}elseif($FromStable1416){'1.4.16'}elseif($FromStable1415){'1.4.15'}elseif($FromStable1413){'1.4.13'}elseif($FromStable1412){'1.4.12'}elseif($FromStable1411){'1.4.11'}elseif($FromStable1410){'1.4.10'}elseif($FromStable146){'1.4.6'}elseif($FromLicenseTest){'1.4.0'}elseif($FromGs1Preview){'1.3.0'}elseif($FromCurrentPreview){'1.2.2'}elseif($FromLatestPreview){'1.2.1'}elseif($FromPreview){'1.2.0'}else{'1.1.34'}
$oldExe=Join-Path $root "VN-code-$fromVersion.exe";$oldMsi=Join-Path $root "VN-code-$fromVersion.msi"
$baselineUrl=if($FromStable152){'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.5.2/VN-code-1.5.2-Windows-x64.exe'}elseif($FromStable153){'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.5.3/VN-code-1.5.3-Windows-x64.exe'}elseif($FromStable154){'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.5.4/VN-code-1.5.4-Windows-x64.exe'}elseif($FromStable151){'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.5.1/VN-code-1.5.1-Windows-x64.exe'}elseif($FromStable150){'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.5.0/VN-code-1.5.0-Windows-x64.exe'}elseif($FromStable1419){'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.19/VN-code-1.4.19-Windows-x64.exe'}elseif($FromStable1418){'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.18/VN-code-1.4.18-Windows-x64.exe'}elseif($FromStable1416){'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.16/VN-code-1.4.16-Windows-x64.exe'}elseif($FromStable1415){'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.15/VN-code-1.4.15-Windows-x64.exe'}elseif($FromStable1413){'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.13/VN-code-1.4.13-Windows-x64.exe'}elseif($FromStable1412){'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.12/VN-code-1.4.12-Windows-x64.exe'}elseif($FromStable1411){'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.11/VN-code-1.4.11-Windows-x64.exe'}elseif($FromStable1410){'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.10/VN-code-1.4.10-Windows-x64.exe'}elseif($FromStable146){'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.6/VN-code-1.4.6-Windows-x64.exe'}elseif($FromGs1Preview){'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.3.0-preview.1/VN-code-1.3.0-preview.1-Windows-x64.exe'}elseif($FromCurrentPreview){'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.2.2-preview.1/VN-code-1.2.2-preview.1-Windows-x64.exe'}elseif($FromLatestPreview){'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.2.1-preview.1/VN-code-1.2.1-preview.1-Windows-x64.exe'}elseif($FromPreview){'https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.2.0-preview.1/VN-code-1.2.0-preview.1-Windows-x64.exe'}else{'https://github.com/ntccong2468-lab/Vncode/releases/download/v1.1.34/VN-code-1.1.34-Windows-x64.exe'}
$baselineHash=if($FromStable152){'4f1b28dbb8eaffa4f86a5512f8155b00f7c7688d744e778e87c9f03120feb31c'}elseif($FromStable153){'ec608d3677581c12901ee702e516cb2eea2757a5a9ee8fd84a2ce2438e8a3f31'}elseif($FromStable154){'e7079a4aca07c1ae2fec26e46c4e3a3d77096a14b1c0485a88cefe95a3f7a18b'}elseif($FromStable1416){'9c18a6d388945358213dad0c6eaa158cdd53b4f78cacbe773fe87b6d0883dfb2'}elseif($FromStable1418){'571f7c26e85722ab742b92000505d606198ba1b66d44f08d2d0a01a4cd231ccd'}elseif($FromStable1419){'e2d43bdca42031b6e583dea8efa298addc142e46fadfb2a21ca00b6e5cc31084'}elseif($FromStable150){'b5b5ddd562213aa655c3183bc8e7e998ba58d42abd1563442a397865a5faea3f'}elseif($FromStable151){'57dae2a5932697789c1cec8acc53de768e458516b0158f0ae83ed96b065d3788'}elseif($FromStable1415){'fc3ecc9be0eefa37bb241d9a86f6b6715bb5ad75f51ed178ce10230e51205c16'}elseif($FromStable1413){'e547fcb7cbfd7f4b2d55816a88eaf45c4c8503e1e41cae5d4eaedd94a0d0ac82'}elseif($FromStable1412){'66b34a542bca66b812a5c7f8685596196f0de0d9e6d0fc3d2b8d67178d15b8a1'}elseif($FromStable1411){'983d481a0b320a57bbb7606d790161120aae1d454bee82f77086480c7d9479bd'}elseif($FromStable1410){'e6017013c999455c85adce4c371e1bf1b3da65ab80c222136327bdc096cea587'}elseif($FromStable146){'5e988021965acdb8cb1ff8857c15a2989ea156e86f0082f28b9869012da0dea2'}elseif($FromLicenseTest){'c54f547819112f2fa10a80d73805e086b4f17af39b1cca5b28715706c50a4110'}elseif($FromGs1Preview){'7b27b1ceb5d3de6c840e5cbfda8ead52898918b253b603ceaace4ef0d1e88c42'}elseif($FromCurrentPreview){'4ef4b3881337f1414d684d0be0ced9b8543be6bf114031ec31e5c83227d93541'}elseif($FromLatestPreview){'2159133f48de2fcaaa2d39e5983157d9c713929c10479d0007f30463394d483f'}elseif($FromPreview){'2290088fb9e1065430f4d9c5e9b47185c5581a93996b3ad692e8bbc846756ad1'}else{'d3970ee5ca88f72a810bbedb807c84cdb7201b96c0d2ef132d0a7a01cea6ac79'}
$baselineBytes=if($FromStable153){146864128}elseif($FromStable154){146868224}else{$null}
if($FromStable153 -and [version]$Version -le [version]'1.5.3'){throw 'The 1.5.3 baseline requires a newer target version.'}
if($FromStable154 -and [version]$Version -le [version]'1.5.4'){throw 'The 1.5.4 baseline requires a newer target version.'}
if($FromLicenseTest){
    if(-not $env:GH_TOKEN){throw 'Verified baseline download requires read-only Actions authentication.'}
    # Draft releases are invisible to read-only tokens. Retrieve the same immutable
    # installer from its successful build instead; do not grant repository write access.
    $baselineRun = (& gh api repos/ntccong2468-lab/Bancapnhat/actions/runs/38492821195) | ConvertFrom-Json
    if($LASTEXITCODE -ne 0 -or $baselineRun.conclusion -cne 'success' -or $baselineRun.head_sha -cne '379197531ac1869e0a645e04f5c8ab9e8418be2c' -or $baselineRun.workflow_id -ne 379116841){throw 'Unverified 1.4.0 baseline source or build.'}
    & gh run download 38492821195 --repo ntccong2468-lab/Bancapnhat --name VN-code-1.4.0-Windows-x64 --dir $root
    if($LASTEXITCODE -ne 0){throw 'Cannot retrieve the immutable 1.4.0 license-test baseline.'}
    Move-Item -LiteralPath (Join-Path $root 'VN-code-1.4.0-Windows-x64.exe') -Destination $oldExe
}else{Invoke-WebRequest -Uri $baselineUrl -OutFile $oldExe -MaximumRetryCount 3 -RetryIntervalSec 2}
if ((Get-FileHash $oldExe -Algorithm SHA256).Hash.ToLowerInvariant() -cne $baselineHash) { throw 'Incorrect baseline VN code installer checksum.' }
if($null -ne $baselineBytes -and (Get-Item -LiteralPath $oldExe).Length -ne $baselineBytes){throw 'Incorrect baseline VN code installer size.'}
[VNcode.Smoke.EmbeddedMsi]::Extract($oldExe,$oldMsi)
$newMsi=(Resolve-Path "target\jpackage-temp\msi\VN code-$Version.msi").Path
$passiveExeUpgradeSucceeded=$false
if($FromStable152 -or $FromStable153 -or $FromStable154){
    # build.bat places the final jpackage EXE in out; the workflow renames it
    # only after this probe. Select exactly that delivered file, not a WiX stub.
    $installers=@(Get-ChildItem -LiteralPath (Join-Path $PWD 'out') -Filter 'VN code*.exe' -File)
    if($installers.Count -ne 1 -or $installers[0].Length -lt 1MB -or $installers[0].Length -gt 1GB -or
       ($installers[0].Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Require exactly one regular final jpackage EXE.'}
    $passiveExe=$installers[0].FullName
    $passiveExeInstallerSha256=(Get-FileHash -LiteralPath $passiveExe -Algorithm SHA256).Hash.ToLowerInvariant()
    $embeddedMsi=Join-Path $root 'candidate-embedded.msi'
    [VNcode.Smoke.EmbeddedMsi]::Extract($passiveExe,$embeddedMsi)
    $passiveExeEmbeddedMsiSha256=(Get-FileHash -LiteralPath $embeddedMsi -Algorithm SHA256).Hash.ToLowerInvariant()
    if($passiveExeEmbeddedMsiSha256 -cne (Get-FileHash -LiteralPath $newMsi -Algorithm SHA256).Hash.ToLowerInvariant()){
        throw 'The final EXE does not contain the exact MSI used by the native upgrade probes.'
    }
}
function Property([string]$Package,[string]$Name){
    $installer=New-Object -ComObject WindowsInstaller.Installer;$database=$installer.OpenDatabase($Package,0)
    $view=$database.OpenView("SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = '$Name'")
    try{[void]$view.Execute();$row=$view.Fetch();if(-not $row){throw 'Missing MSI property'};return $row.StringData(1)}finally{[void]$view.Close()}
}
function VerifyShortcutDefinitions([string]$Package){
    $installer=New-Object -ComObject WindowsInstaller.Installer;$database=$installer.OpenDatabase($Package,0)
    $view=$database.OpenView('SELECT `Directory_`,`Name` FROM `Shortcut`');$entries=@()
    try{[void]$view.Execute();while($row=$view.Fetch()){$entries+=@{directory=$row.StringData(1);name=($row.StringData(2) -split '\|')[-1]}}}finally{[void]$view.Close()}
    if($entries.Count -ne 2 -or @($entries | Group-Object directory | Where-Object Count -ne 1).Count -ne 0 -or @($entries | Where-Object name -CNE 'VN code').Count -ne 0){throw 'The installer must define one VN code shortcut per Desktop/Start destination.'}
    return $true
}
$singleShortcutPerLocation=VerifyShortcutDefinitions $newMsi
$upgrade='8CBBA0E2-6E73-4F56-9101-6BC0948D3C72'
foreach($package in @($oldMsi,$newMsi)){
    if((Property $package 'UpgradeCode').Trim('{}').ToUpperInvariant() -cne $upgrade -or (Property $package 'ProductName') -cne 'VN code'){throw 'Installer identity changed.'}
}
if((Property $oldMsi 'ProductVersion') -cne $fromVersion -or (Property $newMsi 'ProductVersion') -cne $Version){throw 'Incorrect MSI versions.'}
function Msi([string]$Action,[string]$Package){$p=Start-Process msiexec.exe -ArgumentList @($Action,"`"$Package`"",'/qn','/norestart') -Wait -PassThru;if($p.ExitCode -notin @(0,3010)){throw "MSI failed: $($p.ExitCode)"}}
function Registrations(){return @(Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*','HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue | Where-Object DisplayName -CEQ 'VN code')}
$app=$null;$installed=$false;$activeMsi=$oldMsi
try{
    Msi '/i' $oldMsi;$installed=$true
    $launcher=Join-Path $program 'VN code.exe'
    Write-Host "Launching preserved-data baseline VN code $fromVersion with its own fresh schema."
    $app=Start-Process $launcher -PassThru -RedirectStandardOutput "out\upgrade-$fromVersion-baseline-stdout.log" -RedirectStandardError "out\upgrade-$fromVersion-baseline-stderr.log"
    [void](Wait-VncodeWindow -Process $app -Version $fromVersion)
    Stop-Process -Id $app.Id -Force;$app.WaitForExit();$app=$null
    $baselineSchema=if([version]$fromVersion -ge [version]'1.4.19'){6}elseif([version]$fromVersion -ge [version]'1.4.0'){5}else{4}
    & java --enable-native-access=ALL-UNNAMED -cp $probeClasspath WindowsDataProbe fresh $data $baselineSchema
    if($LASTEXITCODE -ne 0){throw 'The baseline launcher did not create the expected empty upgrade fixture.'}
    $baselineClasspath="$root;$program\app\*;$program\app\lib\*"
    & java --enable-native-access=ALL-UNNAMED -cp $baselineClasspath WindowsUpgradeDataProbe seed $data
    if($LASTEXITCODE -ne 0){throw 'Cannot seed upgrade fixture.'}
    $before=& java --enable-native-access=ALL-UNNAMED -cp $probeClasspath WindowsUpgradeDataProbe fingerprint $data
    if($LASTEXITCODE -ne 0 -or $before -notmatch '^[0-9a-f]{64}$'){throw 'Invalid pre-upgrade fingerprint.'}
    if($FromStable152 -or $FromStable153 -or $FromStable154){
        if((Get-FileHash -LiteralPath $passiveExe -Algorithm SHA256).Hash.ToLowerInvariant() -cne $passiveExeInstallerSha256){throw 'The final EXE changed before passive upgrade.'}
        # Exercise the exact public EXE protocol used by UpdateInstallerService.
        # OpenJDK's wrapper forwards these arguments to its own embedded MSI.
        $upgradeProcess=Start-Process -FilePath $passiveExe -ArgumentList @('/passive','/norestart') -Wait -PassThru
        if($upgradeProcess.ExitCode -notin @(0,3010)){throw "Passive EXE upgrade failed: $($upgradeProcess.ExitCode)"}
        $activeMsi=$newMsi
        if((Get-FileHash -LiteralPath $passiveExe -Algorithm SHA256).Hash.ToLowerInvariant() -cne $passiveExeInstallerSha256){throw 'The final EXE changed during passive upgrade.'}
        $passiveExeUpgradeSucceeded=$true
    }else{Msi '/i' $newMsi;$activeMsi=$newMsi}
    $registrations=Registrations
    if($registrations.Count -ne 1 -or $registrations[0].DisplayVersion -cne $Version){throw 'Upgrade created a second VN code registration or retained the old version.'}
    Write-Host "Launching upgraded VN code $Version over the seeded $fromVersion fixture."
    $app=Start-Process $launcher -PassThru -RedirectStandardOutput "out\upgrade-$fromVersion-current-stdout.log" -RedirectStandardError "out\upgrade-$fromVersion-current-stderr.log"
    $window=Wait-VncodeWindow -Process $app -Version $Version
    Stop-Process -Id $app.Id -Force;$app.WaitForExit();$app=$null
    $after=& java --enable-native-access=ALL-UNNAMED -cp $probeClasspath WindowsUpgradeDataProbe fingerprint $data
    if($LASTEXITCODE -ne 0 -or $after -cne $before){throw 'Existing VN code data or saved templates changed during upgrade.'}
    & java --enable-native-access=ALL-UNNAMED -cp $probeClasspath WindowsUpgradeDataProbe verify-kiz-priority $data
    if($LASTEXITCODE -ne 0){throw 'Legacy KIZ stock did not retain its required default priority.'}
    Msi '/x' $newMsi;$installed=$false
    if(@(Registrations).Count -ne 0 -or -not(Test-Path (Join-Path $data 'database.db'))){throw 'Uninstall did not preserve upgrade data.'}
    $proof=@{appName='VN code';fromVersion=$fromVersion;version=$Version;result='passed';installerUpgradeUuid=$upgrade;singleRegistration=$true;singleShortcutPerLocation=$singleShortcutPerLocation;dataPreserved=$true;windowTitle=$window;liveMarketplaceMutations=$false;fingerprint=$after}
    if($FromStable152 -or $FromStable153 -or $FromStable154){
        if(-not $passiveExeUpgradeSucceeded){throw 'Passive EXE handoff was not exercised.'}
        $proof.passiveExeHandoffVerified=$true
        $proof.passiveExeInstallerSha256=$passiveExeInstallerSha256
        $proof.passiveExeEmbeddedMsiSha256=$passiveExeEmbeddedMsiSha256
    }
    if($FromStable153){
        $proof.baselineTag='v1.5.3'
        $proof.baselineInstallerSha256=$baselineHash
        $proof.baselineInstallerBytes=$baselineBytes
    }
    if($FromStable154){
        $proof.baselineTag='v1.5.4'
        $proof.baselineInstallerSha256=$baselineHash
        $proof.baselineInstallerBytes=$baselineBytes
    }
    $proof | ConvertTo-Json | Set-Content $(if($FromStable152){'out\upgrade-stable-152-smoke.json'}elseif($FromStable153){'out\upgrade-stable-153-smoke.json'}elseif($FromStable154){'out\upgrade-stable-154-smoke.json'}elseif($FromStable151){'out\upgrade-stable-151-smoke.json'}elseif($FromStable150){'out\upgrade-stable-150-smoke.json'}elseif($FromStable1419){'out\upgrade-stable-1419-smoke.json'}elseif($FromStable1418){'out\upgrade-stable-1418-smoke.json'}elseif($FromStable1416){'out\upgrade-stable-1416-smoke.json'}elseif($FromStable1415){'out\upgrade-stable-1415-smoke.json'}elseif($FromStable1413){'out\upgrade-stable-1413-smoke.json'}elseif($FromStable1412){'out\upgrade-stable-1412-smoke.json'}elseif($FromStable1411){'out\upgrade-stable-1411-smoke.json'}elseif($FromStable1410){'out\upgrade-stable-1410-smoke.json'}elseif($FromStable146){'out\upgrade-stable-146-smoke.json'}elseif($FromLicenseTest){'out\upgrade-license-test-smoke.json'}elseif($FromGs1Preview){'out\upgrade-gs1-preview-smoke.json'}elseif($FromCurrentPreview){'out\upgrade-current-preview-smoke.json'}elseif($FromLatestPreview){'out\upgrade-latest-preview-smoke.json'}elseif($FromPreview){'out\upgrade-preview-smoke.json'}else{'out\upgrade-smoke.json'}) -Encoding utf8
}finally{if($app -and -not $app.HasExited){Stop-Process -Id $app.Id -Force};if($installed){Msi '/x' $activeMsi}}
