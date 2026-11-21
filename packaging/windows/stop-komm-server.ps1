# Stops everything a komm-server installation has running, so its folder can be removed.
#
# The Windows service can stop and leave the server it started behind: the server's Java, LiveKit and the embedded
# PostgreSQL keep running from the install folder and hold its files open, so the folder cannot be deleted. The uninstaller
# runs this before it deletes anything; it can also be run by hand to clean up an installation that was left behind:
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File stop-komm-server.ps1 -Root "C:\ProgramData\Komm\Server" -Delete -Elevate
#
#   -Root     the install folder (the one with komm-server.exe, app\ and data\)
#   -Delete   also remove the folder afterwards (this deletes the server's data too)
#   -Elevate  ask for administrator rights when not started with them (the server runs as SYSTEM)
#   -Pause    keep the window open at the end (set automatically with -Elevate)
param(
    [Parameter(Mandatory = $true)] [string] $Root,
    [string] $ServiceName = 'KommServer',
    [switch] $Delete,
    [switch] $Elevate,
    [switch] $Pause
)

$Root = $Root.TrimEnd('\')
$log = Join-Path $env:TEMP 'komm-stop.log'
function Log($message) {
    Add-Content -Path $log -Value ('{0} {1}' -f (Get-Date -Format 's'), $message) -ErrorAction SilentlyContinue
    Write-Host $message
}

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($Elevate -and -not $isAdmin) {
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"", '-Root', "`"$Root`"", '-ServiceName', $ServiceName, '-Pause')
    if ($Delete) { $arguments += '-Delete' }
    Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -ArgumentList $arguments
    exit
}
if (-not $isAdmin) {
    Log 'Not running as administrator: the server runs as SYSTEM, so it may not be possible to end it. Run this as administrator (-Elevate).'
}

# What is running from the installation: its own programs under data\ (LiveKit, PostgreSQL), the stub and the service
# wrapper, and any Java started from the folder (the server's jar in app\, the tray in launcher\). The uninstaller itself
# and this script are not on the list.
function Get-InstallProcesses {
    Get-CimInstance Win32_Process | Where-Object {
        ($_.ExecutablePath -and (
            $_.ExecutablePath.StartsWith("$Root\data\", [StringComparison]::OrdinalIgnoreCase) -or
            ($_.Name -in 'komm-server.exe', 'komm-server-service.exe' -and $_.ExecutablePath.StartsWith("$Root\", [StringComparison]::OrdinalIgnoreCase)))) -or
        ($_.CommandLine -and $_.Name -in 'java.exe', 'javaw.exe' -and $_.CommandLine.Replace('/', '\').IndexOf("$Root\", [StringComparison]::OrdinalIgnoreCase) -ge 0)
    }
}

# Which processes hold files of the folder open, asked of Windows itself (the Restart Manager, behind the "file in use"
# dialog). It does not depend on how a process was started or what its command line looks like, so it also finds a
# server whose path or command line is not what the rules above expect. The uninstaller is never on the list.
Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class KommLockFinder {
    [StructLayout(LayoutKind.Sequential)] struct RM_UNIQUE_PROCESS { public int dwProcessId; public System.Runtime.InteropServices.ComTypes.FILETIME ProcessStartTime; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)] struct RM_PROCESS_INFO {
        public RM_UNIQUE_PROCESS Process;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string strAppName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string strServiceShortName;
        public int ApplicationType; public uint AppStatus; public uint TSSessionId;
        [MarshalAs(UnmanagedType.Bool)] public bool bRestartable;
    }
    [DllImport("rstrtmgr.dll", CharSet = CharSet.Auto)] static extern int RmStartSession(out uint handle, int flags, string key);
    [DllImport("rstrtmgr.dll")] static extern int RmEndSession(uint handle);
    [DllImport("rstrtmgr.dll", CharSet = CharSet.Unicode)] static extern int RmRegisterResources(uint handle, uint nFiles, string[] files, uint nApps, RM_UNIQUE_PROCESS[] apps, uint nServices, string[] services);
    [DllImport("rstrtmgr.dll")] static extern int RmGetList(uint handle, out uint needed, ref uint count, [In, Out] RM_PROCESS_INFO[] info, ref uint reasons);
    public static int[] Holders(string[] files) {
        var pids = new List<int>();
        uint handle; if (RmStartSession(out handle, 0, Guid.NewGuid().ToString()) != 0) return pids.ToArray();
        try {
            if (RmRegisterResources(handle, (uint)files.Length, files, 0, null, 0, null) != 0) return pids.ToArray();
            uint needed = 0, count = 0, reasons = 0;
            int result = RmGetList(handle, out needed, ref count, null, ref reasons);
            if (result == 234 && needed > 0) {
                var info = new RM_PROCESS_INFO[needed]; count = needed;
                if (RmGetList(handle, out needed, ref count, info, ref reasons) == 0)
                    for (int i = 0; i < count; i++) pids.Add(info[i].Process.dwProcessId);
            }
        } finally { RmEndSession(handle); }
        return pids.ToArray();
    }
}
"@

function Get-LockingProcesses {
    if (-not (Test-Path -LiteralPath $Root)) { return @() }
    $files = @(Get-ChildItem -LiteralPath $Root -Recurse -Force -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -in '.exe', '.dll', '.jar' -or $_.Name -eq 'postmaster.pid' } | Select-Object -First 3000 -ExpandProperty FullName)
    if ($files.Count -eq 0) { return @() }
    $self = $PID
    [KommLockFinder]::Holders($files) | Sort-Object -Unique | Where-Object { $_ -ne $self -and $_ -ne 0 -and $_ -ne 4 } | ForEach-Object {
        Get-CimInstance Win32_Process -Filter "ProcessId = $_" -ErrorAction SilentlyContinue
    } | Where-Object { $_ -and $_.Name -notlike 'unins*' -and $_.Name -notin 'powershell.exe', 'explorer.exe', 'cmd.exe' }
}

# 1. Stop the service the normal way first, so the server can shut its database down properly when it does listen.
Log "Stopping the service $ServiceName ..."
& sc.exe stop $ServiceName 2>&1 | Out-Null
for ($i = 0; $i -lt 60; $i++) {
    $state = (& sc.exe query $ServiceName 2>&1 | Out-String)
    if ($state -match '1060' -or $state -match 'STOPPED') { break }   # 1060: there is no such service
    Start-Sleep -Milliseconds 500
}

# 2. Whatever is still running from the folder is what the stop left behind: end it, until nothing is left.
for ($round = 1; $round -le 15; $round++) {
    $running = @(Get-InstallProcesses) + @(Get-LockingProcesses) | Sort-Object ProcessId -Unique
    if ($running.Count -eq 0) { break }
    foreach ($process in $running) {
        Log ("Ending {0} (pid {1})" -f $process.Name, $process.ProcessId)
        Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
        if (Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue) { & taskkill.exe /F /T /PID $process.ProcessId 2>&1 | Out-Null }
    }
    Start-Sleep -Seconds 1
}
$left = @(@(Get-InstallProcesses) + @(Get-LockingProcesses) | Sort-Object ProcessId -Unique)
if ($left.Count -gt 0) {
    Log ('STILL RUNNING: ' + (($left | ForEach-Object { "$($_.Name) ($($_.ProcessId))" }) -join ', '))
} else {
    Log 'Nothing of the server is running any more.'
}

# 3. Remove the service registration, so nothing starts the server again.
& sc.exe delete $ServiceName 2>&1 | Out-Null

# 4. Optionally the folder itself.
if ($Delete) {
    for ($i = 0; $i -lt 10 -and (Test-Path $Root); $i++) {
        Remove-Item -LiteralPath $Root -Recurse -Force -ErrorAction SilentlyContinue
        if (Test-Path $Root) { Start-Sleep -Seconds 1 }
    }
    if (Test-Path $Root) {
        Log "Could not remove $Root completely. What is left:"
        Get-ChildItem -LiteralPath $Root -Force -Recurse -ErrorAction SilentlyContinue | Select-Object -First 8 -ExpandProperty FullName | ForEach-Object { Log "  $_" }
    } else {
        Log "Removed $Root"
        $parent = Split-Path $Root -Parent
        if ($parent -and (Test-Path $parent) -and -not (Get-ChildItem -LiteralPath $parent -Force -ErrorAction SilentlyContinue)) {
            Remove-Item -LiteralPath $parent -Force -ErrorAction SilentlyContinue
        }
    }
}

if ($Pause) {
    Write-Host ''
    Write-Host "Log: $log"
    Read-Host 'Press Enter to close'
}
exit $(if ($left.Count -gt 0) { 1 } else { 0 })
