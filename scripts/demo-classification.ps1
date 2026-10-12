param([string]$BaseUrl = 'http://localhost:8084')
$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$artifacts = Join-Path $workspace 'artifacts'
$null = New-Item -ItemType Directory -Path $artifacts -Force

function Complete-Decision([string]$IncidentId, [string]$Expected) {
    $classification = Invoke-RestMethod "$base/v1/incidents/$IncidentId/classification" -TimeoutSec 15
    $plan = Invoke-RestMethod -Method Post "$base/v1/incidents/$IncidentId/remediations" -TimeoutSec 15
    if ($classification.decision.category -ne $Expected) { throw "Expected $Expected, got $($classification.decision.category)" }
    if ($Expected -ne 'ARCHITECTURE_DEFECT' -and $plan.plan.candidates.Count -ne 0) { throw 'No-change outcome unexpectedly proposed a fix' }
    $artifact = Join-Path $artifacts "decision-$IncidentId.json"
    @{ classification = $classification; remediations = $plan } | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $artifact -Encoding UTF8
    [pscustomobject]@{ IncidentId = $IncidentId; Classification = $classification.decision.category; Action = $classification.decision.action; Recommended = $plan.plan.recommendedCandidate; Artifact = $artifact }
}

$payment = & (Join-Path $PSScriptRoot 'demo-investigation.ps1') -BaseUrl $base -Repetitions 2
Complete-Decision $payment.IncidentId 'ARCHITECTURE_DEFECT'

foreach ($signature in @('ACCESS_DENIED', 'INTERMITTENT_TIMEOUT')) {
    $now = [DateTimeOffset]::UtcNow.ToString('o')
    $request = @{ title = "Synthetic $signature"; startedAt = $now; failureSignature = $signature } | ConvertTo-Json
    $incident = Invoke-RestMethod -Method Post "$base/v1/incidents" -ContentType 'application/json' -Body $request -TimeoutSec 15
    $event = @{
        eventId = [guid]::NewGuid().ToString(); timestamp = $now; service = 'synthetic-resource-service'
        eventType = 'ACCESS_DENIED'; traceId = 'synthetic-trace'; requestId = 'synthetic-request'
        resource = 'resource-123'; operation = 'GET'; result = 'DENIED'
        metadata = @{ serviceHealthy = 'true'; resourceExists = 'true'; policyVersion = 'demo-read-policy-v1'; hasReadPermission = 'false' }
    }
    $expected = 'EXPECTED_BEHAVIOR'
    if ($signature -eq 'INTERMITTENT_TIMEOUT') {
        $event.eventType = 'REQUEST_TIMEOUT'; $event.result = 'TIMEOUT'; $event.metadata = @{}
        $expected = 'UNKNOWN'
    }
    $body = @{ events = @($event) } | ConvertTo-Json -Depth 8
    $null = Invoke-RestMethod -Method Post "$base/v1/incidents/$($incident.id)/evidence" -ContentType 'application/json' -Body $body -TimeoutSec 15
    $null = Invoke-RestMethod -Method Post "$base/v1/incidents/$($incident.id)/investigate" -ContentType 'application/json' -Body '{"repetitions":2}' -TimeoutSec 15
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes(2)
    do {
        $job = Invoke-RestMethod "$base/v1/incidents/$($incident.id)/investigation" -TimeoutSec 15
        if ($job.status -eq 'COMPLETED') { break }
        Start-Sleep -Seconds 1
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    if ($job.status -ne 'COMPLETED') { throw 'Investigation did not finish within the demo budget' }
    Complete-Decision $incident.id $expected
}
