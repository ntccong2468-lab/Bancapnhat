# Read visible windows belonging to this launcher or its direct JVM child.
# Process.MainWindowTitle can identify a JavaFX helper window rather than the stage.
if (-not ('VNcode.Smoke.VisibleWindows' -as [type])) {
    Add-Type @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
namespace VNcode.Smoke {
    public static class VisibleWindows {
        private delegate bool WindowCallback(IntPtr window, IntPtr parameter);
        [DllImport("user32.dll")] private static extern bool EnumWindows(WindowCallback callback, IntPtr parameter);
        [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr window);
        [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr window, out uint process);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowText(IntPtr window, StringBuilder text, int maximum);
        public static string[] Titles(int processId) {
            var titles = new List<string>();
            EnumWindows((window, parameter) => {
                uint owner; GetWindowThreadProcessId(window, out owner);
                if (owner == processId && IsWindowVisible(window)) {
                    var title = new StringBuilder(4096);
                    GetWindowText(window, title, title.Capacity);
                    if (title.Length > 0) titles.Add(title.ToString());
                }
                return true;
            }, IntPtr.Zero);
            return titles.ToArray();
        }
    }
}
'@
}

function Wait-VncodeWindow([Diagnostics.Process] $Process, [string] $Version) {
    $expected = "VN code v$Version"
    $titles = @()
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $Process.Refresh()
        if ($Process.HasExited) { throw 'The native launcher exited before showing its window.' }
        $processIds = @($Process.Id)
        $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $($Process.Id)")
        $processIds += @($children | ForEach-Object { [int]$_.ProcessId })
        $titles = @($processIds | ForEach-Object { [VNcode.Smoke.VisibleWindows]::Titles($_) })
        if ($titles -ccontains $expected) { return $expected }
        Start-Sleep -Milliseconds 500
    }
    throw "Expected native window '$expected'; launcher PID $($Process.Id), visible titles: $($titles -join ' | ')."
}
