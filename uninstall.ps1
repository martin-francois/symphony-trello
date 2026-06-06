param(
  [Alias("-dry-run", "dry-run", "--dry-run")]
  [switch]$DryRun,
  [Alias("-yes", "--yes")]
  [switch]$Yes,
  [Alias("-yes-local-data", "yes-local-data", "--yes-local-data")]
  [switch]$YesLocalData,
  [Alias("-remove-config", "remove-config", "--remove-config")]
  [switch]$RemoveConfig,
  [Alias("-remove-workspaces", "remove-workspaces", "--remove-workspaces")]
  [switch]$RemoveWorkspaces,
  [Alias("RemoveLogs", "-remove-state", "remove-state", "-remove-logs", "remove-logs", "--remove-state", "--remove-logs")]
  [switch]$RemoveState,
  [Alias("-remove-all-local-data", "remove-all-local-data", "--remove-all-local-data")]
  [switch]$RemoveAllLocalData,
  [Alias("-symphony-home", "symphony-home", "--symphony-home")]
  [string]$SymphonyHome = $(if ($env:SYMPHONY_HOME) { $env:SYMPHONY_HOME } else { "$env:LOCALAPPDATA\SymphonyTrello" }),
  [Alias("-prefix", "--prefix")]
  [string]$Prefix = "",
  [Alias("-bin-dir", "bin-dir", "--bin-dir")]
  [string]$BinDir = $(Join-Path $(if ($env:USERPROFILE) { $env:USERPROFILE } else { $env:HOME }) (Join-Path ".local" "bin")),
  [switch]$Help,
  [Parameter(ValueFromRemainingArguments = $true)]
  [string[]]$RemainingArgs = @()
)

$ErrorActionPreference = "Stop"
$ScriptBoundParameters = @{} + $PSBoundParameters
$DefaultSymphonyHome = if ($env:SYMPHONY_HOME) { $env:SYMPHONY_HOME } else { "$env:LOCALAPPDATA\SymphonyTrello" }
$DefaultPrefix = ""
$DefaultBinDir = Join-Path $(if ($env:USERPROFILE) { $env:USERPROFILE } else { $env:HOME }) (Join-Path ".local" "bin")

function Apply-PositionalFlag([string]$Token) {
  switch ($Token) {
    { $_ -in @("-dry-run", "--dry-run") } {
      Set-Variable -Name DryRun -Value $true -Scope 1
      return $true
    }
    { $_ -in @("-yes", "--yes") } {
      Set-Variable -Name Yes -Value $true -Scope 1
      return $true
    }
    { $_ -in @("-yes-local-data", "--yes-local-data") } {
      Set-Variable -Name YesLocalData -Value $true -Scope 1
      return $true
    }
    { $_ -in @("-remove-config", "--remove-config") } {
      Set-Variable -Name RemoveConfig -Value $true -Scope 1
      return $true
    }
    { $_ -in @("-remove-workspaces", "--remove-workspaces") } {
      Set-Variable -Name RemoveWorkspaces -Value $true -Scope 1
      return $true
    }
    { $_ -in @("-remove-state", "--remove-state", "-remove-logs", "--remove-logs") } {
      Set-Variable -Name RemoveState -Value $true -Scope 1
      return $true
    }
    { $_ -in @("-remove-all-local-data", "--remove-all-local-data") } {
      Set-Variable -Name RemoveAllLocalData -Value $true -Scope 1
      return $true
    }
    { $_ -in @("-help", "--help", "-h") } {
      Set-Variable -Name Help -Value $true -Scope 1
      return $true
    }
    default {
      return $false
    }
  }
}

function Assert-ExplicitPathOptionValue([string]$ParameterName, [string]$OptionName, [string]$Value) {
  if ($ScriptBoundParameters.ContainsKey($ParameterName) -and [string]::IsNullOrWhiteSpace($Value)) {
    throw "$OptionName must not be blank."
  }
}

