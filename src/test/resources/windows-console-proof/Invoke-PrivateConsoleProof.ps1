[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$CommandFile,
  [Parameter(Mandatory = $true)][string]$ReadyFile,
  [Parameter(Mandatory = $true)][string]$ResultFile,
  [ValidateRange(1, 300)][int]$ReadyTimeoutSeconds = 60,
  [ValidateRange(1, 60)][int]$ExitTimeoutSeconds = 15,
  [switch]$Controller,
  [string]$JobName,
  [string]$ReadyToken
)

$ErrorActionPreference = 'Stop'
function Get-ProofFailureStage([Exception]$Failure) {
  while ($null -ne $Failure) {
    if ($Failure.Data.Contains('ConsoleProofOperation')) {
      return [string]$Failure.Data['ConsoleProofOperation']
    }
    $Failure = $Failure.InnerException
  }
  return 'PowerShell wrapper'
}
function Get-ProofFailureFacts([Exception]$Failure) {
  $types = [Collections.Generic.List[string]]::new()
  $facts = [ordered]@{}
  while ($null -ne $Failure) {
    $types.Add($Failure.GetType().FullName)
    foreach ($key in @('TargetWaitResult', 'TargetWaitError', 'TargetExitCode', 'TargetExitReadError',
        'LaunchError', 'SessionId', 'InteractiveStationVisible', 'WindowStationError',
        'SignalSent', 'ControllerCtrlCCount', 'InterruptedTargetWaitResult', 'InterruptedTargetWaitError',
        'ReadyWaitResult', 'ReadyWaitError', 'ReadyExitCode', 'ReadyExitReadError')) {
      if ($Failure.Data.Contains($key)) { $facts[$key] = $Failure.Data[$key] }
    }
    $Failure = $Failure.InnerException
  }
  return [ordered]@{ exceptionTypes = $types.ToArray(); native = $facts }
}
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
  throw 'cannot run console proof "platform": native Windows is required'
}

# Run only the supplied test script. Never attach to an existing console.
Add-Type -Path (Join-Path $PSScriptRoot 'PrivateConsoleProof.cs')
$shell = (Get-Process -Id $PID).Path
$commandPath = [IO.Path]::GetFullPath($CommandFile)
$readyPath = [IO.Path]::GetFullPath($ReadyFile)
$resultPath = [IO.Path]::GetFullPath($ResultFile)

if ($Controller) {
  $result = [ordered]@{ status = 'incomplete'; signalSent = $null }
  try {
    $proof = [PrivateConsoleProof]::RunController(
      $shell, $commandPath, $readyPath, $JobName, $ReadyToken,
      $ReadyTimeoutSeconds, $ExitTimeoutSeconds)
    $result = [ordered]@{
      status = 'signal-delivered-and-target-exited'
      signalSent = $proof.SignalSent
      controllerPid = $PID
      targetPid = $proof.TargetPid
      readyProcessId = $proof.ReadyProcessId
      consolePids = $proof.ConsolePids
      targetExitCode = $proof.TargetExitCode
    }
  } catch {
    # Exception text is deliberately omitted; supplied commands can contain synthetic credentials.
    $result.failureType = $_.Exception.GetType().FullName
    $result.failureStage = Get-ProofFailureStage $_.Exception
    $result.failureFacts = Get-ProofFailureFacts $_.Exception
  } finally {
    $bootstrapPath = $readyPath + '.bootstrap.json'
    if ([IO.File]::Exists($bootstrapPath)) {
      try {
        $result.targetBootstrap = Get-Content -LiteralPath $bootstrapPath -Raw | ConvertFrom-Json
      } catch {
        # A timeout can race the target's diagnostic write; preserve the primary failure.
        $result.bootstrapReadFailure = [ordered]@{
          type = $_.Exception.GetType().FullName
          hResult = $_.Exception.HResult
        }
      }
    }
    [IO.File]::WriteAllText($resultPath, ($result | ConvertTo-Json -Depth 6))
  }
  if ($result.status -ne 'signal-delivered-and-target-exited') { exit 1 }
  exit 0
}

if (-not [IO.File]::Exists($commandPath) -or
    [IO.Path]::GetExtension($commandPath) -ne '.ps1') {
  throw 'cannot launch test command "CommandFile": supply an existing PowerShell script'
}
if ([IO.File]::Exists($readyPath) -or [IO.File]::Exists($resultPath) -or
    [IO.File]::Exists($resultPath + '.controller') -or [IO.File]::Exists($readyPath + '.bootstrap.json')) {
  throw 'cannot start console proof "evidence": use fresh ready and result paths'
}
if ($commandPath -eq $readyPath -or $commandPath -eq $resultPath -or $readyPath -eq $resultPath -or
    $commandPath -eq ($resultPath + '.controller') -or $readyPath -eq ($resultPath + '.controller')) {
  throw 'cannot start console proof "paths": command, ready and result paths must differ'
}

$result = [ordered]@{ status = 'incomplete'; cleanupConfirmed = $false }
[IO.File]::WriteAllText($resultPath, ($result | ConvertTo-Json))
try {
  $job = 'Local\SymphonyConsoleProof-' + [Guid]::NewGuid().ToString('N')
  $token = [Guid]::NewGuid().ToString('N')
  $controllerResult = $resultPath + '.controller'
  $arguments = @(
    '-NoProfile', '-File', $PSCommandPath, '-Controller',
    '-CommandFile', $commandPath, '-ReadyFile', $readyPath,
    '-ResultFile', $controllerResult, '-JobName', $job, '-ReadyToken', $token,
    '-ReadyTimeoutSeconds', [string]$ReadyTimeoutSeconds,
    '-ExitTimeoutSeconds', [string]$ExitTimeoutSeconds
  )
  $owner = [PrivateConsoleProof]::RunOwner(
    $shell, [string[]]$arguments, $job, $ReadyTimeoutSeconds + $ExitTimeoutSeconds + 45)
  $result.cleanupConfirmed = $owner.CleanupConfirmed
  $result.controllerExitCode = $owner.ControllerExitCode
  $result.sessionId = $owner.SessionId
  $result.interactiveStationVisible = $owner.InteractiveStationVisible
  if ([IO.File]::Exists($controllerResult)) {
    $result.proof = Get-Content -LiteralPath $controllerResult -Raw | ConvertFrom-Json
  }
  if ($owner.CleanupConfirmed -and $owner.ControllerExitCode -eq 0 -and
      $result.proof.status -eq 'signal-delivered-and-target-exited') {
    $result.status = 'complete'
  }
} catch {
  $result.failureType = $_.Exception.GetType().FullName
  $result.failureStage = Get-ProofFailureStage $_.Exception
  $result.failureFacts = Get-ProofFailureFacts $_.Exception
} finally {
  [IO.File]::WriteAllText($resultPath, ($result | ConvertTo-Json -Depth 8))
}
if ($result.status -ne 'complete') { exit 1 }
