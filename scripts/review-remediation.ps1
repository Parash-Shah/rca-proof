param(
    [Parameter(Mandatory = $true)][guid]$VerificationId,
    [string]$BaseUrl = 'http://localhost:8084',
    [switch]$Publish
)
$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')
$review = Invoke-RestMethod -Method Post "$base/v1/verifications/$VerificationId/review" -TimeoutSec 30
$artifacts = Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..')).Path 'artifacts'
$null = New-Item -ItemType Directory -Path $artifacts -Force
$preview = Join-Path $artifacts "review-$VerificationId.md"
$review.body | Set-Content -LiteralPath $preview -Encoding UTF8
if ($Publish) {
    if (-not $env:RCAPROOF_REVIEW_KEY) { throw 'Set RCAPROOF_REVIEW_KEY to the local API publication key before using -Publish.' }
    $review = Invoke-RestMethod -Method Post "$base/v1/verifications/$VerificationId/pull-request" -Headers @{'X-RCAProof-Review-Key' = $env:RCAPROOF_REVIEW_KEY} -TimeoutSec 180
    $review | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $artifacts "review-$VerificationId.json") -Encoding UTF8
    if ($review.status -notin @('OPEN','CLOSED','MERGED_BY_HUMAN')) { throw "Review ended $($review.status): $($review.detail). Retry reconciles the existing branch and PR." }
}
[pscustomobject]@{ Status = $review.status; Branch = $review.branch; Commit = $review.headSha; PullRequest = $review.pullUrl; Preview = $preview }
