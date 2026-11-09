#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $Directory,
    [ValidateSet('Snapshot', 'Observe')][string] $Mode = 'Snapshot',
    [ValidateRange(0, 2147483647)][int] $ParentProcessId = 0,
    [ValidateRange(1, 14400)][int] $MaxSeconds = 1200
)

# Diagnostic only: no injected input, desktop/session changes, power-setting
# writes, registry writes, account names, command lines or window titles.
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'WINDOWS_INPUT_OBSERVER_REQUIRES_WINDOWS' }

if (-not ('VNcode.Diagnostics.InputObserver' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32;

namespace VNcode.Diagnostics {
    public static class InputObserver {
        [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
        [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
        [StructLayout(LayoutKind.Sequential)] private struct LASTINPUTINFO { public uint cbSize, dwTime; }
        [StructLayout(LayoutKind.Sequential)] private struct GUITHREADINFO {
            public uint cbSize, flags;
            public IntPtr hwndActive, hwndFocus, hwndCapture, hwndMenuOwner, hwndMoveSize, hwndCaret;
            public RECT rcCaret;
        }
        [StructLayout(LayoutKind.Sequential)] private struct SYSTEM_POWER_STATUS {
            public byte ACLineStatus, BatteryFlag, BatteryLifePercent, SystemStatusFlag;
            public uint BatteryLifeTime, BatteryFullLifeTime;
        }
        private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr parameter);
        [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();
        [DllImport("kernel32.dll")] private static extern uint GetTickCount();
        [DllImport("kernel32.dll")] private static extern uint WTSGetActiveConsoleSessionId();
        [DllImport("kernel32.dll", SetLastError = true)] private static extern IntPtr OpenProcess(uint access, bool inherit, uint process);
        [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GetSystemPowerStatus(out SYSTEM_POWER_STATUS value);
        [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr value);
        [DllImport("user32.dll", SetLastError = true)] private static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);
        [DllImport("user32.dll")] private static extern bool CloseDesktop(IntPtr desktop);
        [DllImport("user32.dll", SetLastError = true)] private static extern IntPtr GetThreadDesktop(uint thread);
        [DllImport("user32.dll", SetLastError = true)] private static extern IntPtr GetProcessWindowStation();
        [DllImport("user32.dll", EntryPoint = "GetUserObjectInformationW", ExactSpelling = true, CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool GetUserObjectInformation(IntPtr handle, int index, StringBuilder value, uint size, out uint required);
        [DllImport("user32.dll", SetLastError = true)] private static extern bool GetLastInputInfo(ref LASTINPUTINFO value);
        [DllImport("user32.dll", SetLastError = true)] private static extern bool GetCursorPos(out POINT point);
        [DllImport("user32.dll", SetLastError = true)] private static extern bool GetPhysicalCursorPos(out POINT point);
        [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] private static extern IntPtr WindowFromPoint(POINT point);
        [DllImport("user32.dll")] private static extern IntPtr WindowFromPhysicalPoint(POINT point);
        [DllImport("user32.dll", SetLastError = true)] private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint process);
        [DllImport("user32.dll")] private static extern bool IsWindow(IntPtr hwnd);
        [DllImport("user32.dll")] private static extern bool IsWindowEnabled(IntPtr hwnd);
        [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hwnd);
        [DllImport("user32.dll")] private static extern bool IsIconic(IntPtr hwnd);
        [DllImport("user32.dll")] private static extern IntPtr GetWindow(IntPtr hwnd, uint command);
        [DllImport("user32.dll", EntryPoint = "GetClassNameW", ExactSpelling = true, CharSet = CharSet.Unicode)]
        private static extern int GetClassName(IntPtr hwnd, StringBuilder value, int maximum);
        [DllImport("user32.dll", SetLastError = true)] private static extern bool GetWindowRect(IntPtr hwnd, out RECT bounds);
        [DllImport("user32.dll", SetLastError = true)] private static extern bool GetGUIThreadInfo(uint thread, ref GUITHREADINFO value);
        [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr parameter);
        [DllImport("user32.dll")] private static extern int GetSystemMetrics(int index);
        [DllImport("user32.dll")] private static extern uint GetDpiForWindow(IntPtr hwnd);
        [DllImport("user32.dll")] private static extern IntPtr GetWindowDpiAwarenessContext(IntPtr hwnd);
        [DllImport("user32.dll")] private static extern IntPtr GetThreadDpiAwarenessContext();
        [DllImport("user32.dll")] private static extern int GetAwarenessFromDpiAwarenessContext(IntPtr context);
        [DllImport("user32.dll")] private static extern bool AreDpiAwarenessContextsEqual(IntPtr first, IntPtr second);
        [DllImport("shcore.dll")] private static extern int GetProcessDpiAwareness(IntPtr process, out int awareness);
        [DllImport("user32.dll", EntryPoint = "SystemParametersInfoW", ExactSpelling = true, SetLastError = true)]
        private static extern bool SystemParametersInfo(uint action, uint parameter, out uint value, uint flags);
        [DllImport("wtsapi32.dll", EntryPoint = "WTSQuerySessionInformationW", ExactSpelling = true, SetLastError = true)]
        private static extern bool WTSQuerySessionInformation(IntPtr server, int session, int information, out IntPtr value, out uint bytes);
        [DllImport("wtsapi32.dll")] private static extern void WTSFreeMemory(IntPtr value);
        [DllImport("powrprof.dll")] private static extern uint PowerGetActiveScheme(IntPtr root, out IntPtr scheme);
        [DllImport("powrprof.dll")] private static extern uint PowerReadACValueIndex(IntPtr root, ref Guid scheme, ref Guid group, ref Guid setting, out uint value);
        [DllImport("powrprof.dll")] private static extern uint PowerReadDCValueIndex(IntPtr root, ref Guid scheme, ref Guid group, ref Guid setting, out uint value);

        private static Dictionary<string, object> Map() { return new Dictionary<string, object>(); }
        private static string Handle(IntPtr value) { return unchecked((ulong)value.ToInt64()).ToString(); }
        private static object Point(POINT value) { return new Dictionary<string, object> { { "x", value.X }, { "y", value.Y } }; }
        private static object Rect(RECT value) {
            return new Dictionary<string, object> { { "left", value.Left }, { "top", value.Top }, { "right", value.Right }, { "bottom", value.Bottom } };
        }
        private static object UserObjectName(IntPtr handle) {
            var result = Map();
            if (handle == IntPtr.Zero) { result["available"] = false; return result; }
            var text = new StringBuilder(512); uint required;
            bool ok = GetUserObjectInformation(handle, 2, text, (uint)(text.Capacity * 2), out required);
            result["available"] = ok;
            if (ok) result["name"] = text.ToString(); else result["win32Error"] = Marshal.GetLastWin32Error();
            return result;
        }
        private static object DesktopState() {
            var result = Map();
            result["thread"] = UserObjectName(GetThreadDesktop(GetCurrentThreadId()));
            result["windowStation"] = UserObjectName(GetProcessWindowStation());
            IntPtr input = OpenInputDesktop(0, false, 1); // DESKTOP_READOBJECTS only.
            if (input == IntPtr.Zero) result["input"] = new Dictionary<string, object> { { "available", false }, { "win32Error", Marshal.GetLastWin32Error() } };
            else { try { result["input"] = UserObjectName(input); } finally { CloseDesktop(input); } }
            return result;
        }
        private static object SessionState() {
            var result = Map(); int session = Process.GetCurrentProcess().SessionId;
            result["observerSessionId"] = session; result["activeConsoleSessionId"] = WTSGetActiveConsoleSessionId();
            IntPtr value; uint bytes;
            if (WTSQuerySessionInformation(IntPtr.Zero, session, 8, out value, out bytes)) {
                try {
                    if (bytes >= 4) {
                        int state = Marshal.ReadInt32(value);
                        string[] names = { "Active", "Connected", "ConnectQuery", "Shadow", "Disconnected", "Idle", "Listen", "Reset", "Down", "Init" };
                        result["connectState"] = state; result["connectStateName"] = state >= 0 && state < names.Length ? names[state] : "Unknown";
                    }
                } finally { WTSFreeMemory(value); }
            } else result["win32Error"] = Marshal.GetLastWin32Error();
            return result;
        }
        private static object SystemParameter(uint action, bool boolean) {
            var result = Map(); uint value;
            if (SystemParametersInfo(action, 0, out value, 0)) { result["available"] = true; result["value"] = boolean ? (object)(value != 0) : value; }
            else { result["available"] = false; result["win32Error"] = Marshal.GetLastWin32Error(); }
            return result;
        }
        private static object GuiThread(uint thread) {
            var result = Map(); var value = new GUITHREADINFO(); value.cbSize = (uint)Marshal.SizeOf(typeof(GUITHREADINFO));
            bool ok = GetGUIThreadInfo(thread, ref value); result["available"] = ok;
            if (!ok) { result["win32Error"] = Marshal.GetLastWin32Error(); return result; }
            result["flags"] = value.flags; result["activeHwnd"] = Handle(value.hwndActive); result["focusHwnd"] = Handle(value.hwndFocus);
            result["captureHwnd"] = Handle(value.hwndCapture); result["menuOwnerHwnd"] = Handle(value.hwndMenuOwner);
            result["moveSizeHwnd"] = Handle(value.hwndMoveSize); result["caretHwnd"] = Handle(value.hwndCaret); result["caretRect"] = Rect(value.rcCaret);
            return result;
        }
        private static object DpiContext(IntPtr context) {
            var result = Map(); result["rawContext"] = context.ToInt64().ToString();
            result["awareness"] = GetAwarenessFromDpiAwarenessContext(context);
            result["unaware"] = AreDpiAwarenessContextsEqual(context, new IntPtr(-1));
            result["systemAware"] = AreDpiAwarenessContextsEqual(context, new IntPtr(-2));
            result["perMonitorAware"] = AreDpiAwarenessContextsEqual(context, new IntPtr(-3));
            result["perMonitorAwareV2"] = AreDpiAwarenessContextsEqual(context, new IntPtr(-4));
            result["unawareGdiScaled"] = AreDpiAwarenessContextsEqual(context, new IntPtr(-5));
            return result;
        }
        private static object ProcessDpi(uint process) {
            var result = Map(); IntPtr handle = IntPtr.Zero;
            try {
                if (process != 0) {
                    handle = OpenProcess(0x1000, false, process); // PROCESS_QUERY_LIMITED_INFORMATION.
                    if (handle == IntPtr.Zero) { result["win32Error"] = Marshal.GetLastWin32Error(); return result; }
                }
                int awareness; int status = GetProcessDpiAwareness(handle, out awareness);
                result["hresult"] = status; if (status == 0) result["awareness"] = awareness;
            } catch (EntryPointNotFoundException) { result["unavailable"] = true; }
            finally { if (handle != IntPtr.Zero) CloseHandle(handle); }
            return result;
        }
        private static Dictionary<string, object> WindowState(IntPtr window, bool includeGuiThread) {
            var result = Map(); result["hwnd"] = Handle(window); result["valid"] = window != IntPtr.Zero && IsWindow(window);
            if (!(bool)result["valid"]) return result;
            uint process; uint thread = GetWindowThreadProcessId(window, out process);
            result["pid"] = process; result["threadId"] = thread;
            if (thread == 0) { result["closedOrUnavailableDuringRead"] = true; result["win32Error"] = Marshal.GetLastWin32Error(); return result; }
            try { using (Process owner = Process.GetProcessById((int)process)) result["processSessionId"] = owner.SessionId; }
            catch (Exception failure) { result["processSessionErrorType"] = failure.GetType().Name; }
            result["enabled"] = IsWindowEnabled(window);
            result["visible"] = IsWindowVisible(window); result["iconic"] = IsIconic(window); result["ownerHwnd"] = Handle(GetWindow(window, 4));
            var text = new StringBuilder(256); GetClassName(window, text, text.Capacity); result["class"] = text.ToString();
            RECT bounds; if (GetWindowRect(window, out bounds)) result["boundsInObserverDpiContext"] = Rect(bounds);
            try { result["dpi"] = GetDpiForWindow(window); } catch (EntryPointNotFoundException) { result["dpiUnavailable"] = true; }
            try { result["windowDpiContext"] = DpiContext(GetWindowDpiAwarenessContext(window)); }
            catch (EntryPointNotFoundException) { result["windowDpiContextUnavailable"] = true; }
            result["processDpi"] = ProcessDpi(process);
            if (includeGuiThread) result["guiThread"] = GuiThread(thread);
            return result;
        }
        private static HashSet<uint> JavaProcesses() {
            var result = new HashSet<uint>();
            foreach (string name in new [] { "java", "javaw" }) {
                foreach (Process process in Process.GetProcessesByName(name)) {
                    try { result.Add((uint)process.Id); } catch (InvalidOperationException) { } finally { process.Dispose(); }
                }
            }
            return result;
        }
        private static object JavaWindows() {
            var processes = JavaProcesses(); var windows = new List<object>();
            EnumWindows((window, parameter) => {
                uint process; GetWindowThreadProcessId(window, out process);
                if (processes.Contains(process)) windows.Add(WindowState(window, true));
                return true;
            }, IntPtr.Zero);
            return windows;
        }
        public static Dictionary<string, object> Sample(string targetHandle) {
            var result = Map();
            result["timeUtc"] = DateTimeOffset.UtcNow.ToString("O"); result["observerPid"] = Process.GetCurrentProcess().Id;
            result["observationalOnly"] = true; result["desktop"] = DesktopState(); result["session"] = SessionState();
            result["screensaverRunning"] = SystemParameter(0x0072, true);
            var last = new LASTINPUTINFO(); last.cbSize = (uint)Marshal.SizeOf(typeof(LASTINPUTINFO));
            if (GetLastInputInfo(ref last)) result["idleMilliseconds"] = unchecked(GetTickCount() - last.dwTime);
            else result["idleWin32Error"] = Marshal.GetLastWin32Error();
            POINT cursor;
            if (GetCursorPos(out cursor)) { result["cursorInObserverDpiContext"] = Point(cursor); result["windowUnderCursor"] = WindowState(WindowFromPoint(cursor), true); }
            else result["cursorWin32Error"] = Marshal.GetLastWin32Error();
            if (GetPhysicalCursorPos(out cursor)) { result["physicalCursor"] = Point(cursor); result["windowUnderPhysicalCursor"] = WindowState(WindowFromPhysicalPoint(cursor), true); }
            else result["physicalCursorWin32Error"] = Marshal.GetLastWin32Error();
            result["foreground"] = WindowState(GetForegroundWindow(), true);
            result["javaWindows"] = JavaWindows();
            ulong handle;
            if (UInt64.TryParse(targetHandle, out handle) && handle != 0) result["targetJavaWindow"] = WindowState(new IntPtr(unchecked((long)handle)), true);
            result["virtualScreen"] = new Dictionary<string, object> { { "x", GetSystemMetrics(76) }, { "y", GetSystemMetrics(77) }, { "width", GetSystemMetrics(78) }, { "height", GetSystemMetrics(79) }, { "monitors", GetSystemMetrics(80) }, { "remoteSession", GetSystemMetrics(0x1000) != 0 } };
            result["observerProcessDpi"] = ProcessDpi(0);
            try { result["observerThreadDpiContext"] = DpiContext(GetThreadDpiAwarenessContext()); }
            catch (EntryPointNotFoundException) { result["observerDpiAwarenessUnavailable"] = true; }
            return result;
        }
        private static object RegistryPolicy(RegistryKey hive, string path, string[] names, string hiveName) {
            var result = Map(); result["hive"] = hiveName; result["path"] = path;
            try {
                using (RegistryKey key = hive.OpenSubKey(path, false)) {
                    result["available"] = key != null;
                    if (key != null) foreach (string name in names) {
                        object value = key.GetValue(name, null, RegistryValueOptions.DoNotExpandEnvironmentNames);
                        // Only fixed numeric policy values; never export executable paths.
                        if (value != null && UInt64.TryParse(Convert.ToString(value), out ulong number)) result[name] = number;
                    }
                }
            } catch (Exception failure) { result["errorType"] = failure.GetType().Name; }
            return result;
        }
        private static object PowerSetting(Guid scheme, Guid group, Guid setting) {
            var result = Map(); uint value;
            uint code = PowerReadACValueIndex(IntPtr.Zero, ref scheme, ref group, ref setting, out value);
            result["acReadCode"] = code; if (code == 0) result["acSeconds"] = value;
            code = PowerReadDCValueIndex(IntPtr.Zero, ref scheme, ref group, ref setting, out value);
            result["dcReadCode"] = code; if (code == 0) result["dcSeconds"] = value;
            return result;
        }
        public static Dictionary<string, object> Initial() {
            var result = Map(); result["observationalOnly"] = true;
            result["screensaverActive"] = SystemParameter(0x0010, true); result["screensaverTimeoutSeconds"] = SystemParameter(0x000E, false);
            result["screensaverSecure"] = SystemParameter(0x0076, true);
            string[] screenNames = { "ScreenSaveActive", "ScreenSaveTimeOut", "ScreenSaverIsSecure" };
            result["registryPolicy"] = new object[] {
                RegistryPolicy(Registry.CurrentUser, @"Control Panel\Desktop", screenNames, "HKCU"),
                RegistryPolicy(Registry.CurrentUser, @"Software\Policies\Microsoft\Windows\Control Panel\Desktop", screenNames, "HKCU"),
                RegistryPolicy(Registry.LocalMachine, @"Software\Policies\Microsoft\Windows\Control Panel\Desktop", screenNames, "HKLM"),
                RegistryPolicy(Registry.LocalMachine, @"Software\Microsoft\Windows\CurrentVersion\Policies\System", new [] { "InactivityTimeoutSecs" }, "HKLM")
            };
            SYSTEM_POWER_STATUS power;
            if (GetSystemPowerStatus(out power)) result["powerStatus"] = new Dictionary<string, object> { { "acLineStatus", power.ACLineStatus }, { "batteryFlag", power.BatteryFlag }, { "batteryLifePercent", power.BatteryLifePercent }, { "batterySaver", power.SystemStatusFlag } };
            else result["powerStatusWin32Error"] = Marshal.GetLastWin32Error();
            IntPtr allocated; uint status = PowerGetActiveScheme(IntPtr.Zero, out allocated); result["activePowerSchemeReadCode"] = status;
            if (status == 0 && allocated != IntPtr.Zero) {
                try {
                    Guid scheme = (Guid)Marshal.PtrToStructure(allocated, typeof(Guid)); result["activePowerScheme"] = scheme.ToString();
                    result["displayIdle"] = PowerSetting(scheme, new Guid("7516b95f-f776-4464-8c53-06167f40cc99"), new Guid("3c0bc021-c8a8-4e07-a973-6b14cbcb2b7e"));
                    result["sleepIdle"] = PowerSetting(scheme, new Guid("238c9fa8-0aad-41ed-83f4-97be242c8f20"), new Guid("29f6c1db-86da-48c5-9fdb-f2b67b1f44da"));
                } finally { LocalFree(allocated); }
            }
            return result;
        }
    }
}
'@
}

try { [IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($Directory)) | Out-Null }
catch { throw 'WINDOWS_INPUT_DIAGNOSTIC_DIRECTORY_FAILED' }
$observerDirectory = [IO.Path]::GetFullPath($Directory)
$observerEncoding = [Text.UTF8Encoding]::new($false)
$observerStem = "windows-input-$PID"
$observerStopFile = Join-Path $observerDirectory 'windows-input.stop'
$observerLog = Join-Path $observerDirectory "$observerStem.jsonl"

function Write-ObserverJson([string] $File, [object] $Value, [bool] $Append) {
    $line = ($Value | ConvertTo-Json -Depth 16 -Compress) + [Environment]::NewLine
    try {
        if ($Append) { [IO.File]::AppendAllText($File, $line, $observerEncoding) }
        else { [IO.File]::WriteAllText($File, $line, $observerEncoding) }
    } catch { throw 'WINDOWS_INPUT_DIAGNOSTIC_WRITE_FAILED' }
}

function Get-TargetHandle {
    # The Java diagnostic extension writes raw unsigned HWND strings to these
    # files. Read only selected fields; never copy titles or arbitrary contents.
    $files = @(Get-ChildItem -LiteralPath $observerDirectory -Filter 'pointer-*-state.jsonl' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTimeUtc -Descending)
    foreach ($file in $files) {
        try {
            $lines = @(Get-Content -LiteralPath $file.FullName -Tail 16 -ErrorAction Stop)
            for ($index = $lines.Count - 1; $index -ge 0; $index--) {
                try {
                    $row = $lines[$index] | ConvertFrom-Json -ErrorAction Stop
                    if ($null -ne $row.targetHwnd) {
                        $nativeHandle = [uint64]0
                        if ([uint64]::TryParse([string]$row.targetHwnd, [ref]$nativeHandle) -and $nativeHandle -gt 0) {
                            return [string]$nativeHandle
                        }
                    }
                } catch { } # A concurrent JSONL append can leave one partial line.
            }
        } catch { } # The matching trace can disappear while its test closes.
    }
    return ''
}

function Get-ObserverSample {
    try {
        return [VNcode.Diagnostics.InputObserver]::Sample((Get-TargetHandle))
    } catch {
        return @{ timeUtc = [DateTimeOffset]::UtcNow.ToString('O'); observationalOnly = $true; diagnosticErrorType = $_.Exception.GetType().Name }
    }
}

$initial = Get-ObserverSample
$initial['mode'] = $Mode
$initial['parentProcessId'] = $ParentProcessId
$initial['maxSeconds'] = $MaxSeconds
$initial['policy'] = [VNcode.Diagnostics.InputObserver]::Initial()
if ($Mode -eq 'Snapshot') {
    $name = "windows-input-snapshot-$([DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfffZ'))-$PID.json"
    Write-ObserverJson (Join-Path $observerDirectory $name) $initial $false
    Write-Output $name
    return
}

Write-ObserverJson (Join-Path $observerDirectory "windows-input-initial-$PID.json") $initial $false
$observerClock = [Diagnostics.Stopwatch]::StartNew()
$observerReason = 'max-seconds'
while ($observerClock.Elapsed.TotalSeconds -lt $MaxSeconds) {
    if (Test-Path -LiteralPath $observerStopFile) { $observerReason = 'stop-file'; break }
    if ($ParentProcessId -gt 0 -and -not (Get-Process -Id $ParentProcessId -ErrorAction SilentlyContinue)) { $observerReason = 'parent-exited'; break }
    Write-ObserverJson $observerLog (Get-ObserverSample) $true
    Start-Sleep -Seconds 1
}
Write-ObserverJson $observerLog @{ timeUtc = [DateTimeOffset]::UtcNow.ToString('O'); observationalOnly = $true; observerStopped = $true; reason = $observerReason } $true
Write-Output ([IO.Path]::GetFileName($observerLog))
