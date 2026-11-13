[CmdletBinding()]
param([Parameter(Mandatory=$true)][ValidateSet('approve','apply','recover','status','prune')][string]$Action,
      [Parameter(Mandatory=$true)][string]$ServerRoot,
      [Parameter(Mandatory=$true)][string]$JavaExe,
      [string]$ServerJar,
      [string]$OperationId)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $ServerRoot).Path
$java = (Resolve-Path -LiteralPath $JavaExe).Path
$core = Join-Path $root 'plugins\PolderCore.jar'
$invokeArgs = @('-cp', $core, 'nl.poldersmp.core.nether.NetherMaintenance', $Action, $root)
if ($Action -eq 'approve') {
    if (!$ServerJar) { throw '-ServerJar is required for approval' }
    $invokeArgs += @($java, (Resolve-Path -LiteralPath $ServerJar).Path)
}
if ($Action -eq 'recover') {
    if (!$OperationId) { throw '-OperationId is required; inspect status first' }
    $invokeArgs += $OperationId
}
& $java @invokeArgs
if ($LASTEXITCODE -ne 0) { throw "Maintenance refused/failed ($LASTEXITCODE). Existing backups are retained." }
