param([ValidateRange(2,20)][int]$Repetitions = 2, [string]$BaseUrl = 'http://localhost:8084')
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$base = $BaseUrl.TrimEnd('/')
$started = [DateTimeOffset]::UtcNow.AddMinutes(-1)
$orderId = 'incident-demo-' + [guid]::NewGuid().ToString('N')
$create = @{
    title = 'Synthetic duplicate-payment incident'
    startedAt = $started.ToString('o')
    failureSignature = 'DUPLICATE_PAYMENT'
    order = @{ orderId = $orderId; customerId = 'synthetic-customer'; amount = 129.95 }
} | ConvertTo-Json -Depth 8
$incident = Invoke-RestMethod -Method Post -Uri "$base/v1/incidents" -ContentType 'application/json' -Body $create -TimeoutSec 15
$kinds = @('ORDER_CREATED', 'MESSAGE_RECEIVED', 'PAYMENT_SUCCEEDED', 'WORKER_CRASHED', 'MESSAGE_RECEIVED', 'PAYMENT_SUCCEEDED')
$events = for ($i = 0; $i -lt $kinds.Count; $i++) {
    $metadata = @{}
    if ($kinds[$i] -eq 'PAYMENT_SUCCEEDED') { $metadata = @{ chargeId = "synthetic-charge-$i" } }
    @{
        eventId = [guid]::NewGuid().ToString()
        timestamp = $started.AddSeconds($i).ToString('o')
        service = 'synthetic-payment-lab'
        eventType = $kinds[$i]
        orderId = $orderId
        traceId = "trace-$orderId"
        messageId = "message-$orderId"
        result = 'SUCCESS'
        metadata = $metadata
    }
}
$evidence = @{ events = @($events) } | ConvertTo-Json -Depth 8
$null = Invoke-RestMethod -Method Post -Uri "$base/v1/incidents/$($incident.id)/evidence" -ContentType 'application/json' -Body $evidence -TimeoutSec 15
$job = Invoke-RestMethod -Method Post -Uri "$base/v1/incidents/$($incident.id)/investigate" -ContentType 'application/json' -Body (@{ repetitions = $Repetitions } | ConvertTo-Json) -TimeoutSec 15
Write-Host "Incident $($incident.id); investigation $($job.id)"
$deadline = [DateTimeOffset]::UtcNow.AddMinutes(16)
do {
    $job = Invoke-RestMethod "$base/v1/incidents/$($incident.id)/investigation" -TimeoutSec 15
    if ($job.status -eq 'COMPLETED') { break }
    Start-Sleep -Seconds 2
} while ([DateTimeOffset]::UtcNow -lt $deadline)
if ($job.status -ne 'COMPLETED') { throw 'Polling timed out; inspect the persisted investigation through the API' }
$proof = Invoke-RestMethod "$base/v1/incidents/$($incident.id)/proof" -TimeoutSec 15
$artifacts = Join-Path $workspace 'artifacts'
$null = New-Item -ItemType Directory -Path $artifacts -Force
$path = Join-Path $artifacts "incident-$($incident.id).json"
$proof | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $path -Encoding UTF8
[pscustomobject]@{ IncidentId = $incident.id; State = $proof.report.state; Trials = $proof.report.trials.Count; Reason = $proof.reason; Artifact = $path }
