$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class SymphonyProcessExit {
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(uint access, bool inherit, uint processId);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);

    public static bool Wait(uint processId, uint milliseconds) {
        const uint Synchronize = 0x00100000;
        IntPtr handle = OpenProcess(Synchronize, false, processId);
        if (handle == IntPtr.Zero) {
            int error = Marshal.GetLastWin32Error();
            if (error == 87) return true; // The process object no longer exists.
            throw new Win32Exception(error, "cannot wait for Windows process: check process access permissions");
        }
        try {
            uint result = WaitForSingleObject(handle, milliseconds);
            if (result == 0) return true;
            if (result == 258) return false;
            throw new Win32Exception(Marshal.GetLastWin32Error(),
                "cannot wait for Windows process: inspect the managed process before retrying");
        } finally {
            CloseHandle(handle);
        }
    }
}
'@

$elapsed = [System.Diagnostics.Stopwatch]::StartNew()
foreach ($processId in '@PROCESS_IDS@'.Split(',')) {
    $remaining = [uint32][Math]::Max(0, [long]'@TIMEOUT_MS@' - $elapsed.ElapsedMilliseconds)
    if (-not [SymphonyProcessExit]::Wait([uint32]$processId, $remaining)) { exit 1 }
}
exit 0
