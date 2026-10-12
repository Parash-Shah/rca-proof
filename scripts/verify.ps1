param([switch]$UnitOnly)
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$phase = if ($UnitOnly) { 'test' } else { 'verify' }
if (-not $UnitOnly) {
    docker run --rm --entrypoint python3 -v "${workspace}:/workspace" `
        -w /workspace localstack/localstack:4.7.0 -B scripts/test_certification_tools.py
    if ($LASTEXITCODE -ne 0) { throw "Certification tooling tests failed ($LASTEXITCODE)" }
}
docker run --rm -v "${workspace}:/workspace" -v rcaproof-maven-cache:/root/.m2 `
    -v /var/run/docker.sock:/var/run/docker.sock `
    -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal `
    -w /workspace maven:3.9.11-eclipse-temurin-21 mvn -B -ntp $phase
if ($LASTEXITCODE -ne 0) { throw "Maven $phase failed ($LASTEXITCODE)" }
