$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$line = (Get-CimInstance Win32_Process -Filter "ProcessId = @PROCESS_ID@").CommandLine
if ([string]::IsNullOrWhiteSpace($line)) { exit 1 }
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class SymphonyProcessArguments {
    [DllImport("shell32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern IntPtr CommandLineToArgvW(string commandLine, out int count);
    [DllImport("kernel32.dll")]
    public static extern IntPtr LocalFree(IntPtr memory);
}
'@
$count = 0
$memory = [SymphonyProcessArguments]::CommandLineToArgvW($line, [ref] $count)
if ($memory -eq [IntPtr]::Zero) { exit 1 }
try {
    $arguments = [string[]]::new($count)
    for ($index = 0; $index -lt $count; $index++) {
        $pointer = [System.Runtime.InteropServices.Marshal]::ReadIntPtr($memory, $index * [IntPtr]::Size)
        $arguments[$index] = [System.Runtime.InteropServices.Marshal]::PtrToStringUni($pointer)
    }
    ConvertTo-Json -InputObject $arguments -Compress
} finally {
    [void] [SymphonyProcessArguments]::LocalFree($memory)
}
