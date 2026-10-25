using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

// Native installer tests own a private console so Ctrl+C never targets the test runner's console.
public static class PrivateConsoleProof
{
    private const uint NewConsole = 0x10;
    private const uint Suspended = 0x4;
    private const uint WaitObject = 0;
    private const uint WaitTimeout = 258;
    private const uint QueryProcess = 0x1000;
    private const uint Synchronize = 0x100000;
    private const uint JobQuery = 0x4;
    private const uint KillOnJobClose = 0x2000;
    private const uint WindowStationReadAttributes = 0x2;
    private const uint VisibleWindowStation = 0x1;
    private static int ControllerCtrlCCount;
    private static readonly Handler IgnoreControllerCtrlC = delegate(uint signal) {
        if (signal != 0) return false;
        Interlocked.Increment(ref ControllerCtrlCCount);
        return true;
    };

    public sealed class OwnerResult
    {
        public uint ControllerExitCode;
        public int SessionId;
        public bool InteractiveStationVisible;
        public bool CleanupConfirmed;
    }

    public sealed class ProofResult
    {
        public bool SignalSent;
        public uint TargetPid;
        public uint TargetExitCode;
        public uint ReadyProcessId;
        public uint[] ConsolePids;
    }

    public static OwnerResult RunOwner(string shell, string[] arguments, string jobName, int timeoutSeconds)
    {
        IntPtr job = CreateJobObject(IntPtr.Zero, jobName);
        Require(job != IntPtr.Zero, "create owned job");
        PROCESS_INFORMATION controller = new PROCESS_INFORMATION();
        OwnerResult result = new OwnerResult();
        try
        {
            JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
            limits.BasicLimitInformation.LimitFlags = KillOnJobClose;
            Require(SetInformationJobObject(job, 9, ref limits,
                (uint)Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION))), "set job cleanup");
            RequireInteractiveStation(result);
            // Suspension closes the race in which the controller spawns children before containment.
            // The existing interactive station supplies the real UserInteractive prerequisite.
            controller = Start(shell, arguments, NewConsole | Suspended, @"winsta0\default");
            Require(AssignProcessToJobObject(job, controller.hProcess), "contain private controller");
            Require(ResumeThread(controller.hThread) != uint.MaxValue, "resume private controller");
            Require(WaitForSingleObject(controller.hProcess, (uint)(timeoutSeconds * 1000)) == WaitObject,
                "wait for private controller");
            Require(GetExitCodeProcess(controller.hProcess, out result.ControllerExitCode), "read controller exit");
        }
        finally
        {
            try
            {
                // The retained handle also permits cleanup if job assignment failed while suspended.
                bool controllerStopped = true;
                if (controller.hProcess != IntPtr.Zero)
                {
                    if (WaitForSingleObject(controller.hProcess, 0) == WaitTimeout)
                        TerminateProcess(controller.hProcess, 1);
                    controllerStopped = WaitForSingleObject(controller.hProcess, 5000) == WaitObject;
                }
                bool terminated = TerminateJobObject(job, 1);
                Stopwatch deadline = Stopwatch.StartNew();
                uint active;
                bool queried = TryActiveProcesses(job, out active);
                while (terminated && queried && active != 0 && deadline.ElapsedMilliseconds < 5000)
                {
                    Thread.Sleep(25);
                    queried = TryActiveProcesses(job, out active);
                }
                result.CleanupConfirmed = controllerStopped && terminated && queried && active == 0;
            }
            finally
            {
                Close(controller.hThread);
                Close(controller.hProcess);
                // Kill-on-close still applies if a cleanup query itself failed.
                Close(job);
            }
        }
        return result;
    }

    public static ProofResult RunController(string shell, string commandFile, string readyFile,
        string jobName, string readyToken, int readyTimeoutSeconds, int exitTimeoutSeconds)
    {
        IntPtr job = OpenJobObject(JobQuery, false, jobName);
        Require(job != IntPtr.Zero, "open owned job");
        PROCESS_INFORMATION target = new PROCESS_INFORMATION();
        IntPtr readyProcess = IntPtr.Zero;
        try
        {
            bool owned;
            Require(IsProcessInJob(GetCurrentProcess(), job, out owned) && owned, "verify controller ownership");
            uint self = GetCurrentProcessId();
            uint[] initial = ConsoleProcesses();
            Require(initial.Length == 1 && initial[0] == self, "verify private console before launch");
            // Clear any inherited ignore flag before launching the target. A custom controller-only
            // handler survives the later event without teaching the target to ignore Ctrl+C.
            Require(SetConsoleCtrlHandler(null, false), "enable console interruption");
            Require(SetConsoleCtrlHandler(IgnoreControllerCtrlC, true), "protect private controller");
            Environment.SetEnvironmentVariable("SYMPHONY_CONSOLE_PROOF_READY_TOKEN", readyToken);
            Environment.SetEnvironmentVariable("SYMPHONY_CONSOLE_PROOF_READY_FILE", readyFile);
            // No redirected input and no new process group: the target uses this private console.
            target = Start(shell, new string[] { "-NoProfile", "-File", commandFile }, 0, null);
            Require(IsProcessInJob(target.hProcess, job, out owned) && owned, "verify target ownership");
            Stopwatch readyDeadline = Stopwatch.StartNew();
            uint readyPid;
            while (!IsReady(readyFile, readyToken, out readyPid))
            {
                RequireLiveTarget(target.hProcess);
                Require(readyDeadline.ElapsedMilliseconds < readyTimeoutSeconds * 1000L, "wait for readiness marker");
                Thread.Sleep(25);
            }
            Require(readyPid != self, "verify readiness belongs to test command");
            readyProcess = OpenProcess(QueryProcess | Synchronize, false, readyPid);
            Require(readyProcess != IntPtr.Zero, "open ready process");
            Require(IsProcessInJob(readyProcess, job, out owned) && owned, "verify ready process ownership");
            Require(WaitForSingleObject(readyProcess, 0) == WaitTimeout, "verify ready process is alive");
            Require(WaitForSingleObject(target.hProcess, 0) == WaitTimeout, "verify target before signal");
            uint[] members = ConsoleProcesses();
            bool sawTarget = false;
            bool sawController = false;
            bool sawReady = false;
            foreach (uint pid in members)
            {
                IntPtr process = OpenProcess(QueryProcess, false, pid);
                Require(process != IntPtr.Zero, "open console member");
                try
                {
                    Require(IsProcessInJob(process, job, out owned) && owned, "verify console member ownership");
                }
                finally { Close(process); }
                sawTarget |= pid == target.dwProcessId;
                sawController |= pid == self;
                sawReady |= pid == readyPid;
            }
            Require(sawTarget && sawController && sawReady, "verify private console membership");
            // CTRL_C_EVENT cannot address a nonzero process group. Zero is safe here only because
            // this controller created the private console and every current member is job-owned.
            Require(GenerateConsoleCtrlEvent(0, 0), "signal verified private console");
            ProofResult result = new ProofResult();
            result.SignalSent = true;
            result.TargetPid = target.dwProcessId;
            result.ReadyProcessId = readyPid;
            result.ConsolePids = members;
            Stopwatch exitDeadline = Stopwatch.StartNew();
            uint targetWait = WaitForSingleObject(target.hProcess, (uint)(exitTimeoutSeconds * 1000));
            if (targetWait != WaitObject)
            {
                int error = targetWait == uint.MaxValue ? Marshal.GetLastWin32Error() : 0;
                Exception failure = Failure("wait for interrupted target exit");
                failure.Data["SignalSent"] = true;
                failure.Data["ControllerCtrlCCount"] = Volatile.Read(ref ControllerCtrlCCount);
                failure.Data["InterruptedTargetWaitResult"] = targetWait;
                if (targetWait == uint.MaxValue) failure.Data["InterruptedTargetWaitError"] = error;
                AddProcessFacts(failure, "Target", target.hProcess);
                AddProcessFacts(failure, "Ready", readyProcess);
                throw failure;
            }
            uint remaining = (uint)Math.Max(0L, exitTimeoutSeconds * 1000L - exitDeadline.ElapsedMilliseconds);
            Require(WaitForSingleObject(readyProcess, remaining) == WaitObject, "wait for interrupted ready process exit");
            Require(GetExitCodeProcess(target.hProcess, out result.TargetExitCode), "read target exit");
            return result;
        }
        finally
        {
            Close(target.hThread);
            Close(target.hProcess);
            Close(readyProcess);
            Close(job);
            GC.KeepAlive(IgnoreControllerCtrlC);
        }
    }

    private static bool IsReady(string path, string token, out uint pid)
    {
        pid = 0;
        if (String.IsNullOrEmpty(token)) return false;
        try
        {
            string[] lines = File.ReadAllLines(path);
            return lines.Length == 2 && lines[0] == token && UInt32.TryParse(lines[1], out pid) && pid != 0;
        }
        catch (IOException) { return false; }
    }

    private static uint[] ConsoleProcesses()
    {
        uint[] pids = new uint[16];
        for (int attempt = 0; attempt < 4; attempt++)
        {
            uint count = GetConsoleProcessList(pids, (uint)pids.Length);
            Require(count != 0 && count <= 4096, "enumerate private console");
            if (count <= pids.Length)
            {
                Array.Resize(ref pids, (int)count);
                return pids;
            }
            pids = new uint[count];
        }
        throw Failure("stabilize private console membership");
    }

    private static bool TryActiveProcesses(IntPtr job, out uint active)
    {
        JOBOBJECT_BASIC_ACCOUNTING_INFORMATION info;
        bool success = QueryInformationJobObject(job, 1, out info,
            (uint)Marshal.SizeOf(typeof(JOBOBJECT_BASIC_ACCOUNTING_INFORMATION)), IntPtr.Zero);
        active = info.ActiveProcesses;
        return success;
    }

    private static void RequireInteractiveStation(OwnerResult result)
    {
        using (Process current = Process.GetCurrentProcess()) result.SessionId = current.SessionId;
        IntPtr station = OpenWindowStation("WinSta0", false, WindowStationReadAttributes);
        if (station == IntPtr.Zero)
            throw StationFailure("open existing interactive window station", result.SessionId,
                null, Marshal.GetLastWin32Error());
        try
        {
            USEROBJECTFLAGS flags;
            uint returned;
            if (!GetUserObjectInformation(station, 1, out flags,
                (uint)Marshal.SizeOf(typeof(USEROBJECTFLAGS)), out returned))
                throw StationFailure("read existing window station flags", result.SessionId,
                    null, Marshal.GetLastWin32Error());
            result.InteractiveStationVisible = (flags.dwFlags & VisibleWindowStation) != 0;
            if (!result.InteractiveStationVisible)
                throw StationFailure("verify existing interactive window station", result.SessionId, false, null);
        }
        finally { CloseWindowStation(station); }
    }

    private static Exception StationFailure(string operation, int sessionId, bool? visible, int? error)
    {
        Exception failure = Failure(operation);
        failure.Data["SessionId"] = sessionId;
        if (visible.HasValue) failure.Data["InteractiveStationVisible"] = visible.Value;
        if (error.HasValue) failure.Data["WindowStationError"] = error.Value;
        return failure;
    }

    private static PROCESS_INFORMATION Start(string executable, string[] arguments, uint flags, string desktop)
    {
        StringBuilder command = new StringBuilder(Quote(executable));
        foreach (string argument in arguments) command.Append(" ").Append(Quote(argument));
        STARTUPINFO startup = new STARTUPINFO();
        startup.cb = (uint)Marshal.SizeOf(typeof(STARTUPINFO));
        startup.lpDesktop = desktop;
        // A hidden console still supplies console handles. No caller std handles are inherited.
        startup.dwFlags = 1;
        startup.wShowWindow = 0;
        PROCESS_INFORMATION process;
        if (!CreateProcess(executable, command, IntPtr.Zero, IntPtr.Zero, false, flags,
            IntPtr.Zero, null, ref startup, out process))
        {
            int error = Marshal.GetLastWin32Error();
            Exception failure = Failure("launch test process");
            failure.Data["LaunchError"] = error;
            throw failure;
        }
        return process;
    }

    private static string Quote(string value)
    {
        StringBuilder quoted = new StringBuilder("\"");
        int slashes = 0;
        foreach (char character in value)
        {
            if (character == '\\') { slashes++; continue; }
            quoted.Append('\\', character == '"' ? slashes * 2 + 1 : slashes);
            quoted.Append(character);
            slashes = 0;
        }
        return quoted.Append('\\', slashes * 2).Append('"').ToString();
    }

    private static void AddProcessFacts(Exception failure, string prefix, IntPtr process)
    {
        uint waitResult = WaitForSingleObject(process, 0);
        int waitError = waitResult == uint.MaxValue ? Marshal.GetLastWin32Error() : 0;
        failure.Data[prefix + "WaitResult"] = waitResult;
        if (waitResult == uint.MaxValue) failure.Data[prefix + "WaitError"] = waitError;
        uint exitCode;
        if (GetExitCodeProcess(process, out exitCode)) failure.Data[prefix + "ExitCode"] = exitCode;
        else failure.Data[prefix + "ExitReadError"] = Marshal.GetLastWin32Error();
    }

    private static void RequireLiveTarget(IntPtr target)
    {
        uint waitResult = WaitForSingleObject(target, 0);
        if (waitResult == WaitTimeout) return;
        int waitError = waitResult == uint.MaxValue ? Marshal.GetLastWin32Error() : 0;
        Exception failure = Failure("wait for live target readiness");
        failure.Data["TargetWaitResult"] = waitResult;
        if (waitResult == uint.MaxValue) failure.Data["TargetWaitError"] = waitError;
        uint exitCode;
        if (GetExitCodeProcess(target, out exitCode)) failure.Data["TargetExitCode"] = exitCode;
        else failure.Data["TargetExitReadError"] = Marshal.GetLastWin32Error();
        throw failure;
    }

    private static void Require(bool valid, string operation) { if (!valid) throw Failure(operation); }
    private static Exception Failure(string operation)
    {
        Exception failure = new InvalidOperationException("cannot " + operation + " \"native console\": check the Windows fixture and native API result");
        failure.Data["ConsoleProofOperation"] = operation;
        return failure;
    }
    private static void Close(IntPtr handle) { if (handle != IntPtr.Zero) CloseHandle(handle); }

    private delegate bool Handler(uint signal);
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct STARTUPINFO
    {
        public uint cb; public string lpReserved, lpDesktop, lpTitle;
        public uint dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
        public ushort wShowWindow, cbReserved2; public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct USEROBJECTFLAGS { public int fInherit, fReserved; public uint dwFlags; }
    [StructLayout(LayoutKind.Sequential)]
    private struct PROCESS_INFORMATION { public IntPtr hProcess, hThread; public uint dwProcessId, dwThreadId; }
    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_BASIC_LIMIT_INFORMATION
    {
        public long PerProcessUserTimeLimit, PerJobUserTimeLimit; public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize, MaximumWorkingSetSize; public uint ActiveProcessLimit;
        public UIntPtr Affinity; public uint PriorityClass, SchedulingClass;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct IO_COUNTERS { public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount, ReadTransferCount, WriteTransferCount, OtherTransferCount; }
    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION
    {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation; public IO_COUNTERS IoInfo;
        public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_BASIC_ACCOUNTING_INFORMATION
    {
        public long TotalUserTime, TotalKernelTime, ThisPeriodTotalUserTime, ThisPeriodTotalKernelTime;
        public uint TotalPageFaultCount, TotalProcesses, ActiveProcesses, TotalTerminatedProcesses;
    }
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr OpenWindowStation(string name, bool inherit, uint access);
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool GetUserObjectInformation(IntPtr station, int index, out USEROBJECTFLAGS flags,
        uint length, out uint returned);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool CloseWindowStation(IntPtr station);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CreateProcess(string application, StringBuilder command, IntPtr processAttributes,
        IntPtr threadAttributes, bool inheritHandles, uint flags, IntPtr environment, string directory,
        ref STARTUPINFO startup, out PROCESS_INFORMATION process);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern IntPtr CreateJobObject(IntPtr attributes, string name);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern IntPtr OpenJobObject(uint access, bool inherit, string name);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool SetInformationJobObject(IntPtr job, int infoClass, ref JOBOBJECT_EXTENDED_LIMIT_INFORMATION info, uint length);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool QueryInformationJobObject(IntPtr job, int infoClass, out JOBOBJECT_BASIC_ACCOUNTING_INFORMATION info, uint length, IntPtr returned);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool contained);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool TerminateJobObject(IntPtr job, uint exitCode);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool TerminateProcess(IntPtr process, uint exitCode);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern uint ResumeThread(IntPtr thread);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GetExitCodeProcess(IntPtr process, out uint code);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern uint GetConsoleProcessList([Out] uint[] pids, uint length);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool SetConsoleCtrlHandler(Handler handler, bool add);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GenerateConsoleCtrlEvent(uint signal, uint processGroup);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
    [DllImport("kernel32.dll")] private static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32.dll")] private static extern uint GetCurrentProcessId();
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);
}
