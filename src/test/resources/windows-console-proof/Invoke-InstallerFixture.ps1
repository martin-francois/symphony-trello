$ErrorActionPreference = 'Stop'
$bootstrap = [ordered]@{ stage = 'enter script' }
$bootstrapPath = $env:SYMPHONY_CONSOLE_PROOF_READY_FILE + '.bootstrap.json'
function Save-Bootstrap([string]$Stage) {
  $bootstrap.stage = $Stage
  [IO.File]::WriteAllText($bootstrapPath, ($bootstrap | ConvertTo-Json -Depth 4))
}
Save-Bootstrap 'enter script'
try {
  $env:SYMPHONY_CONSOLE_TEST_SHELL = (Get-Process -Id $PID).Path
  $bootstrap.userInteractive = [Environment]::UserInteractive
  $bootstrap.inputRedirected = [Console]::IsInputRedirected
  $bootstrap.outputRedirected = [Console]::IsOutputRedirected
  Save-Bootstrap 'check terminal'
  if (-not $bootstrap.userInteractive -or $bootstrap.inputRedirected -or $bootstrap.outputRedirected) {
    throw 'cannot run installer console proof "terminal": native interactive input and output are required'
  }

  # Functions shadow only this child session. No task is registered in the user's Task Scheduler.
  function New-ScheduledTaskAction {
    param([string]$Execute, [string]$Argument)
    return [pscustomobject]@{ Execute = $Execute; Argument = $Argument }
  }
  function New-ScheduledTaskTrigger {
    param([switch]$AtLogOn)
    return [pscustomobject]@{ AtLogOn = $AtLogOn.IsPresent }
  }
  function Register-ScheduledTask {
    param([string]$TaskName, [object]$Action, [object]$Trigger, [string]$Description, [switch]$Force)
    [ordered]@{ name = $TaskName; action = $Action; trigger = $Trigger; description = $Description } |
      ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $env:SYMPHONY_CONSOLE_TEST_TASK
    Add-Content -LiteralPath $env:SYMPHONY_CONSOLE_TEST_BOUNDARIES -Value 'autostart-registered'
  }
  function Start-ScheduledTask {
    param([string]$TaskName)
    if (-not (Test-Path -LiteralPath $env:SYMPHONY_CONSOLE_TEST_TASK)) {
      throw 'cannot start synthetic task "registration": register the test task first'
    }
    Add-Content -LiteralPath $env:SYMPHONY_CONSOLE_TEST_BOUNDARIES -Value 'autostart-started'
  }

  # The tutorial uses a real JVM so its shutdown hook, not a PowerShell input reader, owns cancellation.
  function java {
    $main = [Array]::IndexOf($args, 'ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain')
    if ($main -ge 0 -and $args.Count -eq $main + 3 -and
        $args[$main + 1] -eq 'tutorial' -and $args[$main + 2] -eq '--offer') {
      if (-not [Environment]::UserInteractive -or [Console]::IsInputRedirected -or [Console]::IsOutputRedirected) {
        throw 'cannot wait for tutorial cancellation "terminal": native interactive input and output are required'
      }
      if (-not ((Get-Content -LiteralPath $env:SYMPHONY_CONSOLE_TEST_BOUNDARIES) -contains 'handoff-complete')) {
        throw 'cannot offer synthetic tutorial "handoff": complete installation before the optional offer'
      }
      & $env:SYMPHONY_CONSOLE_TEST_JAVA -XX:ActiveProcessorCount=2 -Xmx64m `
        -cp $env:SYMPHONY_CONSOLE_TEST_CLASSPATH $env:SYMPHONY_CONSOLE_TEST_PROBE `
        $env:SYMPHONY_CONSOLE_PROOF_READY_FILE $env:SYMPHONY_CONSOLE_PROOF_READY_TOKEN `
        $env:SYMPHONY_CONSOLE_TEST_BOUNDARIES
      return
    }
    & $env:SYMPHONY_CONSOLE_TEST_SHELL -NoProfile -File $env:SYMPHONY_FAKE_JAVA @args
  }

  Save-Bootstrap 'start transcript'
  Start-Transcript -LiteralPath $env:SYMPHONY_CONSOLE_TEST_TRANSCRIPT | Out-Null
  try {
    Save-Bootstrap 'invoke installer'
    & $env:SYMPHONY_CONSOLE_TEST_INSTALLER -NoUpdatePath `
      -SymphonyHome $env:SYMPHONY_HOME -BinDir $env:SYMPHONY_CONSOLE_TEST_BIN `
      -Repo $env:SYMPHONY_TRELLO_REPO_URL -Ref main
    Save-Bootstrap 'installer returned'
  } finally {
    try {
      Save-Bootstrap 'stop transcript'
    } finally {
      Stop-Transcript | Out-Null
    }
    Save-Bootstrap 'transcript stopped'
  }
} catch {
  # Keep diagnostics to types and numeric codes; exception text includes test-owned paths.
  $bootstrap.failureType = $_.Exception.GetType().FullName
  $bootstrap.failureHResult = $_.Exception.HResult
  Save-Bootstrap $bootstrap.stage
  throw
}