function Assert-BinDirOptionValue {
  if ([string]::IsNullOrWhiteSpace($BinDir)) {
    throw "--bin-dir must not be blank."
  }
  if ([regex]::IsMatch($BinDir, "\p{Cc}")) {
    throw "--bin-dir must not contain control characters."
  }
  if (-not (Test-FullyQualifiedPath $BinDir)) {
    throw "--bin-dir must be an absolute path."
  }
}

function Test-HasControlCharacter([string]$Value) {
  return -not [string]::IsNullOrEmpty($Value) -and [regex]::IsMatch($Value, "\p{Cc}")
}

function Assert-AppPathOptionValues {
  if ((Test-HasControlCharacter $SymphonyHome) -or
      (Test-HasControlCharacter $Prefix) -or
      (Test-HasControlCharacter $env:SYMPHONY_TRELLO_CONFIG_DIR) -or
      (Test-HasControlCharacter $env:SYMPHONY_TRELLO_WORKSPACE_ROOT) -or
      (Test-HasControlCharacter $env:SYMPHONY_TRELLO_STATE_HOME)) {
    throw "Installer app, config, workspace, and state paths must not contain control characters."
  }
  if (-not [string]::IsNullOrEmpty($Prefix) -and -not (Test-FullyQualifiedPath $Prefix)) {
    throw "--prefix must be an absolute path."
  }
}

function Test-FullyQualifiedPath([string]$Path) {
  if (-not [System.IO.Path]::IsPathRooted($Path)) {
    return $false
  }
  $root = [System.IO.Path]::GetPathRoot($Path)
  if ([string]::IsNullOrEmpty($root)) {
    return $false
  }
  $isWindowsPlatform = [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
    [System.Runtime.InteropServices.OSPlatform]::Windows)
  if (-not $isWindowsPlatform) {
    return $true
  }
  return [regex]::IsMatch($root, "^[A-Za-z]:[\\/]\z") -or [regex]::IsMatch($root, "^\\\\[^\\]+\\[^\\]+\\?$")
}

function Test-BroadSystemPath([string]$Path) {
  if ([string]::IsNullOrEmpty($Path)) {
    return $true
  }
  $trimmedPath = [System.IO.Path]::GetFullPath($Path).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
  $isWindowsPlatform = [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
    [System.Runtime.InteropServices.OSPlatform]::Windows)
  if (-not $isWindowsPlatform) {
    return $trimmedPath -in @("", "/", "/etc", "/home", "/opt", "/root", "/tmp", "/usr", "/var")
  }
  $root = [System.IO.Path]::GetPathRoot($trimmedPath).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
  return $trimmedPath.Equals($root, [System.StringComparison]::OrdinalIgnoreCase)
}

function Test-PublicOptionToken([string]$Token) {
  return $Token -in @(
    "-dry-run",
    "--dry-run",
    "-yes",
    "--yes",
    "-yes-local-data",
    "--yes-local-data",
    "-remove-config",
    "--remove-config",
    "-remove-workspaces",
    "--remove-workspaces",
    "-remove-state",
    "--remove-state",
    "-remove-logs",
    "--remove-logs",
    "-remove-all-local-data",
    "--remove-all-local-data",
    "-prefix",
    "--prefix",
    "-bin-dir",
    "--bin-dir",
    "-symphony-home",
    "--symphony-home",
    "-help",
    "--help",
    "-h"
  )
}

function Read-PublicPathOptionValue([string[]]$Tokens, [int]$Index, [string]$OptionName) {
  if ($Index -ge $Tokens.Count) {
    throw "Missing value for $OptionName"
  }
  $value = $Tokens[$Index]
  if ([string]::IsNullOrWhiteSpace($value)) {
    throw "$OptionName must not be blank."
  }
  if (Test-PublicOptionToken $value) {
    throw "Missing value for $OptionName"
  }
  return $value
}

