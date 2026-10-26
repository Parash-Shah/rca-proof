param([switch]$Fixed, [switch]$SkipBuild, [string]$RuntimeOverride)
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$names = @('RCAPROOF_DB_PORT','RCAPROOF_API_PORT','RCAPROOF_HTTP_LAB_PORT','RCAPROOF_AWS_PORT','RCAPROOF_ORDER_PORT','RCAPROOF_PROVIDER_PORT','LAB_CRASH_AFTER_CHARGE','LAB_PROVIDER_IDEMPOTENCY')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$composeArgs = @('compose','-p','rcaproof-telemetry','-f','docker-compose.yml','-f','docker-compose.telemetry.yml')
if ($RuntimeOverride) { $composeArgs += @('-f', (Resolve-Path -LiteralPath $RuntimeOverride).Path) }
function Compose {
    & docker @composeArgs @args
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed ($LASTEXITCODE)" }
}
function Wait-Http([string]$Uri) {
    $deadline = (Get-Date).AddSeconds(120)
    do {
        try { return Invoke-RestMethod -Uri $Uri -TimeoutSec 3 }
        catch { Start-Sleep -Milliseconds 500 }
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Uri"
}
function Wait-Proof([string]$StreamPath) {
    $deadline = (Get-Date).AddSeconds(240)
    do {
        try { $view = Invoke-RestMethod "http://localhost:18084/v1/telemetry/$StreamPath" -TimeoutSec 5 }
        catch { Start-Sleep -Seconds 1; continue }
        if ($view.incidentId -and $view.status -in @('PROVEN','INCONCLUSIVE')) {
            if ($view.status -ne 'PROVEN') { throw "Automatic investigation abstained: $($view.incidentId)" }
            return $view
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Automatic investigation timed out for $StreamPath"
}
Push-Location $workspace
try {
    $env:RCAPROOF_DB_PORT='15433'; $env:RCAPROOF_API_PORT='18084'; $env:RCAPROOF_HTTP_LAB_PORT='18085'
    $env:RCAPROOF_AWS_PORT='14566'; $env:RCAPROOF_ORDER_PORT='18081'; $env:RCAPROOF_PROVIDER_PORT='18082'
    $env:LAB_PROVIDER_IDEMPOTENCY = if ($Fixed) { 'true' } else { 'false' }
    $env:LAB_CRASH_AFTER_CHARGE='true'
    # Prevent an existing worker consuming the new order before the crash configuration is applied.
    Compose stop payment-worker
    $build = if ($SkipBuild) { '--no-build' } else { '--build' }
    Compose up -d $build rcaproof-api order-service external-payment-mock
    $null = Wait-Http 'http://localhost:18084/actuator/health'
    $null = Wait-Http 'http://localhost:18081/actuator/health'
    Compose up -d $build --force-recreate payment-worker
    $orderId='telemetry-'+[guid]::NewGuid().ToString('N')
    $body=@{orderId=$orderId;customerId='synthetic-customer';amount=129.95}|ConvertTo-Json
    $null=Invoke-RestMethod -Method Post -Uri 'http://localhost:18081/orders' -ContentType 'application/json' -Body $body -TimeoutSec 15
    $deadline=(Get-Date).AddSeconds(120)
    do {
        $workerId=Compose ps -a -q payment-worker
        $state=& docker inspect --format '{{.State.Status}}:{{.State.ExitCode}}' $workerId
        if ($LASTEXITCODE -ne 0) { throw 'Could not inspect worker' }
        if ($state -eq 'exited:86') { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    if ($state -ne 'exited:86') { throw "Expected actual worker crash (86), got $state" }
    $env:LAB_CRASH_AFTER_CHARGE='false'
    Compose up -d --no-build --no-deps --force-recreate payment-worker
    $deadline=(Get-Date).AddSeconds(120)
    $view=$null
    do {
        try { $view=Invoke-RestMethod "http://localhost:18084/v1/telemetry/payment/$orderId" -TimeoutSec 5 }
        catch { Start-Sleep -Seconds 1; continue }
        if ($view.eventCount -ge 7) { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    if (-not $view -or $view.eventCount -lt 7) { throw 'Recovery telemetry is incomplete' }
    $effects=Invoke-RestMethod "http://localhost:18082/effects?orderId=$orderId" -TimeoutSec 5
    $expected=if ($Fixed) {1} else {2}
    if ($effects.Count -ne $expected) { throw "Expected $expected charge effects, got $($effects.Count)" }
    if ($Fixed) {
        if ($view.status -ne 'COLLECTING' -or $view.incidentId) { throw 'Same-charge replay incorrectly detected as duplicate payment' }
    } else { $view=Wait-Proof "payment/$orderId" }
    $healthy=Invoke-RestMethod -Method Post -Uri 'http://localhost:18084/v1/labs/http-requests' -ContentType 'application/json' -Body '{"mode":"HEALTHY","attempts":6}' -TimeoutSec 30
    if ($healthy.status -ne 'COLLECTING' -or $healthy.incidentId) { throw 'Healthy HTTP request incorrectly detected as retry amplification' }
    $retry=Invoke-RestMethod -Method Post -Uri 'http://localhost:18084/v1/labs/http-requests' -ContentType 'application/json' -Body '{"mode":"HTTP_500","attempts":6}' -TimeoutSec 30
    $retry=Wait-Proof ($retry.streamKey.Replace(':','/'))
    $result=[pscustomobject]@{orderId=$orderId;fixed=[bool]$Fixed;paymentEffects=$effects.Count;workerCrashExitCode=86;payment=$view;healthyHttp=$healthy;retry=$retry}
    New-Item -ItemType Directory -Force artifacts/telemetry | Out-Null
    $result | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 "artifacts/telemetry/demo-$orderId.json"
    $result
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
    Pop-Location
}
