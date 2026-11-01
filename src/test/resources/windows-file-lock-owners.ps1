param([Parameter(Mandatory = $true)][string]$Path)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
# Read-only diagnostics for native process-test cleanup failures. Never stop a reported owner.
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class TestFileLockOwners {
    [StructLayout(LayoutKind.Sequential)]
    public struct UniqueProcess {
        public uint ProcessId;
        public System.Runtime.InteropServices.ComTypes.FILETIME StartTime;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct ProcessInfo {
        public UniqueProcess Process;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string Application;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string Service;
        public uint Type;
        public uint Status;
        public uint Session;
        [MarshalAs(UnmanagedType.Bool)] public bool Restartable;
    }

    [DllImport("rstrtmgr.dll", CharSet = CharSet.Unicode)]
    private static extern uint RmStartSession(out uint session, uint flags, StringBuilder key);
    [DllImport("rstrtmgr.dll")]
    private static extern uint RmEndSession(uint session);
    [DllImport("rstrtmgr.dll", CharSet = CharSet.Unicode)]
    private static extern uint RmRegisterResources(uint session, uint fileCount,
        string[] files, uint processCount, IntPtr processes, uint serviceCount, IntPtr services);
    [DllImport("rstrtmgr.dll")]
    private static extern uint RmGetList(uint session, out uint needed, ref uint count,
        [In, Out] ProcessInfo[] processes, ref uint reasons);

    private static void Check(uint result) {
        if (result != 0) {
            throw new InvalidOperationException("cannot inspect test file locks: Windows error " + result);
        }
    }

    public static uint[] Find(string path) {
        uint session;
        Check(RmStartSession(out session, 0, new StringBuilder(33)));
        try {
            Check(RmRegisterResources(session, 1, new string[] { path }, 0, IntPtr.Zero, 0, IntPtr.Zero));
            uint count = 0, needed, reasons = 0;
            uint result = RmGetList(session, out needed, ref count, null, ref reasons);
            if (result == 0) return new uint[0];
            if (result != 234) Check(result);
            count = needed;
            ProcessInfo[] owners = new ProcessInfo[count];
            Check(RmGetList(session, out needed, ref count, owners, ref reasons));
            uint[] ids = new uint[count];
            for (int i = 0; i < count; i++) ids[i] = owners[i].Process.ProcessId;
            return ids;
        } finally {
            RmEndSession(session);
        }
    }
}
'@

$owners = @([TestFileLockOwners]::Find($Path))
if ($owners.Count -eq 0) {
    Write-Output 'No file owner remains by the time Restart Manager queries the failed deletion.'
}
foreach ($ownerId in $owners) {
    $owner = Get-Process -Id $ownerId -ErrorAction SilentlyContinue
    Write-Output "File owner: pid=$ownerId name=$($owner.ProcessName)"
}
