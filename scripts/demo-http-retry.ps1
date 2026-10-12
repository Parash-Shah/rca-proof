param([string]$BaseUrl = 'http://localhost:8084', [string]$AttestationFile = '')
$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')
$incident = Invoke-RestMethod -Method Post "$base/v1/labs/retry-storm" -TimeoutSec 60
$job = Invoke-RestMethod -Method Post "$base/v1/incidents/$($incident.id)/investigate" -ContentType 'application/json' -Body '{"repetitions":2}' -TimeoutSec 15
Write-Host "Measured HTTP retry incident $($incident.id); investigation $($job.id)"
$deadline = [DateTimeOffset]::UtcNow.AddMinutes(16)
do {
    $job = Invoke-RestMethod "$base/v1/incidents/$($incident.id)/investigation" -TimeoutSec 15
    if ($job.status -eq 'COMPLETED') { break }
    Start-Sleep -Seconds 2
} while ([DateTimeOffset]::UtcNow -lt $deadline)
if ($job.status -ne 'COMPLETED') { throw 'HTTP investigation polling timed out' }
$proof = Invoke-RestMethod "$base/v1/incidents/$($incident.id)/proof" -TimeoutSec 15
if ($proof.report.state -ne 'PROVEN') { throw "HTTP investigation ended $($proof.report.state): $($proof.reason)" }
$plan = Invoke-RestMethod -Method Post "$base/v1/incidents/$($incident.id)/remediations" -TimeoutSec 15
if ($plan.plan.recommendedCandidate -ne 'BOUNDED_RETRY') { throw 'No eligible bounded retry candidate' }
$run = Invoke-RestMethod -Method Post "$base/v1/incidents/$($incident.id)/remediations/BOUNDED_RETRY/verify" -TimeoutSec 15
$deadline = [DateTimeOffset]::UtcNow.AddMinutes(16)
do {
    $run = Invoke-RestMethod "$base/v1/verifications/$($run.id)" -TimeoutSec 15
    if ($run.status -notin @('QUEUED','RUNNING')) { break }
    Start-Sleep -Seconds 2
} while ([DateTimeOffset]::UtcNow -lt $deadline)
$artifacts = Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..')).Path 'artifacts'
$null = New-Item -ItemType Directory -Path $artifacts -Force
$artifact = Join-Path $artifacts "http-retry-$($incident.id).json"
@{ incident = $incident; proof = $proof; plan = $plan; verification = $run } | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $artifact -Encoding UTF8
if ($run.status -ne 'VERIFIED_IN_SANDBOX' -or $run.checks.Count -ne 16 -or $run.report.suiteVersion -ne 'http-retry-verification-v2') { throw "Verification ended $($run.status); inspect $artifact" }
$certificate = Invoke-RestMethod "$base/v1/verifications/$($run.id)/certificate" -TimeoutSec 15
if ($certificate.status -ne 'NOT_CERTIFIED') { throw 'Fresh verification must require independently submitted evidence' }
$reviewStatus = 'NOT_PREPARED'
if ($AttestationFile) {
    $envelope = Get-Content -LiteralPath $AttestationFile -Raw
    $certificate = Invoke-RestMethod -Method Post "$base/v1/verifications/$($run.id)/certificate" -ContentType 'application/json' -Body $envelope -TimeoutSec 30
    $certificate | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $artifacts "certificate-$($run.id).json") -Encoding UTF8
    if ($certificate.status -ne 'CERTIFIED') { throw 'Certification was not granted; inspect the saved certificate blockers' }
    $review = Invoke-RestMethod -Method Post "$base/v1/verifications/$($run.id)/review" -TimeoutSec 30
    $review.body | Set-Content -LiteralPath (Join-Path $artifacts "review-$($run.id).md") -Encoding UTF8
    $reviewStatus = $review.status
    if ($reviewStatus -ne 'PREPARED') { throw "Review preparation ended $reviewStatus" }
}
[pscustomobject]@{ IncidentId = $incident.id; VerificationId = $run.id; Proof = $proof.report.state; MinimalCausalSet = $proof.report.minimalCausalSets; Verification = $run.status; Checks = $run.checks.Count; Certificate = $certificate.status; Review = $reviewStatus; Artifact = $artifact }
