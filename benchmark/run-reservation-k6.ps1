param(
  [string]$BaseUrl = "http://localhost:1122",
  [long]$TicketItemId = 950015,
  [int]$Vus = 100,
  [int]$Iterations = 5000,
  [string]$K6Bin = "k6"
)

$ErrorActionPreference = "Stop"
$utc = (Get-Date).ToUniversalTime()
$runId = "reservation-k6-$($utc.ToString('yyyyMMdd-HHmmssZ'))"
$resultsDir = Join-Path ".\benchmark\results" $runId
$null = New-Item -ItemType Directory -Force -Path $resultsDir

if (-not (Get-Command $K6Bin -ErrorAction SilentlyContinue)) {
  throw "k6 is unavailable; no OTLP workload claim was produced."
}

$sha = (& git rev-parse HEAD).Trim()
@{
  runId = $runId
  sha = $sha
  baseUrl = $BaseUrl
  vus = $Vus
  iterations = $Iterations
  startedAtUtc = $utc.ToString("o")
  synthetic = $true
} | ConvertTo-Json | Out-File (Join-Path $resultsDir "manifest.json") -Encoding utf8
@(& $K6Bin version 2>&1) | Out-File (Join-Path $resultsDir "environment.json") -Encoding utf8

& $K6Bin run ".\benchmark\flash-sale-reservation-k6.js" `
  "--out" "json=$(Join-Path $resultsDir 'results.json')" `
  --env "BASE_URL=$BaseUrl" `
  --env "TICKET_ITEM_ID=$TicketItemId" `
  --env "VUS=$Vus" `
  --env "ITERATIONS=$Iterations"
if ($LASTEXITCODE -ne 0) {
  throw "k6 failed; see $resultsDir."
}

@{
  sha = $sha
  note = "Correctness and convergence require the same inventory/journal/outbox snapshot used by the JMeter lane."
} | ConvertTo-Json | Out-File (Join-Path $resultsDir "convergence.json") -Encoding utf8
Write-Host "k6 evidence written to $resultsDir"
