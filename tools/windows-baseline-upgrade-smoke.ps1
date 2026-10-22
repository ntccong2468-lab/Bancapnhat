param([Parameter(Mandatory=$true)][string] $Version,[Parameter(Mandatory=$true)][string] $BaselineManifest)
$ErrorActionPreference='Stop'
$FromBaseline=$true
$baseline=$null
if($FromBaseline){
    $baseline=Get-Content -LiteralPath $BaselineManifest -Raw | ConvertFrom-Json
    if($baseline.version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$' -or [version]$baseline.version -ge [version]$Version){throw 'Baseline must be older than candidate.'}
    $expectedUrl="https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v$($baseline.version)/VN-code-$($baseline.version)-Windows-x64.exe"
    if($baseline.url -cne $expectedUrl -or $baseline.sha256 -cnotmatch '^[a-f0-9]{64}$' -or $baseline.bytes -lt 1MB -or $baseline.bytes -gt 1GB){throw 'Invalid exact baseline metadata.'}
}
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
    & java --enable-native-access=ALL-UNNAMED -cp $probeClasspath WindowsDataProbe fresh $data
    if ($LASTEXITCODE -ne 0) { throw 'Previous CI data is not the expected empty fixture.' }
    # Coexistence just launched the new version and left its newer, empty schema behind.
    # Preserve that verified fixture before starting an older binary, which correctly refuses
    # newer schemas. The old launcher must create its own baseline database for this upgrade.
    Move-Item -LiteralPath $data -Destination (Join-Path $root 'preserved-new-version-fresh-fixture')
    if (Test-Path $data) { throw 'The older baseline requires a fresh data directory.' }
$fromVersion=$baseline.version
$oldExe=Join-Path $root "VN-code-$fromVersion.exe";$oldMsi=Join-Path $root "VN-code-$fromVersion.msi"
$baselineUrl=$baseline.url;$baselineHash=$baseline.sha256;$baselineBytes=$baseline.bytes
Invoke-WebRequest -Uri $baselineUrl -OutFile $oldExe -MaximumRetryCount 3 -RetryIntervalSec 2
if ((Get-FileHash $oldExe -Algorithm SHA256).Hash.ToLowerInvariant() -cne $baselineHash) { throw 'Incorrect baseline VN code installer checksum.' }
if($null -ne $baselineBytes -and (Get-Item -LiteralPath $oldExe).Length -ne $baselineBytes){throw 'Incorrect baseline VN code installer size.'}
[VNcode.Smoke.EmbeddedMsi]::Extract($oldExe,$oldMsi)
$newMsi=(Resolve-Path "target\jpackage-temp\msi\VN code-$Version.msi").Path
$passiveExeUpgradeSucceeded=$false
if($FromBaseline){
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
    if($FromBaseline){
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
    if($FromBaseline){
        if(-not $passiveExeUpgradeSucceeded){throw 'Passive EXE handoff was not exercised.'}
        $proof.passiveExeHandoffVerified=$true
        $proof.passiveExeInstallerSha256=$passiveExeInstallerSha256
        $proof.passiveExeEmbeddedMsiSha256=$passiveExeEmbeddedMsiSha256
    }
    if($FromBaseline){$proof.baselineTag=$baseline.tag;$proof.baselineInstallerSha256=$baselineHash;$proof.baselineInstallerBytes=$baselineBytes}
    $proof | ConvertTo-Json | Set-Content 'out\upgrade-baseline-smoke.json' -Encoding utf8
}finally{if($app -and -not $app.HasExited){Stop-Process -Id $app.Id -Force};if($installed){Msi '/x' $activeMsi}}