function Apply-PublicArgs([string[]]$Tokens) {
  for ($i = 0; $i -lt $Tokens.Count; $i++) {
    $token = $Tokens[$i]
    switch ($token) {
      { $_ -in @("-dry-run", "--dry-run") } {
        Set-Variable -Name DryRun -Value $true -Scope 1
        break
      }
      { $_ -in @("-yes", "--yes") } {
        Set-Variable -Name Yes -Value $true -Scope 1
        break
      }
      { $_ -in @("-yes-local-data", "--yes-local-data") } {
        Set-Variable -Name YesLocalData -Value $true -Scope 1
        break
      }
      { $_ -in @("-remove-config", "--remove-config") } {
        Set-Variable -Name RemoveConfig -Value $true -Scope 1
        break
      }
      { $_ -in @("-remove-workspaces", "--remove-workspaces") } {
        Set-Variable -Name RemoveWorkspaces -Value $true -Scope 1
        break
      }
      { $_ -in @("-remove-state", "--remove-state", "-remove-logs", "--remove-logs") } {
        Set-Variable -Name RemoveState -Value $true -Scope 1
        break
      }
      { $_ -in @("-remove-all-local-data", "--remove-all-local-data") } {
        Set-Variable -Name RemoveAllLocalData -Value $true -Scope 1
        break
      }
      { $_ -in @("-help", "--help", "-h") } {
        Set-Variable -Name Help -Value $true -Scope 1
        break
      }
      { $_ -in @("-prefix", "--prefix") } {
        $i++
        Set-Variable -Name Prefix -Value (Read-PublicPathOptionValue $Tokens $i $token) -Scope 1
        break
      }
      { $_ -in @("-bin-dir", "--bin-dir") } {
        $i++
        Set-Variable -Name BinDir -Value (Read-PublicPathOptionValue $Tokens $i $token) -Scope 1
        break
      }
      { $_ -in @("-symphony-home", "--symphony-home") } {
        $i++
        Set-Variable -Name SymphonyHome -Value (Read-PublicPathOptionValue $Tokens $i $token) -Scope 1
        break
      }
      default {
        if (-not (Test-ImplicitDefaultToken $token)) {
          throw "Unknown option: $token"
        }
      }
    }
  }
}

Assert-ExplicitPathOptionValue "SymphonyHome" "--symphony-home" $SymphonyHome
Assert-ExplicitPathOptionValue "Prefix" "--prefix" $Prefix
Assert-ExplicitPathOptionValue "BinDir" "--bin-dir" $BinDir

function Test-ImplicitDefaultToken([string]$Token) {
  return $Token -eq $DefaultSymphonyHome -or
    $Token -eq $DefaultPrefix -or
    $Token -eq $DefaultBinDir
}

$positionalTokens = @($SymphonyHome, $Prefix, $BinDir) + $RemainingArgs |
  Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
if (($positionalTokens | Where-Object { $_.StartsWith("-") } | Select-Object -First 1)) {
  $SymphonyHome = $DefaultSymphonyHome
  $Prefix = $DefaultPrefix
  $BinDir = $DefaultBinDir
  Apply-PublicArgs $positionalTokens
} else {
  if (Apply-PositionalFlag $SymphonyHome) {
    $SymphonyHome = $DefaultSymphonyHome
  }
  if (Apply-PositionalFlag $Prefix) {
    $Prefix = $DefaultPrefix
  }
  if (Apply-PositionalFlag $BinDir) {
    $BinDir = $DefaultBinDir
  }
  foreach ($token in $RemainingArgs) {
    if (-not (Apply-PositionalFlag $token)) {
      throw "Unknown option: $token"
    }
  }
}

if ($Help) {
  @"
Usage:
  powershell -c "irm https://raw.githubusercontent.com/martin-francois/symphony-trello/main/uninstall.ps1 | iex"

Options:
  --dry-run    Print planned actions without deleting files.
  --yes        Do not prompt for installer-managed files.
  --yes-local-data     Do not prompt for cleanup scopes that delete local user data.
  -RemoveConfig        Also remove local .env, workflows, and connected-board manifest.
  -RemoveWorkspaces    Also remove per-card workspaces.
  -RemoveState         Also remove local state and logs.
  -RemoveAllLocalData  Remove config, workspaces, state, and logs.
"@
  exit 0
}

