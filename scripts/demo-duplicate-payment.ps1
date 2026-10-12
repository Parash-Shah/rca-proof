param([switch]$Fixed)
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $workspace
$previousCrash = $env:LAB_CRASH_AFTER_CHARGE
$previousIdempotency = $env:LAB_PROVIDER_IDEMPOTENCY
function Invoke-Compose {
    docker compose @args
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed ($LASTEXITCODE)" }
}
function Wait-Http([string]$Uri) {
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        try { return Invoke-RestMethod -Uri $Uri -TimeoutSec 3 }
        catch { Start-Sleep -Milliseconds 500 }
    }
    throw "Timed out waiting for $Uri"
}
try {
    $env:LAB_PROVIDER_IDEMPOTENCY = if ($Fixed) { 'true' } else { 'false' }
    $env:LAB_CRASH_AFTER_CHARGE = 'true'
    Invoke-Compose up -d --build
    $queueUrl = docker compose exec -T localstack awslocal sqs get-queue-url --queue-name payment-orders --query QueueUrl --output text
    if ($LASTEXITCODE -ne 0) { throw 'Could not resolve demo queue' }
    docker compose exec -T localstack awslocal sqs set-queue-attributes --queue-url $queueUrl --attributes VisibilityTimeout=60
    if ($LASTEXITCODE -ne 0) { throw 'Could not set demo visibility timeout' }
    $null = Wait-Http 'http://localhost:8081/actuator/health'
    $orderId = 'demo-' + [guid]::NewGuid().ToString('N')
    $body = @{ orderId = $orderId; customerId = 'synthetic-customer'; amount = 129.95 } | ConvertTo-Json
    $null = Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/orders' -ContentType 'application/json' -Body $body
    $deadline = (Get-Date).AddSeconds(120)
    do {
        $workerId = docker compose ps -a -q payment-worker
        if ($LASTEXITCODE -ne 0) { throw 'Could not inspect worker' }
        $state = docker inspect --format '{{.State.Status}}:{{.State.ExitCode}}' $workerId
        if ($state -eq 'exited:86') { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    if ($state -ne 'exited:86') { throw "Expected injected crash (86), got $state" }
    $env:LAB_CRASH_AFTER_CHARGE = 'false'
    Invoke-Compose up -d --no-deps --force-recreate payment-worker
    $deadline = (Get-Date).AddSeconds(120)
    do {
        $payment = docker compose exec -T localstack awslocal dynamodb get-item --table-name payments --key "orderId={S=$orderId}" --consistent-read
        if ($LASTEXITCODE -eq 0 -and ($payment | ConvertFrom-Json).Item) { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    if (-not ($payment | ConvertFrom-Json).Item) { throw 'Recovery never persisted a payment' }
    $effects = Invoke-RestMethod "http://localhost:8082/effects?orderId=$orderId"
    $expected = if ($Fixed) { 1 } else { 2 }
    if ($effects.Count -ne $expected) { throw "Expected $expected effects, got $($effects.Count)" }
    [pscustomobject]@{ orderId = $orderId; providerIdempotency = [bool]$Fixed; paymentEffects = $effects.Count; workerCrashExitCode = 86 }
} finally {
    $env:LAB_CRASH_AFTER_CHARGE = $previousCrash
    $env:LAB_PROVIDER_IDEMPOTENCY = $previousIdempotency
    Pop-Location
}
