param(
  [Alias("-dry-run", "dry-run", "--dry-run")]
  [switch]$DryRun,
  [Alias("-no-onboard", "no-onboard", "--no-onboard")]
  [switch]$NoOnboard,
  [Alias("-no-update-path", "no-update-path", "--no-update-path")]
  [switch]$NoUpdatePath,
  [Alias("-symphony-home", "symphony-home", "--symphony-home")]
  [string]$SymphonyHome = $(if ($env:SYMPHONY_HOME) { $env:SYMPHONY_HOME } else { "$env:LOCALAPPDATA\SymphonyTrello" }),
  [Alias("-prefix", "--prefix")]
  [string]$Prefix = "",
  [Alias("-bin-dir", "bin-dir", "--bin-dir")]
  [string]$BinDir = $(Join-Path $(if ($env:USERPROFILE) { $env:USERPROFILE } else { $env:HOME }) (Join-Path ".local" "bin")),
  [Alias("-version", "--version")]
  [string]$Version = $(if ($env:SYMPHONY_TRELLO_VERSION) { $env:SYMPHONY_TRELLO_VERSION } else { "1.0.2" }), # x-release-please-version
  [Alias("-from-source", "from-source", "--from-source")]
  [switch]$FromSource,
  [Alias("-repo", "--repo")]
  [string]$Repo = $(if ($env:SYMPHONY_TRELLO_REPO_URL) { $env:SYMPHONY_TRELLO_REPO_URL } else { "https://github.com/martin-francois/symphony-trello.git" }),
  [Alias("-ref", "--ref")]
  [string]$Ref = $(if ($env:SYMPHONY_TRELLO_REF) { $env:SYMPHONY_TRELLO_REF } else { "v1.0.2" }), # x-release-please-version
  [switch]$Help,
  [Parameter(ValueFromRemainingArguments = $true)]
  [string[]]$RemainingArgs = @()
)

$ErrorActionPreference = "Stop"
trap {
  $message = if ($_.Exception -and -not [string]::IsNullOrWhiteSpace($_.Exception.Message)) {
    $_.Exception.Message
  } else {
    $_.ToString()
  }
  [Console]::Error.WriteLine($message)
  exit 1
}
$ScriptBoundParameters = @{} + $PSBoundParameters
$OriginalPath = $env:PATH
$DefaultSymphonyHome = if ($env:SYMPHONY_HOME) { $env:SYMPHONY_HOME } else { "$env:LOCALAPPDATA\SymphonyTrello" }
$DefaultPrefix = ""
$DefaultBinDir = Join-Path $(if ($env:USERPROFILE) { $env:USERPROFILE } else { $env:HOME }) (Join-Path ".local" "bin")
$DefaultVersion = "1.0.2" # x-release-please-version
$DefaultRepo = "https://github.com/martin-francois/symphony-trello.git"
$DefaultRef = "v$DefaultVersion"
$ScheduledTaskName = "Symphony for Trello"
$WindowsUserProfile = if ($env:USERPROFILE) { $env:USERPROFILE } else { $env:HOME }
$WindowsProfileRoot = if ($env:APPDATA) { $env:APPDATA } else { Join-Path $WindowsUserProfile "AppData\Roaming" }
$StartupFolder = Join-Path $WindowsProfileRoot "Microsoft\Windows\Start Menu\Programs\Startup"
$StartupCommandPath = Join-Path $StartupFolder "Symphony for Trello.cmd"
$ReleaseBaseUrl = if ($env:SYMPHONY_TRELLO_RELEASE_BASE_URL) {
  $env:SYMPHONY_TRELLO_RELEASE_BASE_URL
} else {
  "https://github.com/martin-francois/symphony-trello/releases/download/v$Version"
}
if ($env:SYMPHONY_TRELLO_INSTALL_SOURCE) {
  $InstallSource = $env:SYMPHONY_TRELLO_INSTALL_SOURCE
} elseif ($env:SYMPHONY_TRELLO_REPO_URL -or $env:SYMPHONY_TRELLO_REF) {
  $InstallSource = "source-checkout"
} else {
  $InstallSource = "release-archive"
}
if ($FromSource -or $ScriptBoundParameters.ContainsKey("Repo") -or $ScriptBoundParameters.ContainsKey("Ref")) {
  $InstallSource = "source-checkout"
}