Assert-BinDirOptionValue
Assert-AppPathOptionValues
$SymphonyHome = [System.IO.Path]::GetFullPath($SymphonyHome)
if (-not $Prefix) {
  $Prefix = Join-Path $SymphonyHome "app"
}
$ConfigDir = if ($env:SYMPHONY_TRELLO_CONFIG_DIR) { $env:SYMPHONY_TRELLO_CONFIG_DIR } else { Join-Path $SymphonyHome "config" }
$WorkspaceRoot = if ($env:SYMPHONY_TRELLO_WORKSPACE_ROOT) { $env:SYMPHONY_TRELLO_WORKSPACE_ROOT } else { Join-Path $SymphonyHome "workspaces" }
$StateHome = if ($env:SYMPHONY_TRELLO_STATE_HOME) { $env:SYMPHONY_TRELLO_STATE_HOME } else { Join-Path $SymphonyHome "state" }
if ($RemoveAllLocalData) {
  $RemoveConfig = $true
  $RemoveWorkspaces = $true
  $RemoveState = $true
}
$RawPrefix = $Prefix
$Prefix = [System.IO.Path]::GetFullPath($Prefix)
$ConfigDir = [System.IO.Path]::GetFullPath($ConfigDir)
$WorkspaceRoot = [System.IO.Path]::GetFullPath($WorkspaceRoot)
$RawBinDir = $BinDir
$BinDir = [System.IO.Path]::GetFullPath($BinDir)
$StateHome = [System.IO.Path]::GetFullPath($StateHome)
$CodexHome = [System.IO.Path]::GetFullPath((Join-Path $SymphonyHome "codex"))
$CodexNpmPrefix = [System.IO.Path]::GetFullPath((Join-Path $SymphonyHome "npm"))

function Confirm-Step([string]$Prompt, [bool]$AssumeYes = $false) {
  if ($DryRun) {
    return $true
  }
  if ($AssumeYes) {
    return $true
  }
  $answer = Read-Host "$Prompt [y/N]"
  return $answer -match '^[Yy]'
}

function Test-SameOrInsidePath([string]$Child, [string]$Parent) {
  $childPath = [System.IO.Path]::GetFullPath($Child).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
  $parentPath = [System.IO.Path]::GetFullPath($Parent).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
  return [string]::Equals($childPath, $parentPath, [System.StringComparison]::OrdinalIgnoreCase) -or $childPath.StartsWith($parentPath + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase) -or $childPath.StartsWith($parentPath + [System.IO.Path]::AltDirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)
}

function Test-PathHasSymlinkComponent([string]$Path) {
  $root = [System.IO.Path]::GetPathRoot($Path)
  $relativePath = $Path.Substring($root.Length)
  $current = $root
  foreach ($part in $relativePath.Split([char[]]@([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar), [System.StringSplitOptions]::RemoveEmptyEntries)) {
    if ($part -eq ".") {
      continue
    }
    if ($part -eq "..") {
      $parent = Split-Path -Parent $current
      if (-not [string]::IsNullOrEmpty($parent)) {
        $current = $parent
      }
      continue
    }
    $current = Join-Path $current $part
    if (Test-Path -LiteralPath $current) {
      $item = Get-Item -LiteralPath $current -Force
      if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        return $true
      }
    } else {
      return $false
    }
  }
  return $false
}

function Get-ScriptCheckoutDirectory {
  if ([string]::IsNullOrEmpty($PSScriptRoot)) {
    return $null
  }
  $scriptDirectory = [System.IO.Path]::GetFullPath($PSScriptRoot)
  if ((Test-Path -LiteralPath (Join-Path $scriptDirectory ".git")) -or
      (Test-Path -LiteralPath (Join-Path $scriptDirectory "pom.xml"))) {
    return $scriptDirectory
  }
  return $null
}

