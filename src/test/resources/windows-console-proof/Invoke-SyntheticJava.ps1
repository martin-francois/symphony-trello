$ErrorActionPreference = 'Stop'
if ($args.Count -gt 0 -and $args[0] -eq '-version') {
  [Console]::Error.WriteLine('openjdk version "25.0.1" 2026-04-21')
  exit 0
}
$main = [Array]::IndexOf($args, 'ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain')
if ($main -lt 0 -or $main + 1 -ge $args.Count) {
  throw 'cannot dispatch synthetic Java "arguments": expected the setup main class and command'
}
$command = $args[$main + 1]
$config = $env:SYMPHONY_TRELLO_CONFIG_DIR
$workflow = Join-Path $config 'WORKFLOW.native-console.md'
if ($command -eq '--version') {
  Write-Output 'symphony-trello test'
  exit 0
}
if ($command -eq 'setup-local') {
  if ($env:SYMPHONY_TRELLO_INSTALLER_COMPLETION -eq 'defer') {
    New-Item -ItemType Directory -Force -Path $config | Out-Null
    Set-Content -LiteralPath $workflow -Value '# Native console synthetic workflow'
    Set-Content -LiteralPath (Join-Path $config '.env') -Value 'TRELLO_API_KEY=synthetic-key'
    [ordered]@{ boards = @([ordered]@{
      boardId = 'synthetic-board'; boardName = 'Native console queue'
      workflowPath = $workflow; envPath = (Join-Path $config '.env')
    }) } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $config 'connected-boards.json')
    Add-Content -LiteralPath $env:SYMPHONY_CONSOLE_TEST_BOUNDARIES -Value 'setup-deferred'
    exit 0
  }
  if ($env:SYMPHONY_TRELLO_INSTALLER_COMPLETION -eq 'print') {
    Write-Output "You're good to go - your Trello board is now a queue for Codex work."
    Write-Output 'Connected board: Native console queue'
    Write-Output "Workflow: $workflow"
    Add-Content -LiteralPath $env:SYMPHONY_CONSOLE_TEST_BOUNDARIES -Value 'handoff-complete'
    exit 0
  }
}
throw 'cannot dispatch synthetic Java "command": expected installer setup'
