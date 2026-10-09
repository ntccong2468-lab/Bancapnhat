$ErrorActionPreference='Stop'
if($env:GITHUB_ACTIONS -cne 'true' -or $env:RUNNER_OS -cne 'Windows' -or $env:RUNNER_ENVIRONMENT -cne 'github-hosted'){throw 'WCode observation requires a disposable GitHub-hosted Windows desktop.'}
. (Join-Path $PSScriptRoot 'windows-smoke-common.ps1')
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class WCodeObservationWindow {
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hwnd,int command);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")] public static extern void mouse_event(uint flags,uint x,uint y,uint data,UIntPtr extra);
}
'@
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
 $app.Refresh();$handle=$app.MainWindowHandle
 if($handle -eq [IntPtr]::Zero){foreach($id in $ids){$candidate=Get-Process -Id $id;$candidate.Refresh();if($candidate.MainWindowHandle -ne [IntPtr]::Zero){$handle=$candidate.MainWindowHandle;break}}}
 if($handle -ne [IntPtr]::Zero){[WCodeObservationWindow]::ShowWindow($handle,3)|Out-Null;[WCodeObservationWindow]::SetForegroundWindow($handle)|Out-Null}
 Start-Sleep -Seconds 20
 $bounds=[Windows.Forms.SystemInformation]::VirtualScreen
 function Save-ObservationImage([string]$Name){
  $bitmap=New-Object Drawing.Bitmap $bounds.Width,$bounds.Height;$graphics=[Drawing.Graphics]::FromImage($bitmap)
  try{$graphics.CopyFromScreen($bounds.Left,$bounds.Top,0,0,$bitmap.Size);$bitmap.Save((Join-Path $out $Name),[Drawing.Imaging.ImageFormat]::Png)}finally{$graphics.Dispose();$bitmap.Dispose()}
 }
 Save-ObservationImage 'official-wbcode-1.3.0-first-run.png'
 # The book icon was visually identified in the previous maximized capture.
 # Only open the public guide page; do not enter credentials or activate a license.
 if($bounds.Width -eq 1024 -and $bounds.Height -eq 768){
  [WCodeObservationWindow]::SetCursorPos(36,474)|Out-Null;Start-Sleep -Seconds 2
  Save-ObservationImage 'official-wbcode-1.3.0-guides-hover.png'
  [WCodeObservationWindow]::mouse_event(2,0,0,0,[UIntPtr]::Zero);[WCodeObservationWindow]::mouse_event(4,0,0,0,[UIntPtr]::Zero)
  Start-Sleep -Seconds 8;Save-ObservationImage 'official-wbcode-1.3.0-guides.png'
 }
 $windows=@($ids|ForEach-Object {[VNcode.Smoke.VisibleWindows]::Titles($_)})
 @{source='official release installer';release='v1.3.0';installerSha256=$hash;nativeLaunch='passed';visibleTitles=$windows;windowMaximized=($handle -ne [IntPtr]::Zero);screenWidth=$bounds.Width;screenHeight=$bounds.Height;freshRunner=$true;shopCredentialsEntered=$false;liveMarketplaceMutations=$false;licenseActivated=$false;scope='First-run UI only; authenticated workflows not tested';screenshot='official-wbcode-1.3.0-first-run.png'}|ConvertTo-Json|Set-Content (Join-Path $out 'observation.json') -Encoding utf8
}finally{if($app -and -not $app.HasExited){Stop-Process -Id $app.Id -Force;$app.WaitForExit()};if($installed){$p=Start-Process msiexec.exe -ArgumentList @('/x',"`"$msi`"",'/qn','/norestart') -Wait -PassThru;if($p.ExitCode -notin @(0,3010)){throw 'Official app uninstall failed.'}}}
