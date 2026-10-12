param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    $composeArgs = @('compose','--profile','observability','up','-d')
    if (-not $SkipBuild) { $composeArgs += '--build' }
    & docker @composeArgs rcaproof-api prometheus grafana
    if ($LASTEXITCODE -ne 0) { throw 'Lab startup failed; inspect docker compose logs' }
    & docker compose --profile observability run --rm release-check
    if ($LASTEXITCODE -ne 0) { throw 'Release smoke check failed; inspect artifacts/release-check-*.json' }
    Write-Host 'Demo passed. Default URLs: API http://localhost:8084, Grafana http://localhost:3001/d/rcaproof, Prometheus http://localhost:9091'
} finally { Pop-Location }
