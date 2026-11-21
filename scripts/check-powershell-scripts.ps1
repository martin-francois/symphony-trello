# PSScriptAnalyzer over the installers and process inspection helper, run by the windows-powershell job in
# .github/workflows/ci.yml. Rules the installers break on purpose are listed below.
$ErrorActionPreference = "Stop"
if (-not (Get-Module -ListAvailable -Name PSScriptAnalyzer)) {
  Install-Module PSScriptAnalyzer -Scope CurrentUser -Force -Repository PSGallery
}
$ignoredRules = @(
  "PSAvoidUsingInvokeExpression",
  "PSAvoidUsingWriteHost",
  "PSReviewUnusedParameter",
  "PSUseApprovedVerbs",
  "PSUseShouldProcessForStateChangingFunctions",
  "PSUseSingularNouns"
)
$results = @(
  foreach ($script in @("install.ps1", "uninstall.ps1", "src/main/resources/windows-process-arguments.ps1", "src/main/resources/windows-private-directories.ps1", "src/main/resources/windows-wait-for-exit.ps1", "src/test/resources/windows-file-lock-owners.ps1")) {
    Invoke-ScriptAnalyzer -Path $script -Severity Warning,Error
  }
)
$results = @($results | Where-Object { $ignoredRules -notcontains $_.RuleName })
if ($results.Count -gt 0) {
  $results | Format-Table -AutoSize | Out-String | Write-Host
  exit 1
}