function Assert-CommandDirectory {
  $normalizedBinDir = [System.IO.Path]::GetFullPath($BinDir)
  $pathRoot = [System.IO.Path]::GetPathRoot($normalizedBinDir).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
  $trimmedBinDir = $normalizedBinDir.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
  $homeValue = if ($env:USERPROFILE) { $env:USERPROFILE } else { $env:HOME }
  $homeDirectory = [System.IO.Path]::GetFullPath($homeValue)
  $checkoutDirectory = Get-ScriptCheckoutDirectory
  if ($trimmedBinDir.Equals($pathRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
      $trimmedBinDir.Equals($homeDirectory.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar), [System.StringComparison]::OrdinalIgnoreCase) -or
      (-not [string]::IsNullOrEmpty($checkoutDirectory) -and
          $trimmedBinDir.Equals($checkoutDirectory.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar), [System.StringComparison]::OrdinalIgnoreCase))) {
    throw "--bin-dir must point to a dedicated command directory."
  }
  if (Test-Path -LiteralPath $normalizedBinDir) {
    $binDirItem = Get-Item -LiteralPath $normalizedBinDir -Force
    if (-not $binDirItem.PSIsContainer) {
      throw "--bin-dir must be a directory."
    }
  }
  foreach ($root in @($Prefix, $ConfigDir, $WorkspaceRoot, $StateHome)) {
    if ((Test-SameOrInsidePath $normalizedBinDir $root) -or (Test-SameOrInsidePath $root $normalizedBinDir)) {
      throw "--bin-dir must not overlap Symphony app, config, workspace, or state directories."
    }
  }
}

function Assert-AppPaths {
  $normalizedSymphonyHome = [System.IO.Path]::GetFullPath($SymphonyHome)
  $normalizedPrefix = [System.IO.Path]::GetFullPath($Prefix)
  $trimmedPrefix = $normalizedPrefix.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
  if ((Test-BroadSystemPath $trimmedPrefix) -or (Test-BroadSystemPath $normalizedSymphonyHome)) {
    throw "--prefix must point to a dedicated app checkout directory."
  }
  $homeValue = if ($env:USERPROFILE) { $env:USERPROFILE } else { $env:HOME }
  $homeDirectory = [System.IO.Path]::GetFullPath($homeValue).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
  $trimmedSymphonyHome = $normalizedSymphonyHome.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
  $checkoutDirectory = Get-ScriptCheckoutDirectory
  if ($trimmedPrefix.Equals($homeDirectory, [System.StringComparison]::OrdinalIgnoreCase) -or
      $trimmedSymphonyHome.Equals($homeDirectory, [System.StringComparison]::OrdinalIgnoreCase) -or
      (-not [string]::IsNullOrEmpty($checkoutDirectory) -and
          $trimmedPrefix.Equals($checkoutDirectory.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar), [System.StringComparison]::OrdinalIgnoreCase))) {
    throw "--prefix must point to a dedicated app checkout directory."
  }
  if ((Test-PathHasSymlinkComponent $RawPrefix) -or (Test-PathHasSymlinkComponent $normalizedPrefix)) {
    throw "--prefix must not be a symlink."
  }
  if (Test-Path -LiteralPath $normalizedPrefix) {
    $prefixItem = Get-Item -LiteralPath $normalizedPrefix -Force
    if (-not $prefixItem.PSIsContainer) {
      throw "--prefix must be a directory."
    }
  }
  foreach ($root in @($ConfigDir, $WorkspaceRoot, $StateHome)) {
    if ((-not (Test-Path -LiteralPath $root)) -and
        ((Test-SameOrInsidePath $normalizedPrefix $root) -or (Test-SameOrInsidePath $root $normalizedPrefix))) {
      throw "--prefix must not overlap Symphony config, workspace, or state directories."
    }
  }
}

