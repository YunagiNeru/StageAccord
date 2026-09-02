[CmdletBinding()]
param(
    [switch]$SchemaOnly
)

$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$Arguments = @(
    (Join-Path $ProjectRoot "deploy/scripts/preflight_production.py")
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
