param(
    [Parameter(Mandatory=$true)][ValidateSet('Snapshot','Enable','Disable','Restore')][string]$Mode,
    [Parameter(Mandatory=$true)][string]$StateFile,
    [Parameter(Mandatory=$true)][string]$EvidenceFile
)
$ErrorActionPreference='Stop'
if(-not $IsWindows -or $env:GITHUB_ACTIONS -cne 'true'){
    throw 'This transient desktop-preference diagnostic is restricted to an isolated GitHub Actions Windows worker.'
}
if(-not ('VnCodeMotionDiagnostic.Native' -as [type])){
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
namespace VnCodeMotionDiagnostic {
    public static class Native {
        [StructLayout(LayoutKind.Sequential)] private struct AnimationInfo { public uint cbSize; public int iMinAnimate; }
        [DllImport("user32.dll",EntryPoint="SystemParametersInfoW",SetLastError=true)] private static extern bool GetClient(uint action,uint param,ref int value,uint flags);
        [DllImport("user32.dll",EntryPoint="SystemParametersInfoW",SetLastError=true)] private static extern bool SetClient(uint action,uint param,IntPtr value,uint flags);
        [DllImport("user32.dll",EntryPoint="SystemParametersInfoW",SetLastError=true)] private static extern bool Animation(uint action,uint param,ref AnimationInfo value,uint flags);
        public static bool GetClientAreaAnimation(){ int value=0; if(!GetClient(0x1042,0,ref value,0))throw new Win32Exception(Marshal.GetLastWin32Error());return value!=0; }
        public static bool GetMinimizeAnimation(){ var value=new AnimationInfo{cbSize=(uint)Marshal.SizeOf<AnimationInfo>()};if(!Animation(0x0048,value.cbSize,ref value,0))throw new Win32Exception(Marshal.GetLastWin32Error());return value.iMinAnimate!=0; }
        public static void SetClientAreaAnimation(bool enabled){if(!SetClient(0x1043,0,enabled?new IntPtr(1):IntPtr.Zero,2))throw new Win32Exception(Marshal.GetLastWin32Error());}
        public static void SetMinimizeAnimation(bool enabled){var value=new AnimationInfo{cbSize=(uint)Marshal.SizeOf<AnimationInfo>(),iMinAnimate=enabled?1:0};if(!Animation(0x0049,value.cbSize,ref value,2))throw new Win32Exception(Marshal.GetLastWin32Error());}
    }
}
'@
}
function Read-MotionState {
    return [ordered]@{clientAreaAnimation=[VnCodeMotionDiagnostic.Native]::GetClientAreaAnimation();minimizeAnimation=[VnCodeMotionDiagnostic.Native]::GetMinimizeAnimation()}
}
New-Item -ItemType Directory -Force (Split-Path -Parent ([IO.Path]::GetFullPath($EvidenceFile))) | Out-Null
$before=Read-MotionState
$result=[ordered]@{mode=$Mode;before=$before;after=$null;transientOnly=$true;registryWritten=$false;flags='SPIF_SENDCHANGE (0x2), without SPIF_UPDATEINIFILE';status='STARTED';errors=@()}
try {
    if($Mode -ceq 'Snapshot'){
        if(-not (Test-Path -LiteralPath $StateFile)){
            New-Item -ItemType Directory -Force (Split-Path -Parent ([IO.Path]::GetFullPath($StateFile))) | Out-Null
            [ordered]@{schema=1;original=$before;runId=$env:GITHUB_RUN_ID;createdAtUtc=[DateTime]::UtcNow.ToString('O')} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $StateFile -Encoding utf8NoBOM
        }
    } else {
        if(-not (Test-Path -LiteralPath $StateFile)){throw 'An original snapshot is required before changing preferences.'}
        $saved=Get-Content -LiteralPath $StateFile -Raw | ConvertFrom-Json
        if($saved.schema -ne 1 -or $saved.runId -cne $env:GITHUB_RUN_ID -or $null -eq $saved.original.clientAreaAnimation -or $null -eq $saved.original.minimizeAnimation){throw 'Original motion snapshot does not belong to this diagnostic run.'}
        $client=if($Mode -ceq 'Restore'){[bool]$saved.original.clientAreaAnimation}else{$Mode -ceq 'Enable'}
        $minimize=if($Mode -ceq 'Restore'){[bool]$saved.original.minimizeAnimation}else{$Mode -ceq 'Enable'}
        $problems=[System.Collections.Generic.List[string]]::new()
        try {[VnCodeMotionDiagnostic.Native]::SetClientAreaAnimation($client)} catch {$problems.Add("SPI_SETCLIENTAREAANIMATION: $($_.Exception.Message)")}
        try {[VnCodeMotionDiagnostic.Native]::SetMinimizeAnimation($minimize)} catch {$problems.Add("SPI_SETANIMATION: $($_.Exception.Message)")}
        $result.after=Read-MotionState
        if($result.after.clientAreaAnimation -ne $client){$problems.Add('Client-area animation readback differs from the requested value.')}
        if($result.after.minimizeAnimation -ne $minimize){$problems.Add('Minimize animation readback differs from the requested value.')}
        if($problems.Count -gt 0){throw ($problems -join ' ')}
    }
    if($null -eq $result.after){$result.after=Read-MotionState}
    $result.status='PASS'
} catch {
    $result.status='FAIL';$result.errors=@($_.Exception.Message)
    throw
} finally {
    $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $EvidenceFile -Encoding utf8NoBOM
}
[pscustomobject]$result
