$ErrorActionPreference='Stop'
if($env:GITHUB_ACTIONS -cne 'true' -or $env:RUNNER_OS -cne 'Windows' -or $env:RUNNER_ENVIRONMENT -cne 'github-hosted'){throw 'WCode observation requires a disposable GitHub-hosted Windows desktop.'}
. (Join-Path $PSScriptRoot 'windows-smoke-common.ps1')
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
$root=Join-Path $env:RUNNER_TEMP ('wcode-observe-'+[guid]::NewGuid());New-Item -ItemType Directory $root | Out-Null
$out=Join-Path $PWD 'out/wcode-observation';New-Item -ItemType Directory -Force $out | Out-Null
$exe=Join-Path $root 'WCode-1.3.0.exe';$msi=Join-Path $root 'Wbcode-1.3.0.msi'
Invoke-WebRequest 'https://github.com/rupphi/relatest-wcode/releases/download/v1.3.0/WCode.exe' -OutFile $exe
$hash=(Get-FileHash $exe -Algorithm SHA256).Hash.ToLowerInvariant()
if($hash -cne '1abd064e9f5cc74888fb9c737097e3a1030aa3dead3c42d2a52ea3bdd844f092'){throw 'Official Wbcode checksum mismatch.'}
[VNcode.Smoke.EmbeddedMsi]::Extract($exe,$msi)
$installed=$false;$app=$null
try {
 $p=Start-Process msiexec.exe -ArgumentList @('/i',"`"$msi`"",'/qn','/norestart') -Wait -PassThru;if($p.ExitCode -notin @(0,3010)){throw "Install failed: $($p.ExitCode)"};$installed=$true
 $candidates=@();foreach($dir in @('WCodeApp','WbcodeApp','Wbcode','WCode')){foreach($base in @($env:ProgramFiles,$env:LOCALAPPDATA)){if(Test-Path (Join-Path $base $dir)){$candidates+=Get-ChildItem (Join-Path $base $dir) -Filter '*.exe' -File}}}
 $launcher=@($candidates | Where-Object {$_.BaseName -in @('WCode','Wbcode')}) | Select-Object -First 1
 if(-not $launcher){throw 'Official launcher not found in expected application directories.'}
 $env:JAVA_TOOL_OPTIONS='-Dprism.order=sw'
 $app=Start-Process $launcher.FullName -PassThru
 $titles=@();for($n=0;$n -lt 60;$n++){Start-Sleep -Milliseconds 500;$app.Refresh();if($app.HasExited){throw 'Official app exited before showing UI.'};$ids=@($app.Id)+@(Get-CimInstance Win32_Process -Filter "ParentProcessId = $($app.Id)" | ForEach-Object {[int]$_.ProcessId});$titles=@($ids|ForEach-Object {[VNcode.Smoke.VisibleWindows]::Titles($_)});if($titles.Count -gt 0){break}}
 if($titles.Count -eq 0){throw 'Official app showed no visible window.'}
 Start-Sleep -Seconds 5
 $bounds=[Windows.Forms.SystemInformation]::VirtualScreen;$bitmap=New-Object Drawing.Bitmap $bounds.Width,$bounds.Height;$graphics=[Drawing.Graphics]::FromImage($bitmap)
 try{$graphics.CopyFromScreen($bounds.Left,$bounds.Top,0,0,$bitmap.Size);$bitmap.Save((Join-Path $out 'official-wbcode-1.3.0-first-run.png'),[Drawing.Imaging.ImageFormat]::Png)}finally{$graphics.Dispose();$bitmap.Dispose()}
 $windows=@($ids|ForEach-Object {[VNcode.Smoke.VisibleWindows]::Titles($_)})
 @{source='official release installer';release='v1.3.0';installerSha256=$hash;nativeLaunch='passed';visibleTitles=$windows;freshRunner=$true;shopCredentialsEntered=$false;liveMarketplaceMutations=$false;licenseActivated=$false;scope='First-run UI only; authenticated workflows not tested';screenshot='official-wbcode-1.3.0-first-run.png'}|ConvertTo-Json|Set-Content (Join-Path $out 'observation.json') -Encoding utf8
}finally{if($app -and -not $app.HasExited){Stop-Process -Id $app.Id -Force;$app.WaitForExit()};if($installed){$p=Start-Process msiexec.exe -ArgumentList @('/x',"`"$msi`"",'/qn','/norestart') -Wait -PassThru;if($p.ExitCode -notin @(0,3010)){throw 'Official app uninstall failed.'}}}
