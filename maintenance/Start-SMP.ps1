# Java supervisor waits for the actual child exit. Never uses Stop-Process/taskkill.
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ServerRoot,
      [Parameter(Mandatory=$true)][string]$JavaExe)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $ServerRoot).Path
$java = (Resolve-Path -LiteralPath $JavaExe).Path
$core = Join-Path $root 'plugins\PolderCore.jar'
if (!(Test-Path -LiteralPath $core -PathType Leaf)) { throw 'PolderCore.jar missing' }
& $java '-cp' $core 'nl.poldersmp.core.nether.NetherMaintenance' 'run' $root
if ($LASTEXITCODE -ne 0) { throw "SMP supervisor refused/failed ($LASTEXITCODE). Inspect maintenance log; do not bypass recovery." }