function Assert-AppRemovalPreservesCurrentData {
  $conflicts = @()
  $cleanupPaths = @()
  if ((-not $RemoveConfig) -and (Test-Path -LiteralPath $ConfigDir) -and (Test-SameOrInsidePath $ConfigDir $Prefix)) {
    $conflicts += "CONFIG     $ConfigDir"
  } elseif ((-not $YesLocalData) -and (Test-Path -LiteralPath $ConfigDir) -and (Test-SameOrInsidePath $ConfigDir $Prefix)) {
    $cleanupPaths += "CONFIG     $ConfigDir"
  }
  if ((-not $RemoveWorkspaces) -and (Test-Path -LiteralPath $WorkspaceRoot) -and (Test-SameOrInsidePath $WorkspaceRoot $Prefix)) {
    $conflicts += "WORKSPACES $WorkspaceRoot"
  } elseif ((-not $YesLocalData) -and (Test-Path -LiteralPath $WorkspaceRoot) -and (Test-SameOrInsidePath $WorkspaceRoot $Prefix)) {
    $cleanupPaths += "WORKSPACES $WorkspaceRoot"
  }
  if ((-not $RemoveState) -and (Test-Path -LiteralPath $StateHome) -and (Test-SameOrInsidePath $StateHome $Prefix)) {
    $conflicts += "STATE/LOGS $StateHome"
  } elseif ((-not $YesLocalData) -and (Test-Path -LiteralPath $StateHome) -and (Test-SameOrInsidePath $StateHome $Prefix)) {
    $cleanupPaths += "STATE/LOGS $StateHome"
  }
  if ($conflicts.Count -gt 0) {
    Write-Error "Refusing to remove app directory because it contains current local data that is preserved by default:`n  $($conflicts -join "`n  ")`nUse a dedicated app checkout path, or pass the matching cleanup scope with --yes-local-data."
    exit 2
  }
  if ($cleanupPaths.Count -gt 0) {
    Write-Host "The app directory contains local data selected for cleanup:"
    Write-Host "  $($cleanupPaths -join "`n  ")"
    if (-not (Confirm-Step "Remove those local data paths with the app directory?" $YesLocalData)) {
      Write-Host "Skipped cleanup-scoped local data inside the app directory."
      exit 2
    }
  }
}

function Remove-ManagedPath([string]$Path) {
  $safePath = Assert-SafeRemovalPath $Path
  if (Test-Path -LiteralPath $safePath) {
    if ($DryRun) {
      Write-Host "  WOULD REMOVE  $safePath"
    } else {
      Write-Host "  REMOVE  $safePath"
    }
    if (-not $DryRun) {
      Remove-Item -Recurse -Force -LiteralPath $safePath
    }
  }
}

function Test-SameFileContent([string]$First, [string]$Second) {
  if ((-not (Test-Path $First)) -or (-not (Test-Path $Second))) {
    return $false
  }
  return (Get-FileHash -Algorithm SHA256 $First).Hash -eq (Get-FileHash -Algorithm SHA256 $Second).Hash
}

function Test-ManagedCodexWrapper([string]$Path) {
  if (-not (Test-Path $Path)) {
    return $false
  }
  $content = Get-Content $Path -Raw -ErrorAction SilentlyContinue
  if ($null -eq $content) {
    return $false
  }
  return $content.Contains($CodexNpmPrefix)
}

function Remove-ManagedCodexArtifacts {
  $codexExe = Join-Path $BinDir "codex.exe"
  $managedCodexExe = Join-Path $CodexHome "bin\codex.exe"
  if (Test-SameFileContent $codexExe $managedCodexExe) {
    Remove-ManagedPath $codexExe
  }
  foreach ($wrapper in @("codex.cmd", "codex.ps1")) {
    $path = Join-Path $BinDir $wrapper
    if (Test-ManagedCodexWrapper $path) {
      Remove-ManagedPath $path
    }
  }
  Remove-ManagedPath $CodexHome
  Remove-ManagedPath $CodexNpmPrefix
}

function Write-ManagedCodexRemovalPlan {
  $printed = $false
  $codexExe = Join-Path $BinDir "codex.exe"
  $managedCodexExe = Join-Path $CodexHome "bin\codex.exe"
  if (Test-SameFileContent $codexExe $managedCodexExe) {
    Write-Host "  CODEX CLI       $codexExe"
    $printed = $true
  }
  foreach ($wrapper in @("codex.cmd", "codex.ps1")) {
    $path = Join-Path $BinDir $wrapper
    if (Test-ManagedCodexWrapper $path) {
      if ($printed) {
        Write-Host "                  $path"
      } else {
        Write-Host "  CODEX CLI       $path"
        $printed = $true
      }
    }
  }
  foreach ($path in @($CodexHome, $CodexNpmPrefix)) {
    if (Test-Path -LiteralPath $path) {
      if ($printed) {
        Write-Host "                  $path"
      } else {
        Write-Host "  CODEX CLI       $path"
        $printed = $true
      }
    }
  }
}

function Assert-SafeRemovalPath([string]$Path) {
  if ([string]::IsNullOrWhiteSpace($Path)) {
    throw "Refusing dangerous removal path: $Path"
  }
  $fullPath = [System.IO.Path]::GetFullPath($Path)
  $root = [System.IO.Path]::GetPathRoot($fullPath)
  $trimmedRoot = $root.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
  $trimmed = $fullPath.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
  if ($trimmed -eq $trimmedRoot) {
    $trimmed = $root
  }
  $homePath = [System.IO.Path]::GetFullPath($HOME).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
  $currentPath = [System.IO.Path]::GetFullPath((Get-Location).Path).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
  if ([string]::Equals($trimmed, $root, [System.StringComparison]::OrdinalIgnoreCase) -or [string]::Equals($trimmed, $homePath, [System.StringComparison]::OrdinalIgnoreCase) -or [string]::Equals($trimmed, $currentPath, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing dangerous removal path: $Path"
  }
  return $trimmed
}

function Assert-RequestedRemovalPaths {
  if ($RemoveConfig) {
    Assert-SafeRemovalPath $ConfigDir | Out-Null
  }
  if ($RemoveWorkspaces) {
    Assert-SafeRemovalPath $WorkspaceRoot | Out-Null
  }
  if ($RemoveState) {
    Assert-SafeRemovalPath $StateHome | Out-Null
  }
}

Assert-AppPaths
Assert-RequestedRemovalPaths
Assert-CommandDirectory

function Test-CommandLineArgument([string]$CommandLine, [string]$Argument) {
  $escaped = [regex]::Escape($Argument)
  return $CommandLine -match "(^|\s)$escaped(\s|$)" -or $CommandLine -match "(^|\s)`"$escaped`"(\s|$)"
}

function Test-ManagedPid([int]$ManagedPid) {
  $marker = "-Dsymphony.trello.managed.app_home=$Prefix"
  $jar = Join-Path $Prefix "target\quarkus-app\quarkus-run.jar"
  $process = Get-CimInstance Win32_Process -Filter "ProcessId = $ManagedPid" -ErrorAction SilentlyContinue
  return $process -and $process.CommandLine -and (Test-CommandLineArgument $process.CommandLine $marker) -and (Test-CommandLineArgument $process.CommandLine $jar)
}

function Stop-AndWaitManagedProcess([int]$ManagedPid) {
  Stop-Process -Id $ManagedPid -ErrorAction SilentlyContinue
  try {
    Wait-Process -Id $ManagedPid -Timeout 15 -ErrorAction Stop
  } catch {
    if (-not (Get-Process -Id $ManagedPid -ErrorAction SilentlyContinue)) {
      return
    }
    Write-Host "  KILL  pid=$ManagedPid did not stop after SIGTERM"
    Stop-Process -Id $ManagedPid -Force -ErrorAction SilentlyContinue
    try {
      Wait-Process -Id $ManagedPid -Timeout 5 -ErrorAction Stop
    } catch {
      if (Get-Process -Id $ManagedPid -ErrorAction SilentlyContinue) {
        throw
      }
    }
  }
}

function Stop-ManagedProcesses {
  $pidFiles = @(Get-ChildItem -Path $StateHome -Filter "*.pid" -ErrorAction SilentlyContinue)
  foreach ($pidFile in $pidFiles) {
    $pidText = Get-Content -LiteralPath $pidFile.FullName -Raw -ErrorAction SilentlyContinue
    $managedPid = 0
    if (($null -eq $pidText) -or (-not [int]::TryParse($pidText.Trim(), [ref]$managedPid))) {
      Write-Host "  SKIP  invalid stale pid: $($pidFile.BaseName)"
      if (-not $DryRun) {
        Remove-Item -LiteralPath $pidFile.FullName -Force
      }
      continue
    }
    $process = Get-Process -Id $managedPid -ErrorAction SilentlyContinue
    if ($process -and (Test-ManagedPid $managedPid)) {
      Write-Host "  STOP  $($pidFile.BaseName) pid=$managedPid"
      if (-not $DryRun) {
        Stop-AndWaitManagedProcess $managedPid
        Remove-Item -LiteralPath $pidFile.FullName -Force
      }
    } elseif ($process) {
      Write-Host "  SKIP  stale pid does not belong to this install: $($pidFile.BaseName) pid=$managedPid"
    } elseif (-not $DryRun) {
      Remove-Item -LiteralPath $pidFile.FullName -Force
    }
  }
}

Write-Host "Symphony for Trello uninstall"
Write-Host
Write-Host "App checkout: $Prefix"
Write-Host "Installed CLI: $(Join-Path $BinDir "symphony-trello.ps1")"
Write-Host
if ($DryRun) {
  Write-Host "Dry run: no files changed."
  Write-Host
}
Write-Host "Will remove if present:"
Write-Host "  APP FILES       $Prefix"
Write-Host "  CLI EXECUTABLE  $(Join-Path $BinDir "symphony-trello.ps1")"
Write-ManagedCodexRemovalPlan
Write-Host
Write-Host "Will preserve by default:"
Write-Host "  CONFIG          $ConfigDir"
Write-Host "  WORKSPACES      $WorkspaceRoot"
Write-Host "  STATE/LOGS      $StateHome"
Write-Host "  AUTH            Codex login/auth files and GitHub auth"
Write-Host "  TRELLO          Trello boards are not deleted or archived"
Write-Host
if (Confirm-Step "Remove installer-managed app files and CLI executable?" $Yes) {
  if ((Test-Path $Prefix) -and (-not (Test-Path "$Prefix\.symphony-trello-install"))) {
    throw "Refusing to remove app directory without Symphony installer marker: $Prefix"
  }
  Assert-AppRemovalPreservesCurrentData
  Stop-ManagedProcesses
  Remove-ManagedPath "$BinDir\symphony-trello.ps1"
  Remove-ManagedPath "$BinDir\symphony-trello.cmd"
  Remove-ManagedCodexArtifacts
  Remove-ManagedPath $Prefix
} else {
  Write-Host "Skipped installer-managed files."
}

if ($RemoveConfig -and (Test-Path $ConfigDir)) {
  Write-Host
  if (Confirm-Step "Remove local config, .env files, workflows, and connected-board manifest?" $YesLocalData) {
    Remove-ManagedPath $ConfigDir
  } else {
    Write-Host "Kept local config: $ConfigDir"
  }
}

if ($RemoveWorkspaces -and (Test-Path $WorkspaceRoot)) {
  Write-Host
  if (Confirm-Step "Remove per-card workspaces?" $YesLocalData) {
    Remove-ManagedPath $WorkspaceRoot
  } else {
    Write-Host "Kept workspaces: $WorkspaceRoot"
  }
}

if ($RemoveState -and (Test-Path $StateHome)) {
  Write-Host
  if (Confirm-Step "Remove local state and logs?" $YesLocalData) {
    Remove-ManagedPath $StateHome
  } else {
    Write-Host "Kept local state and logs: $StateHome"
  }
}

Write-Host
Write-Host "Trello boards were not deleted or archived."
