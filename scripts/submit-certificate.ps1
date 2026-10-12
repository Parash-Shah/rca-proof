param([Parameter(Mandatory=$true)][guid]$VerificationId,
      [Parameter(Mandatory=$true)][string]$AttestationFile,
      [string]$BaseUrl = 'http://localhost:8084')
$ErrorActionPreference = 'Stop'
$evidence = Get-Content -LiteralPath $AttestationFile -Raw
$certificate = Invoke-RestMethod -Method Post -Uri "$($BaseUrl.TrimEnd('/'))/v1/verifications/$VerificationId/certificate" -ContentType 'application/json' -Body $evidence -TimeoutSec 30
$certificate | ConvertTo-Json -Depth 30
if ($certificate.status -ne 'CERTIFIED') { throw 'Certification was not granted; inspect the returned blockers' }
