[CmdletBinding()]
param(
    [string]$EnvironmentFile = "/etc/stageaccord/config/production.env",
    [switch]$SchemaOnly
)

$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$Arguments = @(
    (Join-Path $ProjectRoot "deploy/scripts/preflight_production.py"),
    "--config",
    $EnvironmentFile
)

if ($SchemaOnly) {
    $Arguments += "--schema-only"
}

Push-Location $ProjectRoot
try {
    & python @Arguments
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
finally {
    Pop-Location
}
