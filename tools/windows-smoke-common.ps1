# Read visible windows belonging to this launcher or its direct JVM child.
# Process.MainWindowTitle can identify a JavaFX helper window rather than the stage.
if (-not ('VNcode.Smoke.VisibleWindows' -as [type])) {
    Add-Type @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
namespace VNcode.Smoke {
    // Resource-only loading does not execute the original installer or its entry point.
    public static class EmbeddedMsi {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr LoadLibraryEx(string path, IntPtr file, uint flags);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr FindResource(IntPtr module, string name, IntPtr type);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern IntPtr LoadResource(IntPtr module, IntPtr resource);
        [DllImport("kernel32.dll")] private static extern IntPtr LockResource(IntPtr resource);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern uint SizeofResource(IntPtr module, IntPtr resource);
        [DllImport("kernel32.dll")] private static extern bool FreeLibrary(IntPtr module);
        public static void Extract(string executable, string destination) {
            IntPtr module = LoadLibraryEx(executable, IntPtr.Zero, 0x2 | 0x20);
            if (module == IntPtr.Zero) throw new InvalidOperationException("Cannot load installer resources.");
            try {
                IntPtr resource = FindResource(module, "MSI", new IntPtr(10));
                if (resource == IntPtr.Zero) throw new InvalidOperationException("No embedded MSI resource.");
                uint size = SizeofResource(module, resource);
                if (size < 8 || size > int.MaxValue) throw new InvalidOperationException("Invalid embedded MSI size.");
                IntPtr handle = LoadResource(module, resource);
                IntPtr address = LockResource(handle);
                if (handle == IntPtr.Zero || address == IntPtr.Zero) throw new InvalidOperationException("Cannot read MSI resource.");
                byte[] data = new byte[(int)size];
                Marshal.Copy(address, data, 0, data.Length);
                byte[] magic = { 0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1 };
                for (int i = 0; i < magic.Length; i++)
                    if (data[i] != magic[i]) throw new InvalidOperationException("Invalid MSI compound-document header.");
                System.IO.File.WriteAllBytes(destination, data);
            } finally { FreeLibrary(module); }
        }
    }
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
