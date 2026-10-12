param([string]$BaseUrl = 'http://localhost:8084', [string]$IncidentId)
$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')
if (-not $IncidentId) {
    $incident = & (Join-Path $PSScriptRoot 'demo-investigation.ps1') -BaseUrl $base -Repetitions 2
    $IncidentId = $incident.IncidentId
}
$run = Invoke-RestMethod -Method Post "$base/v1/incidents/$IncidentId/remediations/IDEMPOTENCY_LEDGER/verify" -TimeoutSec 15
Write-Host "Verification $($run.id) for incident $IncidentId"
$deadline = [DateTimeOffset]::UtcNow.AddMinutes(16)
do {
    $run = Invoke-RestMethod "$base/v1/verifications/$($run.id)" -TimeoutSec 15
    if ($run.status -notin @('QUEUED','RUNNING')) { break }
    Start-Sleep -Seconds 2
} while ([DateTimeOffset]::UtcNow -lt $deadline)
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$artifacts = Join-Path $workspace 'artifacts'
$null = New-Item -ItemType Directory -Path $artifacts -Force
$artifact = Join-Path $artifacts "verification-$($run.id).json"
$run | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $artifact -Encoding UTF8
if ($run.status -ne 'VERIFIED_IN_SANDBOX') { throw "Verification ended $($run.status); inspect $artifact" }
$certificate = Invoke-RestMethod "$base/v1/verifications/$($run.id)/certificate" -TimeoutSec 15
if ($certificate.status -notin @('CERTIFIED','NOT_CERTIFIED','STALE')) { throw 'Unknown certification status' }
[pscustomobject]@{ IncidentId = $IncidentId; VerificationId = $run.id; Status = $run.status; Checks = $run.checks.Count; Certificate = $certificate.status; Artifact = $artifact }
