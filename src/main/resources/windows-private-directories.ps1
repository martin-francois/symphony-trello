$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$request = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('@REQUEST@')) | ConvertFrom-Json
$account = [System.Security.Principal.NTAccount]::new([string]$request.owner)
$owner = $account.Translate([System.Security.Principal.SecurityIdentifier])
$security = [System.Security.AccessControl.DirectorySecurity]::new()
$security.SetOwner($owner)
$security.SetAccessRuleProtection($true, $false)
$rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
  $owner,
  [System.Security.AccessControl.FileSystemRights]::FullControl,
  ([System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [System.Security.AccessControl.InheritanceFlags]::ObjectInherit),
  [System.Security.AccessControl.PropagationFlags]::None,
  [System.Security.AccessControl.AccessControlType]::Allow
)
$security.AddAccessRule($rule)
foreach ($path in $request.paths) {
  $directory = [System.IO.DirectoryInfo]::new([string]$path)
  $directory.Create($security)
}