function Apply-PositionalFlag([string]$Token) {
  switch ($Token) {
    { $_ -in @("-dry-run", "--dry-run") } {
      Set-Variable -Name DryRun -Value $true -Scope 1
      return $true
    }
    { $_ -in @("-no-onboard", "--no-onboard") } {
      Set-Variable -Name NoOnboard -Value $true -Scope 1
      return $true
    }
    { $_ -in @("-no-update-path", "--no-update-path") } {
      Set-Variable -Name NoUpdatePath -Value $true -Scope 1
      return $true
    }
    { $_ -in @("-from-source", "--from-source") } {
      Set-Variable -Name FromSource -Value $true -Scope 1
      Set-Variable -Name InstallSource -Value "source-checkout" -Scope 1
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

function Test-WindowsPlatform {
  return [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
    [System.Runtime.InteropServices.OSPlatform]::Windows)
}

function Assert-SupportedPowerShellPlatform {
  if ((Test-WindowsPlatform) -or $env:SYMPHONY_TRELLO_ALLOW_NON_WINDOWS_PWSH_FOR_TEST -eq "1") {
    return
  }
  throw "install.ps1 supports Windows PowerShell setup only. On Linux, macOS, or WSL2, use install.sh."
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
  if (-not (Test-WindowsPlatform)) {
    return $true
  }
  return [regex]::IsMatch($root, "^[A-Za-z]:[\\/]\z") -or [regex]::IsMatch($root, "^\\\\[^\\]+\\[^\\]+\\?$")
}

function Test-BroadSystemPath([string]$Path) {
  if ([string]::IsNullOrEmpty($Path)) {
    return $true
  }
  $trimmedPath = [System.IO.Path]::GetFullPath($Path).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
  if (-not (Test-WindowsPlatform)) {
    return $trimmedPath -in @("", "/", "/etc", "/home", "/opt", "/root", "/tmp", "/usr", "/var")
  }
  $root = [System.IO.Path]::GetPathRoot($trimmedPath).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
  return $trimmedPath.Equals($root, [System.StringComparison]::OrdinalIgnoreCase)
}

function Test-SameOrInsidePath([string]$Child, [string]$Parent) {
  $normalizedChild = [System.IO.Path]::GetFullPath($Child).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
  $normalizedParent = [System.IO.Path]::GetFullPath($Parent).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
  return $normalizedChild.Equals($normalizedParent, [System.StringComparison]::OrdinalIgnoreCase) -or
    $normalizedChild.StartsWith("$normalizedParent$([System.IO.Path]::DirectorySeparatorChar)", [System.StringComparison]::OrdinalIgnoreCase) -or
    $normalizedChild.StartsWith("$normalizedParent$([System.IO.Path]::AltDirectorySeparatorChar)", [System.StringComparison]::OrdinalIgnoreCase)
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
  if ((Test-PathHasSymlinkComponent $RawBinDir) -or (Test-PathHasSymlinkComponent $normalizedBinDir)) {
    throw "--bin-dir must not be a symlink."
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
    if ((Test-SameOrInsidePath $normalizedPrefix $root) -or (Test-SameOrInsidePath $root $normalizedPrefix)) {
      throw "--prefix must not overlap Symphony config, workspace, or state directories."
    }
  }
}

function Test-PublicOptionToken([string]$Token) {
  return $Token -in @(
    "-dry-run",
    "--dry-run",
    "-no-onboard",
    "--no-onboard",
    "-no-update-path",
    "--no-update-path",
    "-prefix",
    "--prefix",
    "-bin-dir",
    "--bin-dir",
    "-version",
    "--version",
    "-from-source",
    "--from-source",
    "-repo",
    "--repo",
    "-ref",
    "--ref",
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

function Read-PublicSourceOptionValue([string[]]$Tokens, [int]$Index, [string]$OptionName) {
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
      { $_ -in @("-no-onboard", "--no-onboard") } {
        Set-Variable -Name NoOnboard -Value $true -Scope 1
        break
      }
      { $_ -in @("-no-update-path", "--no-update-path") } {
        Set-Variable -Name NoUpdatePath -Value $true -Scope 1
        break
      }
      { $_ -in @("-from-source", "--from-source") } {
        Set-Variable -Name FromSource -Value $true -Scope 1
        Set-Variable -Name InstallSource -Value "source-checkout" -Scope 1
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
      { $_ -in @("-version", "--version") } {
        $i++
        $value = Read-PublicSourceOptionValue $Tokens $i $token
        Set-Variable -Name Version -Value $value -Scope 1
        if (-not $env:SYMPHONY_TRELLO_RELEASE_BASE_URL) {
          Set-Variable -Name ReleaseBaseUrl -Value "https://github.com/martin-francois/symphony-trello/releases/download/v$value" -Scope 1
        }
        break
      }
      { $_ -in @("-repo", "--repo") } {
        $i++
        Set-Variable -Name Repo -Value (Read-PublicSourceOptionValue $Tokens $i $token) -Scope 1
        Set-Variable -Name InstallSource -Value "source-checkout" -Scope 1
        break
      }
      { $_ -in @("-ref", "--ref") } {
        $i++
        Set-Variable -Name Ref -Value (Read-PublicSourceOptionValue $Tokens $i $token) -Scope 1
        Set-Variable -Name InstallSource -Value "source-checkout" -Scope 1
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

function Assert-SourceInputs {
  if ([string]::IsNullOrWhiteSpace($Repo)) {
    throw "--repo must not be blank."
  }
  if ($Repo.StartsWith("-")) {
    throw "Missing value for --repo"
  }
  if ([regex]::IsMatch($Repo, "\p{Cc}")) {
    throw "--repo must be a URL or local Git path without whitespace or control characters."
  }
  $localGitRepository = Test-LocalGitRepository $Repo
  if (-not $localGitRepository -and [regex]::IsMatch($Repo, "\s")) {
    throw "--repo must be a URL or local Git path without whitespace or control characters."
  }
  if (-not ($localGitRepository -or $Repo.Contains("://") -or $Repo -match "^[^@]+@[^:]+:.+")) {
    throw "--repo must be a URL or existing local Git repository path."
  }

  if ([string]::IsNullOrWhiteSpace($Ref)) {
    throw "--ref must not be blank."
  }
  if ($Ref.StartsWith("-")) {
    throw "Missing value for --ref"
  }
  if ([regex]::IsMatch($Ref, "[\p{Cc}\s]")) {
    throw "--ref must not contain whitespace or control characters."
  }
  if ($Ref.StartsWith("refs/") -or
      $Ref.StartsWith("origin/") -or
      $Ref.StartsWith("/") -or
      $Ref.EndsWith("/") -or
      $Ref.Contains("..") -or
      $Ref.Contains("//") -or
      $Ref.Contains("./") -or
      $Ref.EndsWith(".") -or
      $Ref.StartsWith(".") -or
      $Ref.Contains("/.") -or
      $Ref.Contains("@{") -or
      $Ref.Contains(".lock/") -or
      $Ref.EndsWith(".lock")) {
    throw "--ref must be a branch, tag, or commit without Git namespace prefixes or path traversal."
  }
  if (-not [regex]::IsMatch($Ref, "^[A-Za-z0-9][A-Za-z0-9._/+,%=@-]*$")) {
    throw "--ref contains unsupported characters."
  }
}

function Assert-ReleaseInputs {
  switch ($InstallSource) {
    { $_ -in @("release", "release-archive") } {
      Set-Variable -Name InstallSource -Value "release-archive" -Scope 1
      break
    }
    { $_ -in @("source", "source-checkout") } {
      Set-Variable -Name InstallSource -Value "source-checkout" -Scope 1
      break
    }
    default {
      throw "SYMPHONY_TRELLO_INSTALL_SOURCE must be release-archive or source-checkout."
    }
  }
  if ([string]::IsNullOrWhiteSpace($Version)) {
    throw "--version must not be blank."
  }
  if ($InstallSource -eq "source-checkout") {
    return
  }
  if (-not ($Version -match "^[0-9]+[.][0-9]+[.][0-9]+([-+][A-Za-z0-9._-]+)?$")) {
    throw "--version must be a semantic version without the leading v."
  }
  if (-not $env:SYMPHONY_TRELLO_RELEASE_BASE_URL) {
    Set-Variable -Name ReleaseBaseUrl -Value "https://github.com/martin-francois/symphony-trello/releases/download/v$Version" -Scope 1
  }
  if ([string]::IsNullOrWhiteSpace($ReleaseBaseUrl) -or [regex]::IsMatch($ReleaseBaseUrl, "[\p{Cc}\s]")) {
    throw "SYMPHONY_TRELLO_RELEASE_BASE_URL must be a URL without whitespace or control characters."
  }
  if (-not ($ReleaseBaseUrl.Contains("://"))) {
    throw "SYMPHONY_TRELLO_RELEASE_BASE_URL must be a URL."
  }
}

function Test-LocalGitRepository([string]$Path) {
  if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
    return $false
  }
  $gitPath = Join-Path $Path ".git"
  if (Test-Path -LiteralPath $gitPath) {
    return $true
  }
  return (Test-Path -LiteralPath (Join-Path $Path "HEAD") -PathType Leaf) -and
    (Test-Path -LiteralPath (Join-Path $Path "objects") -PathType Container)
}

function Test-ImplicitDefaultToken([string]$Token) {
  return $Token -eq $DefaultSymphonyHome -or
    $Token -eq $DefaultPrefix -or
    $Token -eq $DefaultBinDir -or
    $Token -eq $DefaultVersion -or
    $Token -eq $DefaultRepo -or
    $Token -eq $DefaultRef
}

function Format-RepositoryForOutput([string]$Value) {
  return $Value -replace '^([A-Za-z][A-Za-z0-9+.-]*://)[^/]*@(.+)$', '${1}<redacted>@$2'
}

$positionalTokens = @($SymphonyHome, $Prefix, $BinDir, $Version, $Repo, $Ref) + $RemainingArgs |
  Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
if (($positionalTokens | Where-Object { $_.StartsWith("-") } | Select-Object -First 1)) {
  $SymphonyHome = $DefaultSymphonyHome
  $Prefix = $DefaultPrefix
  $BinDir = $DefaultBinDir
  $Version = $DefaultVersion
  $Repo = $DefaultRepo
  $Ref = $DefaultRef
  if (-not $env:SYMPHONY_TRELLO_RELEASE_BASE_URL) {
    $ReleaseBaseUrl = "https://github.com/martin-francois/symphony-trello/releases/download/v$Version"
  }
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
  if (Apply-PositionalFlag $Repo) {
    $Repo = $DefaultRepo
  }
  if (Apply-PositionalFlag $Ref) {
    $Ref = $DefaultRef
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
  powershell -c "irm https://symphony-trello.fmartin.ch/install.ps1 | iex"

Options:
  --dry-run     Print planned actions without changing files.
  --no-onboard  Install or update the command without running setup-local.
  --no-update-path
                 Do not edit the current user PATH.
  --version VERSION
                 Release version to install without leading v. Default: $DefaultVersion.
  --from-source Install from a Git checkout instead of release assets.
  -DryRun       Alias for --dry-run.
  -NoOnboard    Alias for --no-onboard.
  -Prefix PATH  App install path. Default: <SYMPHONY_HOME>\app.
  -BinDir PATH  Command directory.
"@
  exit 0
}

Assert-SupportedPowerShellPlatform
Assert-ReleaseInputs
if ($InstallSource -eq "source-checkout") {
  Assert-SourceInputs
}
Assert-AppPathOptionValues
Assert-BinDirOptionValue
$SymphonyHome = [System.IO.Path]::GetFullPath($SymphonyHome)
if (-not $Prefix) {
  $Prefix = Join-Path $SymphonyHome "app"
}
$ConfigDir = if ($env:SYMPHONY_TRELLO_CONFIG_DIR) { $env:SYMPHONY_TRELLO_CONFIG_DIR } else { Join-Path $SymphonyHome "config" }
$WorkspaceRoot = if ($env:SYMPHONY_TRELLO_WORKSPACE_ROOT) { $env:SYMPHONY_TRELLO_WORKSPACE_ROOT } else { Join-Path $SymphonyHome "workspaces" }
$StateHome = if ($env:SYMPHONY_TRELLO_STATE_HOME) { $env:SYMPHONY_TRELLO_STATE_HOME } else { Join-Path $SymphonyHome "state" }
$RawPrefix = $Prefix
$Prefix = [System.IO.Path]::GetFullPath($Prefix)
$ConfigDir = [System.IO.Path]::GetFullPath($ConfigDir)
$WorkspaceRoot = [System.IO.Path]::GetFullPath($WorkspaceRoot)
$StateHome = [System.IO.Path]::GetFullPath($StateHome)
$RawBinDir = $BinDir
$BinDir = [System.IO.Path]::GetFullPath($BinDir)
$AutostartEnvironmentPath = Join-Path $ConfigDir "autostart-env.ps1"
$AutostartScriptPath = Join-Path $BinDir "symphony-trello-autostart.ps1"
$CodexNpmPrefix = Join-Path $SymphonyHome "npm"
$InstallContextFile = Join-Path $StateHome "install-context.properties"
Assert-AppPaths
Assert-CommandDirectory

function Invoke-Step([string]$Label, [scriptblock]$Action) {
  Write-Host "  RUN  $Label"
  if (-not $DryRun) {
    $global:LASTEXITCODE = 0
    & $Action
    if (-not $?) {
      throw "$Label failed."
    }
    if ($global:LASTEXITCODE -ne 0) {
      throw "$Label failed with exit code $global:LASTEXITCODE."
    }
  }
}

function Get-InstalledAppVersionFallback {
  if ($InstallSource -eq "release-archive") {
    return $Version
  }
  return "unknown"
}

function Get-InstalledAppVersion {
  if (-not (Test-Command "java")) {
    return (Get-InstalledAppVersionFallback)
  }
  $classpath = @(
    (Join-Path $Prefix "target\quarkus-app\quarkus-run.jar"),
    (Join-Path $Prefix "target\quarkus-app\app\*"),
    (Join-Path $Prefix "target\quarkus-app\lib\main\*"),
    (Join-Path $Prefix "target\quarkus-app\quarkus\*")
  ) -join ";"
  try {
    $output = @((& java "-Dsymphony.trello.app.home=$Prefix" "-Dsymphony.trello.config.dir=$ConfigDir" -cp $classpath ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain --version) 2>$null)
    if ($LASTEXITCODE -eq 0 -and $output.Count -eq 1 -and $output[0] -match '^symphony-trello\s+(.+)$') {
      return $Matches[1].Trim()
    }
  } catch {
    return (Get-InstalledAppVersionFallback)
  }
  return (Get-InstalledAppVersionFallback)
}

function Get-InstalledSourceCommit {
  if ($InstallSource -ne "source-checkout" -or -not (Test-Path -LiteralPath (Join-Path $Prefix ".git")) -or -not (Test-Command "git")) {
    return ""
  }
  try {
    $commit = (& git -C $Prefix rev-parse --verify HEAD) 2>$null | Select-Object -First 1
    if ($LASTEXITCODE -eq 0) {
      return $commit
    }
  } catch {
    return ""
  }
  return ""
}

function Write-InstallContext {
  if ($DryRun) {
    return
  }
  try {
    New-Item -ItemType Directory -Force -Path $StateHome | Out-Null
    $packageManager = if (Test-Command "winget") { "winget" } else { "none" }
    $appVersion = Get-InstalledAppVersion
    $releaseMetadata = @()
    if ($InstallSource -eq "release-archive") {
      $releaseMetadata = @(
        "release_tag=v$Version",
        "release_base_url=$ReleaseBaseUrl"
      )
    } else {
      $sourceCommit = Get-InstalledSourceCommit
      if (-not [string]::IsNullOrWhiteSpace($sourceCommit)) {
        $releaseMetadata = @("source_commit=$sourceCommit")
      }
    }
    @(
      "installer=install.ps1",
      "install_format_version=1",
      "install_source=$InstallSource",
      "app_version=$appVersion",
      $releaseMetadata,
      "platform=$(Get-PlatformLabel)",
      "repo_url=$Repo",
      "ref=$Ref",
      "app_dir=$Prefix",
      "config_dir=$ConfigDir",
      "workspace_root=$WorkspaceRoot",
      "state_home=$StateHome",
      "bin_dir=$BinDir",
      "dry_run=$DryRun",
      "no_onboard=$NoOnboard",
      "codex_npm_prefix=$CodexNpmPrefix",
      "git_available=$(if (Test-Command "git") { "yes" } else { "no" })",
      "java_25_available=$(if (Test-Java25) { "yes" } else { "no" })",
      "npm_available=$(if (Test-Command "npm") { "yes" } else { "no" })",
      "codex_available=$(if (Test-Command "codex") { "yes" } else { "no" })",
      "gh_available=$(if (Test-Command "gh") { "yes" } else { "no" })",
      "package_manager=$packageManager"
    ) | Set-Content -Encoding UTF8 -Path $InstallContextFile
  } catch {
    return
  }
}

function Test-AutostartEnvironmentName([string]$Name) {
  if (-not [regex]::IsMatch($Name, "^[A-Za-z_][A-Za-z0-9_]*$")) {
    return $false
  }
  return $Name.StartsWith("TRELLO_") -or
    $Name.StartsWith("SYMPHONY_") -or
    $Name.StartsWith("CODEX_") -or
    $Name.StartsWith("GITHUB_") -or
    $Name.StartsWith("GH_") -or
    $Name.StartsWith("OPENAI_") -or
    $Name.StartsWith("ANTHROPIC_") -or
    $Name.StartsWith("QUARKUS_")
}

function Write-AutostartEnvironmentFile {
  if ($DryRun) {
    Write-Host "  WOULD write autostart environment snapshot: $AutostartEnvironmentPath"
    return
  }
  New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null
  $lines = @(
    "# Installer-managed Symphony for Trello autostart environment.",
    "# Contains only prefixed runtime variables from the installer session."
  )
  Get-ChildItem Env: |
    Sort-Object Name |
    Where-Object {
      (Test-AutostartEnvironmentName $_.Name) -and
        (-not [string]::IsNullOrEmpty($_.Value)) -and
        (-not [regex]::IsMatch($_.Value, "\p{Cc}"))
    } |
    ForEach-Object {
      $lines += "`$env:$($_.Name) = $(ConvertTo-PowerShellLiteral $_.Value)"
    }
  $lines | Set-Content -Encoding UTF8 -Path $AutostartEnvironmentPath
  Protect-PrivateFile $AutostartEnvironmentPath
}

function Protect-PrivateFile([string]$Path) {
  $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
  $acl = New-Object System.Security.AccessControl.FileSecurity
  $rule = New-Object System.Security.AccessControl.FileSystemAccessRule($identity, "FullControl", "Allow")
  $acl.SetAccessRuleProtection($true, $false)
  $acl.AddAccessRule($rule)
  Set-Acl -LiteralPath $Path -AclObject $acl
}

function Write-AutostartLauncher {
  Write-AutostartEnvironmentFile
  if ($DryRun) {
    Write-Host "  WOULD write Windows autostart launcher: $AutostartScriptPath"
    return
  }
  New-Item -ItemType Directory -Force -Path $BinDir | Out-Null
  $environmentPathLiteral = ConvertTo-PowerShellLiteral $AutostartEnvironmentPath
  $wrapperLiteral = ConvertTo-PowerShellLiteral (Join-Path $BinDir "symphony-trello.ps1")
@"
`$ErrorActionPreference = "Stop"
if (Test-Path -LiteralPath $environmentPathLiteral) {
  . $environmentPathLiteral
}
& $wrapperLiteral start --all
exit `$LASTEXITCODE
"@ | Set-Content -Encoding UTF8 -Path $AutostartScriptPath
}

function Get-StartAllCommand {
  return "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$AutostartScriptPath`""
}

function Install-StartupFolderCommand {
  Write-AutostartLauncher
  $startCommand = Get-StartAllCommand
  if ($DryRun) {
    Write-Host "  WOULD write Windows Startup command: $StartupCommandPath"
    return $true
  }
  New-Item -ItemType Directory -Force -Path $StartupFolder | Out-Null
@"
@echo off
$startCommand
"@ | Set-Content -Encoding ASCII $StartupCommandPath
  Write-Host "  OK  Windows Startup command installed: $StartupCommandPath"
  return $true
}

function Install-ScheduledTaskAutostart {
  if (-not (Get-Command Register-ScheduledTask -ErrorAction SilentlyContinue)) {
    return "ScheduledTasks module is not available."
  }
  try {
    $action = New-ScheduledTaskAction `
      -Execute "powershell.exe" `
      -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$AutostartScriptPath`""
    $trigger = New-ScheduledTaskTrigger -AtLogOn
    Register-ScheduledTask `
      -TaskName $ScheduledTaskName `
      -Action $action `
      -Trigger $trigger `
      -Description "Start Symphony for Trello connected boards at user logon." `
      -Force | Out-Null
    return ""
  } catch {
    return $_.Exception.Message
  }
}

function Enable-WindowsAutostart {
  Write-AutostartLauncher
  if ($DryRun) {
    Write-Host "  WOULD create Windows Scheduled Task: $ScheduledTaskName"
    Write-Host "  WOULD start managed workers through Windows Scheduled Task"
    return $true
  }
  $createResult = Install-ScheduledTaskAutostart
  if ([string]::IsNullOrWhiteSpace($createResult)) {
    Write-Host "  OK  Windows Scheduled Task installed: $ScheduledTaskName"
    try {
      Start-ScheduledTask -TaskName $ScheduledTaskName
      Write-Host "  OK  Windows Scheduled Task started managed workers."
      return $true
    } catch {
      Write-Host "  NOTE  Scheduled Task was installed but could not be started immediately. It will run at the next user logon."
      Invoke-Step "$BinDir\symphony-trello.ps1 start --all" { & "$BinDir\symphony-trello.ps1" start --all }
      return $true
    }
  }
  Write-Host "  NOTE  Could not create Windows Scheduled Task: $createResult"
  if (Install-StartupFolderCommand) {
    Invoke-Step "$BinDir\symphony-trello.ps1 start --all" { & "$BinDir\symphony-trello.ps1" start --all }
    return $true
  }
  return $false
}

function Start-ManagedWorkers {
  Write-Host "Starting managed workers..."
  if (Enable-WindowsAutostart) {
    return
  }
  Write-Host "  NOTE  Autostart was not configured. Use '$BinDir\symphony-trello.ps1 start --all' after reboot or login."
  Invoke-Step "$BinDir\symphony-trello.ps1 start --all" { & "$BinDir\symphony-trello.ps1" start --all }
}

function Test-Command([string]$Name) {
  $null -ne (Get-Command $Name -ErrorAction SilentlyContinue)
}

function Test-Java25 {
  if (-not (Test-Command "java")) {
    return $false
  }
  if (-not (Test-Command "javac")) {
    return $false
  }
  $output = (& java -version) 2>&1 | Out-String
  $javacOutput = (& javac -version) 2>&1 | Out-String
  return $output -match 'version "([0-9]+)' -and [int]$Matches[1] -ge 25 -and $javacOutput -match 'javac ([0-9]+)' -and [int]$Matches[1] -ge 25
}

function Test-PathContains([string]$Directory, [string]$PathValue = $env:PATH) {
  $separator = [System.IO.Path]::PathSeparator
  return ($PathValue -split [regex]::Escape($separator)) -contains $Directory
}

function Test-InteractiveInput {
  try {
    return [Environment]::UserInteractive -and (-not [Console]::IsInputRedirected)
  } catch {
    return $false
  }
}

function Get-UserPathValue {
  $value = [Environment]::GetEnvironmentVariable("Path", "User")
  if ($null -eq $value) {
    return ""
  }
  return $value
}

function Write-PathSetupInstructions {
  Write-Host "  NOTE  $BinDir is not on PATH for this PowerShell session."
  Write-Host "        Future PowerShell windows can run symphony-trello after this directory is in the current user PATH:"
  Write-Host "        $BinDir"
}

function Add-BinDirToUserPath {
  $userPath = Get-UserPathValue
  if (Test-PathContains $BinDir $userPath) {
    Write-Host "  OK  PATH setup already exists for the current user:"
    Write-Host "      $BinDir"
    return
  }
  if ($DryRun) {
    Write-Host "  WOULD add $BinDir to the current user PATH."
    return
  }
  $separator = [System.IO.Path]::PathSeparator
  $newPath = if ([string]::IsNullOrWhiteSpace($userPath)) { $BinDir } else { "$BinDir$separator$userPath" }
  try {
    [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
    Add-PathPrefix $BinDir
    Write-Host "  OK  Added $BinDir to the current user PATH."
  } catch {
    Write-Host "  NOTE  Could not update the current user PATH."
    Write-PathSetupInstructions
  }
}

function Offer-PathSetup {
  if (Test-PathContains $BinDir $OriginalPath) {
    return
  }
  if ($NoUpdatePath) {
    Write-PathSetupInstructions
    return
  }
  Write-Host
  Write-Host "Command PATH setup"
  if ($DryRun) {
    Write-Host "Symphony would install the command here:"
  } else {
    Write-Host "Symphony installed the command here:"
  }
  Write-Host "  $BinDir\symphony-trello.ps1"
  Add-BinDirToUserPath
}

function Add-PathPrefix([string]$Directory) {
  if (-not (Test-PathContains $Directory $env:PATH)) {
    $env:PATH = "$Directory$([System.IO.Path]::PathSeparator)$env:PATH"
  }
}

function Enable-ManagedCodexPath {
  $managedCodexCommands = @(
    (Join-Path $BinDir "codex.cmd"),
    (Join-Path $BinDir "codex.ps1"),
    (Join-Path $CodexNpmPrefix "codex.cmd"),
    (Join-Path $CodexNpmPrefix "codex.ps1"),
    (Join-Path $CodexNpmPrefix "bin\codex.cmd"),
    (Join-Path $CodexNpmPrefix "bin\codex.ps1")
  )
  if ($managedCodexCommands | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1) {
    Add-PathPrefix (Join-Path $CodexNpmPrefix "bin")
    Add-PathPrefix $CodexNpmPrefix
    Add-PathPrefix $BinDir
  }
}

function Get-ManagedPidFile {
  if (-not (Test-Path -LiteralPath $StateHome -PathType Container)) {
    return $null
  }
  return Get-ChildItem -LiteralPath $StateHome -Filter "*.pid" -File -ErrorAction SilentlyContinue |
    Select-Object -First 1
}

function ConvertTo-PowerShellLiteral([string]$Value) {
  return "'" + ($Value -replace "'", "''") + "'"
}

function Get-PlatformLabel {
  if (-not (Test-WindowsPlatform)) {
    if ($env:SYMPHONY_TRELLO_ALLOW_NON_WINDOWS_PWSH_FOR_TEST -eq "1") {
      return "Non-Windows PowerShell test runtime"
    }
    throw "install.ps1 supports Windows PowerShell setup only. On Linux, macOS, or WSL2, use install.sh."
  }
  $architecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString().ToLowerInvariant()
  if ($architecture -eq "x64") {
    return "Windows amd64"
  }
  if ($architecture -eq "arm64") {
    return "Windows arm64 (best effort)"
  }
  throw "Unsupported platform: Windows $architecture. Supported Windows platforms: amd64, with arm64 best-effort."
}

function Read-YesNo([string]$Prompt) {
  $answer = Read-Host $Prompt
  return $answer -match '^[Yy]'
}

function Get-WingetPackageCommand([string]$Package) {
  if (-not (Test-Command "winget")) {
    return ""
  }
  switch ($Package) {
    "git" { return "winget install --id Git.Git --source winget" }
    "java" { return "winget install --id Azul.Zulu.25.JDK --source winget" }
    "node" { return "winget install --id OpenJS.NodeJS.LTS --source winget" }
    "gh" { return "winget install --id GitHub.cli --source winget" }
    default { return "" }
  }
}

function Install-PackageOrExit([string]$Label, [string]$Package, [string]$Fallback) {
  $command = Get-WingetPackageCommand $Package
  if (-not $command) {
    throw "$Label is required.`n$Fallback`nThen rerun this installer."
  }
  Write-Host
  Write-Host "$Label is missing."
  Write-Host "Proposed install command:"
  Write-Host "  $command"
  if (-not (Read-YesNo "Run this command now? [y/N]")) {
    throw "$Fallback`nThen rerun this installer."
  }
  Invoke-Step $command { Invoke-Expression $command }
}

function Assert-AvailableAfterInstall([scriptblock]$Check, [string]$Label, [string]$Fix) {
  if (-not (& $Check)) {
    throw "$Label was installed, but it is not available in this PowerShell session.`n$Fix`nThen rerun this installer."
  }
}

function Test-CodexAuthenticated {
  if (-not (Test-Command "codex")) {
    return $false
  }
  & codex login status *> $null
  return $LASTEXITCODE -eq 0
}

function Get-CodexInstallPlan {
  $nodeStatus = if (Test-Command "npm") { "yes" } else { "no" }
  return "Symphony-managed npm at $CodexNpmPrefix (Node.js/npm installed: $nodeStatus)"
}

function Get-CodexNpmInstallCommand {
  return "npm install --global --prefix $(ConvertTo-PowerShellLiteral $CodexNpmPrefix) @openai/codex"
}

function Write-CodexNpmInstallPlan {
  Write-Host "Install Codex CLI with Symphony-managed npm."
  Write-Host "  Install location: $CodexNpmPrefix"
  Write-Host "  Command link: $(Join-Path $BinDir 'codex.cmd')"
  Write-Host "This keeps system-wide npm packages unchanged."
  if (-not (Test-Command "npm")) {
    $nodeCommand = Get-WingetPackageCommand "node"
    if (-not $nodeCommand) {
      throw "Automatic Node.js/npm install requires the Windows Package Manager.`nInstall Node.js with npm from https://nodejs.org/ or the Windows Package Manager.`nThen rerun this installer."
    }
    Write-Host "  Node.js/npm install: $nodeCommand"
  }
  Write-Host "  Codex CLI install: $(Get-CodexNpmInstallCommand)"
}

function Install-CodexWithUserLocalNpm {
  if (-not (Test-Command "npm")) {
    $nodeCommand = Get-WingetPackageCommand "node"
    if (-not $nodeCommand) {
      throw "Automatic Node.js/npm install requires the Windows Package Manager.`nInstall Node.js with npm from https://nodejs.org/ or the Windows Package Manager.`nThen rerun this installer."
    }
    Invoke-Step $nodeCommand { Invoke-Expression $nodeCommand }
    Assert-AvailableAfterInstall { Test-Command "npm" } "Node.js/npm" "Open a new PowerShell window with npm on PATH."
  }
  Invoke-Step (Get-CodexNpmInstallCommand) {
    & npm install --global --prefix $CodexNpmPrefix "@openai/codex"
  }
  Invoke-Step "make Codex CLI available from $BinDir" {
    New-Item -ItemType Directory -Force -Path $BinDir | Out-Null
    $codexNpmPrefixLiteral = ConvertTo-PowerShellLiteral $CodexNpmPrefix
    @"
@echo off
set "CODEX_NPM_PREFIX=$CodexNpmPrefix"
if exist "%CODEX_NPM_PREFIX%\codex.cmd" (
  call "%CODEX_NPM_PREFIX%\codex.cmd" %*
) else (
  call "%CODEX_NPM_PREFIX%\bin\codex.cmd" %*
)
"@ | Set-Content -Encoding ASCII (Join-Path $BinDir "codex.cmd")
    @"
`$CodexNpmPrefix = $codexNpmPrefixLiteral
`$CodexCommand = Join-Path `$CodexNpmPrefix "codex.ps1"
if (-not (Test-Path `$CodexCommand)) {
  `$CodexCommand = Join-Path `$CodexNpmPrefix "bin\codex.ps1"
}
& `$CodexCommand @args
exit `$LASTEXITCODE
"@ | Set-Content -Encoding UTF8 (Join-Path $BinDir "codex.ps1")
  }
  $env:PATH = "$BinDir;$CodexNpmPrefix;$CodexNpmPrefix\bin;$env:PATH"
}

function Install-CodexOrExit {
  if (Test-CodexAuthenticated) {
    return
  }
  if (Test-Command "codex") {
    return
  }
  Write-Host
  if (Test-Command "npm") {
    Write-Host "Codex CLI is missing."
  } else {
    Write-Host "Codex CLI is missing and needs Node.js with npm."
  }
  Write-CodexNpmInstallPlan
  if (-not (Read-YesNo "Run now? [y/N]")) {
    throw "Install Codex CLI, then rerun this installer.`nThe installer will help you log in after it finds Codex CLI."
  }
  Install-CodexWithUserLocalNpm
  Assert-AvailableAfterInstall { Test-Command "codex" } "Codex CLI" "Open a new PowerShell window with the Symphony bin directory on PATH."
}

function Ensure-Prerequisites {
  if ($InstallSource -eq "source-checkout" -and -not (Test-Command "git")) {
    Install-PackageOrExit "Git" "git" "Install Git from https://git-scm.com/download/win or the Windows Package Manager."
    Assert-AvailableAfterInstall { Test-Command "git" } "Git" "Open a new PowerShell window with Git on PATH."
  }
  if (-not (Test-Java25)) {
    Install-PackageOrExit "Java 25+ JDK" "java" "Install a Java 25 or newer JDK with javac, then make java and javac available on PATH."
    Assert-AvailableAfterInstall { Test-Java25 } "Java 25+ JDK" "Open a new PowerShell window with java and javac on PATH."
  }
  if (-not $NoOnboard) {
    Install-CodexOrExit
  }
}

function Write-DryRunPrerequisitePlan {
  if ($InstallSource -eq "source-checkout" -and -not (Test-Command "git")) {
    $command = Get-WingetPackageCommand "git"
    Write-Host "  WOULD offer to install Git$(if ($command) { " with: $command" })"
  }
  if (-not (Test-Java25)) {
    $command = Get-WingetPackageCommand "java"
    Write-Host "  WOULD offer to install Java 25+ JDK$(if ($command) { " with: $command" })"
  }
  if ((-not $NoOnboard) -and (-not (Test-Command "codex"))) {
    $nodeStatus = if (Test-Command "npm") { "yes" } else { "no" }
    Write-Host "  WOULD offer to install Codex CLI with Symphony-managed npm:"
    Write-Host "          Install location: $CodexNpmPrefix"
    Write-Host "          Command link: $(Join-Path $BinDir 'codex.cmd')"
    Write-Host "          Node.js/npm installed: $nodeStatus"
  } elseif ($NoOnboard -and (-not (Test-Command "codex"))) {
    Write-Host "  NOTE   Codex CLI setup is skipped because --no-onboard was passed."
  }
}

function Test-RemoteBranch([string]$Remote, [string]$GitRef) {
  & git ls-remote --exit-code --heads $Remote $GitRef *> $null
  return $LASTEXITCODE -eq 0
}

function Invoke-CheckoutRef([string]$CheckoutPath, [string]$GitRef) {
  & git -C $CheckoutPath show-ref --verify --quiet "refs/remotes/origin/$GitRef"
  if ($LASTEXITCODE -eq 0) {
    Invoke-Step "git -C $CheckoutPath checkout -B $GitRef origin/$GitRef" {
      & git -C $CheckoutPath checkout -B $GitRef "origin/$GitRef"
    }
    Invoke-Step "git -C $CheckoutPath pull --ff-only origin $GitRef" {
      & git -C $CheckoutPath pull --ff-only origin $GitRef
    }
  } else {
    Invoke-Step "git -C $CheckoutPath checkout --detach $GitRef" {
      & git -C $CheckoutPath checkout --detach $GitRef
    }
  }
}

function Assert-ExistingCheckoutSafe {
  if (Test-Path "$Prefix\.symphony-trello-install") {
    return
  }
  $originUrl = ""
  try {
    $originUrl = (& git -C $Prefix remote get-url origin) 2>$null
    if ($LASTEXITCODE -ne 0) {
      $originUrl = ""
    }
  } catch {
    $originUrl = ""
  }
  if ($originUrl -eq $Repo) {
    return
  }
  $displayRepo = Format-RepositoryForOutput $Repo
  throw "Refusing to update existing Git checkout without Symphony installer marker:`n  $Prefix`nUse a dedicated app checkout path, or pass a checkout whose origin remote is:`n  $displayRepo"
}

function Assert-ExistingAppSafe {
  if ((-not (Test-Path -LiteralPath $Prefix)) -or (Test-Path -LiteralPath (Join-Path $Prefix ".symphony-trello-install"))) {
    return
  }
  throw "Refusing to replace existing app directory without Symphony installer marker:`n  $Prefix`nUse a dedicated app install path or remove the directory manually after backing up anything important."
}

function Invoke-DownloadFile([string]$Url, [string]$OutputPath) {
  Invoke-Step "download $Url" {
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $OutputPath
  }
}

function Get-Sha3FileHash([string]$Path) {
  $helperDir = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid().ToString("N"))
  New-Item -ItemType Directory -Force -Path $helperDir | Out-Null
  try {
    $helper = Join-Path $helperDir "Sha3File.java"
@'
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

class Sha3File {
    public static void main(String[] args) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA3-256");
        System.out.println(HexFormat.of().formatHex(digest.digest(Files.readAllBytes(Path.of(args[0])))));
    }
}
'@ | Set-Content -Encoding ASCII -Path $helper
    $output = (& java $helper $Path) 2>&1
    if ($LASTEXITCODE -ne 0) {
      throw "SHA3-256 checksum calculation failed."
    }
    return ($output | Select-Object -First 1).ToString().Trim()
  } finally {
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $helperDir
  }
}

function Assert-ReleaseArchiveChecksum([string]$ArchivePath, [string]$ChecksumsPath) {
  $archiveName = Split-Path -Leaf $ArchivePath
  $expected = Get-Content -Path $ChecksumsPath |
    ForEach-Object {
      $parts = $_ -split "\s+", 2
      if ($parts.Count -eq 2 -and $parts[1] -eq $archiveName) {
        $parts[0]
      }
    } |
    Select-Object -First 1
  if ([string]::IsNullOrWhiteSpace($expected)) {
    throw "checksums.txt does not contain $archiveName."
  }
  $actual = Get-Sha3FileHash $ArchivePath
  if ($actual -ne $expected) {
    throw "Release archive checksum verification failed for $archiveName."
  }
}

function Install-ReleaseArchive {
  Assert-ExistingAppSafe
  $archiveName = "symphony-trello-$Version.zip"
  $archiveUrl = "$ReleaseBaseUrl/$archiveName"
  $checksumsUrl = "$ReleaseBaseUrl/checksums.txt"
  $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid().ToString("N"))
  New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
  try {
    $archive = Join-Path $tempDir $archiveName
    $checksums = Join-Path $tempDir "checksums.txt"
    Invoke-DownloadFile $archiveUrl $archive
    Invoke-DownloadFile $checksumsUrl $checksums
    Assert-ReleaseArchiveChecksum $archive $checksums
    $extractDir = Join-Path $tempDir "extract"
    New-Item -ItemType Directory -Force -Path $extractDir | Out-Null
    Invoke-Step "expand $archiveName" {
      Expand-Archive -LiteralPath $archive -DestinationPath $extractDir -Force
    }
    $extractedRoot = Join-Path $extractDir "symphony-trello-$Version"
    if (-not (Test-Path -LiteralPath (Join-Path $extractedRoot "target\quarkus-app\quarkus-run.jar") -PathType Leaf)) {
      throw "Release archive is missing target/quarkus-app/quarkus-run.jar."
    }
    Invoke-Step "install release archive to $Prefix" {
      Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $Prefix
      New-Item -ItemType Directory -Force -Path (Split-Path $Prefix) | Out-Null
      Move-Item -LiteralPath $extractedRoot -Destination $Prefix
    }
  } finally {
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $tempDir
  }
}

Write-Host "Symphony for Trello installer"
Write-Host
Enable-ManagedCodexPath
Write-Host "Detected $(Get-PlatformLabel)"
Write-Host "Install: $Prefix"
Write-Host "Config: $ConfigDir"
Write-Host "Workspaces: $WorkspaceRoot"
Write-Host "State/logs: $StateHome"
Write-Host "Command: $BinDir\symphony-trello.ps1"
Write-Host "Install source: $InstallSource"
if ($InstallSource -eq "source-checkout") {
  Write-Host "Repository: $(Format-RepositoryForOutput $Repo)"
  Write-Host "Ref: $Ref"
} else {
  Write-Host "Version: $Version"
  Write-Host "Release assets: $ReleaseBaseUrl"
}
Write-Host
Write-Host "Checking prerequisites..."
if ($InstallSource -eq "source-checkout") {
  Write-Host ($(if (Test-Command "git") { "  OK      Git available" } else { "  NEEDED  Git" }))
}
Write-Host ($(if (Test-Java25) { "  OK      Java 25+ JDK available" } else { "  NEEDED  Java 25+ JDK" }))
Write-Host ($(if (Test-Command "codex") {
      "  OK      Codex CLI available"
    } elseif ($NoOnboard) {
      "  NEEDED  Codex CLI (only needed for guided setup; skipped by --no-onboard)"
    } else {
      "  NEEDED  Codex CLI"
    }))

if ($DryRun) {
  Write-Host
  Write-Host "Dry run: no files changed."
  Write-DryRunPrerequisitePlan
  if ($InstallSource -eq "source-checkout") {
    Write-Host "  WOULD clone or update: $Prefix"
    Write-Host "  WOULD build packaged Quarkus app with Maven wrapper"
  } else {
    Write-Host "  WOULD download release archive: $ReleaseBaseUrl/symphony-trello-$Version.zip"
    Write-Host "  WOULD verify SHA3-256 checksum with: $ReleaseBaseUrl/checksums.txt"
    Write-Host "  WOULD unpack release archive to: $Prefix"
  }
  Write-Host "  WOULD install CLI executable: $BinDir\symphony-trello.ps1"
  Offer-PathSetup
  if (-not $NoOnboard) {
    Write-Host "  WOULD run guided setup after install."
  }
  exit 0
}

Ensure-Prerequisites

if (-not $NoOnboard) {
  try {
    & codex login status *> $null
    $codexAuthenticated = $LASTEXITCODE -eq 0
  } catch {
    $codexAuthenticated = $false
  }
  if (-not $codexAuthenticated) {
    Write-Host "Codex CLI is installed but not logged in."
    $browser = Read-Host "Can this machine open a browser for Codex login? [Y/n]"
    $loginCommand = "codex login"
    if ($browser -match '^[Nn]') {
      $loginCommand = "codex login --device-auth"
      try {
        Invoke-Step $loginCommand { & codex login --device-auth }
      } catch {
        throw "Codex login did not complete successfully.`nRun '$loginCommand', then rerun this installer."
      }
    } else {
      try {
        Invoke-Step $loginCommand { & codex login }
      } catch {
        throw "Codex login did not complete successfully.`nRun '$loginCommand', then rerun this installer."
      }
    }
    & codex login status *> $null
    if ($LASTEXITCODE -ne 0) {
      throw "Codex login did not complete successfully.`nRun '$loginCommand', then rerun this installer."
    }
  }
}

Write-Host
Write-Host "Installing Symphony..."
$UpdatingExistingApp = Test-Path -LiteralPath $Prefix
$RestartManagedWorkers = $false
if ($UpdatingExistingApp -and (Get-ManagedPidFile)) {
  $RestartManagedWorkers = $true
  Write-Host "Stopping managed workers before update..."
  Invoke-Step "$BinDir\symphony-trello.ps1 stop" { & "$BinDir\symphony-trello.ps1" stop }
}
if ($InstallSource -eq "source-checkout") {
  $UpdatingExistingCheckout = Test-Path -LiteralPath (Join-Path $Prefix ".git")
  if (-not $UpdatingExistingCheckout) {
    if (Test-Path -LiteralPath $Prefix) {
      Assert-ExistingAppSafe
      Invoke-Step "remove existing release archive app $Prefix" {
        Remove-Item -Recurse -Force $Prefix
      }
    }
    $displayRepo = Format-RepositoryForOutput $Repo
    if (Test-RemoteBranch $Repo $Ref) {
      Invoke-Step "git clone --branch $Ref $displayRepo $Prefix" {
        New-Item -ItemType Directory -Force -Path (Split-Path $Prefix) | Out-Null
        & git clone --branch $Ref $Repo $Prefix
      }
    } else {
      Invoke-Step "git clone $displayRepo $Prefix" {
        New-Item -ItemType Directory -Force -Path (Split-Path $Prefix) | Out-Null
        & git clone $Repo $Prefix
      }
      Invoke-Step "git -C $Prefix fetch --tags --prune origin" {
        & git -C $Prefix fetch --tags --prune origin
      }
      Invoke-CheckoutRef $Prefix $Ref
    }
  } else {
    Assert-ExistingCheckoutSafe
    Invoke-Step "git -C $Prefix fetch --tags --prune origin" {
      & git -C $Prefix fetch --tags --prune origin
    }
    Invoke-CheckoutRef $Prefix $Ref
  }
  Invoke-Step "$Prefix\mvnw.cmd -q -DskipTests clean package" { & "$Prefix\mvnw.cmd" -q -f "$Prefix\pom.xml" -DskipTests clean package }
} else {
  Install-ReleaseArchive
}
Invoke-Step "create $BinDir\symphony-trello.ps1" {
  New-Item -ItemType Directory -Force -Path $BinDir, $ConfigDir, $WorkspaceRoot, $StateHome | Out-Null
  Set-Content -Encoding ASCII -Path "$Prefix\.symphony-trello-install" -Value "symphony-trello installer-managed app directory"
  $PrefixLiteral = ConvertTo-PowerShellLiteral $Prefix
  $ConfigDirLiteral = ConvertTo-PowerShellLiteral $ConfigDir
  $WorkspaceRootLiteral = ConvertTo-PowerShellLiteral $WorkspaceRoot
  $StateHomeLiteral = ConvertTo-PowerShellLiteral $StateHome
  $CommandLiteral = ConvertTo-PowerShellLiteral (Join-Path $BinDir "symphony-trello.cmd")
  $BinDirLiteral = ConvertTo-PowerShellLiteral $BinDir
  $CodexNpmPrefixLiteral = ConvertTo-PowerShellLiteral $CodexNpmPrefix
@"
param(
  [Parameter(ValueFromRemainingArguments=`$true)]
  [string[]]`$ScriptArgs = @()
)
`$ErrorActionPreference = "Stop"
`$AppHome = $PrefixLiteral
`$InstalledAppHome = $PrefixLiteral
`$InstalledConfigDir = $ConfigDirLiteral
`$InstalledWorkspaceRoot = $WorkspaceRootLiteral
`$InstalledStateHome = $StateHomeLiteral
`$ConfigDir = if (`$env:SYMPHONY_TRELLO_CONFIG_DIR) { `$env:SYMPHONY_TRELLO_CONFIG_DIR } else { $ConfigDirLiteral }
`$WorkspaceRoot = if (`$env:SYMPHONY_TRELLO_WORKSPACE_ROOT) { `$env:SYMPHONY_TRELLO_WORKSPACE_ROOT } else { $WorkspaceRootLiteral }
`$StateHome = if (`$env:SYMPHONY_TRELLO_STATE_HOME) { `$env:SYMPHONY_TRELLO_STATE_HOME } else { $StateHomeLiteral }
`$BinDir = $BinDirLiteral
`$CodexNpmPrefix = $CodexNpmPrefixLiteral
`$env:PATH = "`$BinDir;`$CodexNpmPrefix;`$CodexNpmPrefix\bin;`$env:PATH"
New-Item -ItemType Directory -Force -Path `$StateHome | Out-Null
function Invoke-SetupCli {
  param([string[]]`$CliArgs = @())
  `$classpath = @(
    (Join-Path `$AppHome "target\quarkus-app\quarkus-run.jar"),
    (Join-Path `$AppHome "target\quarkus-app\app\*"),
    (Join-Path `$AppHome "target\quarkus-app\lib\main\*"),
    (Join-Path `$AppHome "target\quarkus-app\quarkus\*")
  ) -join ";"
  `$callerDir = (Get-Location).Path
  `$env:SYMPHONY_TRELLO_APP_HOME = `$AppHome
  `$env:SYMPHONY_TRELLO_COMMAND = $CommandLiteral
  `$env:SYMPHONY_TRELLO_CONFIG_DIR = `$ConfigDir
  `$env:SYMPHONY_TRELLO_WORKSPACE_ROOT = `$WorkspaceRoot
  `$env:SYMPHONY_TRELLO_STATE_HOME = `$StateHome
  `$env:SYMPHONY_TRELLO_CALLER_DIR = `$callerDir
  if (-not `$env:SYMPHONY_TRELLO_DOTENV) {
    `$env:SYMPHONY_TRELLO_DOTENV = Join-Path `$ConfigDir ".env"
  }
  `$shell = if (`$env:SYMPHONY_TRELLO_WRAPPER_SHELL -eq "cmd") { "cmd" } else { "powershell" }
  `$commandPath = if (`$env:SYMPHONY_TRELLO_WRAPPER_COMMAND) { `$env:SYMPHONY_TRELLO_WRAPPER_COMMAND } else { `$PSCommandPath }
  `$env:SYMPHONY_TRELLO_COMMAND = `$commandPath
  if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    [Console]::Error.WriteLine("Symphony for Trello needs Java 25+ on PATH, but no java command was found.")
    [Console]::Error.WriteLine("Install a Java 25+ JDK or rerun the installer, then try again:")
    [Console]::Error.WriteLine("  `$commandPath")
    exit 2
  }
  & java "-Dsymphony.trello.app.home=`$AppHome" "-Dsymphony.trello.config.dir=`$ConfigDir" "-Dsymphony.trello.installed.app.home=`$InstalledAppHome" "-Dsymphony.trello.installed.config.dir=`$InstalledConfigDir" "-Dsymphony.trello.installed.workspace.root=`$InstalledWorkspaceRoot" "-Dsymphony.trello.installed.state.home=`$InstalledStateHome" "-Dsymphony.trello.shell=`$shell" "-Dsymphony.trello.command=`$commandPath" -cp `$classpath ch.fmartin.symphony.trello.setup.TrelloBoardSetupMain @CliArgs
  exit `$LASTEXITCODE
}
Invoke-SetupCli `$ScriptArgs
"@ | Set-Content -Encoding UTF8 "$BinDir\symphony-trello.ps1"
@"
@echo off
set "SYMPHONY_TRELLO_WRAPPER_SHELL=cmd"
set "SYMPHONY_TRELLO_WRAPPER_COMMAND=%~f0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0symphony-trello.ps1" %*
"@ | Set-Content -Encoding ASCII "$BinDir\symphony-trello.cmd"
}
Write-Host "  OK  Command installed: $BinDir\symphony-trello.ps1"
Write-InstallContext

Offer-PathSetup

if (-not $NoOnboard) {
  Write-Host
  if ($RestartManagedWorkers) {
    Write-Host "Restarting managed workers after update..."
  }
  Write-Host "Starting setup..."
  if ($DryRun) {
    Write-Host "  WOULD run: $BinDir\symphony-trello.ps1 setup-local"
    Start-ManagedWorkers
  } else {
    Invoke-Step "$BinDir\symphony-trello.ps1 setup-local" { & "$BinDir\symphony-trello.ps1" setup-local }
    Start-ManagedWorkers
  }
} elseif ($RestartManagedWorkers) {
  Write-Host
  Write-Host "Restarting managed workers after update..."
  Start-ManagedWorkers
}
