param(
  [string]$BaseUrl = "http://localhost:1122",
  [long]$TicketItemId = 4,
  [int]$Stock = 1000,
  [string]$YearMonth = (Get-Date -Format "yyyyMM"),
  [string]$Strategy = "REDIS_LUA_WITH_COMPENSATION",
  [int]$Threads = 100,
  [int]$TotalRequests = 5000,
  [string]$JMeterBin = ".\benchmark\jmeter\bin\jmeter.bat"
)

$ErrorActionPreference = "Stop"
$runId = "$Strategy-$(Get-Date -Format yyyyMMdd-HHmmss)"
$resultsDir = ".\benchmark\results\$runId"
New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null
$uri = [Uri]$BaseUrl
$headers = @{ "Content-Type" = "application/json" }

$resetBody = @{
  ticketItemId = $TicketItemId
  stock = $Stock
  yearMonth = $YearMonth
} | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri "$BaseUrl/admin/benchmarks/reset" -Headers $headers -Body $resetBody |
  ConvertTo-Json -Depth 5 |
  Out-File "$resultsDir\reset.json"

Invoke-RestMethod -Method Post -Uri "$BaseUrl/admin/tickets/$TicketItemId/stock/warmup" |
  ConvertTo-Json -Depth 5 |
  Out-File "$resultsDir\warmup.json"

$loops = [Math]::Ceiling($TotalRequests / $Threads)
$actualRequests = $loops * $Threads
& $JMeterBin -n -t ".\benchmark\flash-sale-order.jmx" -l "$resultsDir\results.jtl" -e -o "$resultsDir\html" `
  "-JbaseUrl=$BaseUrl" `
  "-Jprotocol=$($uri.Scheme)" `
  "-Jhost=$($uri.Host)" `
  "-Jport=$($uri.Port)" `
  "-JticketItemId=$TicketItemId" `
  "-Jstrategy=$Strategy" `
  "-Jthreads=$Threads" `
  "-Jloops=$loops"

if ($LASTEXITCODE -ne 0 -or -not (Test-Path "$resultsDir\results.jtl")) {
  throw "JMeter failed; see console output and $resultsDir"
}

$consistency = Invoke-RestMethod -Method Get -Uri "$BaseUrl/admin/benchmarks/consistency?ticketItemId=$TicketItemId&yearMonth=$YearMonth"
$consistency | ConvertTo-Json -Depth 5 | Out-File "$resultsDir\consistency.json"

$samples = Import-Csv "$resultsDir\results.jtl"
$count = $samples.Count
$elapsedValues = $samples | ForEach-Object { [int]$_.elapsed } | Sort-Object
$avgMs = [Math]::Round(($elapsedValues | Measure-Object -Average).Average, 2)
$p95Index = [Math]::Min($elapsedValues.Count - 1, [Math]::Ceiling($elapsedValues.Count * 0.95) - 1)
$p99Index = [Math]::Min($elapsedValues.Count - 1, [Math]::Ceiling($elapsedValues.Count * 0.99) - 1)
$p95 = $elapsedValues[$p95Index]
$p99 = $elapsedValues[$p99Index]
$startMs = ($samples | ForEach-Object { [long]$_.timeStamp } | Measure-Object -Minimum).Minimum
$endMs = ($samples | ForEach-Object { [long]$_.timeStamp + [long]$_.elapsed } | Measure-Object -Maximum).Maximum
$durationSeconds = [Math]::Max(0.001, ($endMs - $startMs) / 1000.0)
$throughput = [Math]::Round($count / $durationSeconds, 2)
$successOrders = [int]$consistency.result.dbOrderCount
$failedOrders = [Math]::Max(0, $actualRequests - $successOrders)
$machine = $env:COMPUTERNAME
$date = Get-Date -Format "yyyy-MM-dd"
$oversoldCount = [int]$consistency.result.oversoldCount
$redisStockAfter = [int]$consistency.result.redisStockAfter
$dbStockAfter = [int]$consistency.result.dbStockAfter
$dbOrderCount = [long]$consistency.result.dbOrderCount
$redisDbInconsistencyCount = [int]$consistency.result.redisDbInconsistencyCount
$status = if ($oversoldCount -eq 0 -and ($Strategy -ne "REDIS_LUA_WITH_COMPENSATION" -or $redisDbInconsistencyCount -eq 0)) { "PASS" } else { "CHECK" }

$summary = [ordered]@{
  runId = $runId
  date = $date
  machine = $machine
  strategy = $Strategy
  totalRequests = $actualRequests
  concurrency = $Threads
  throughput = $throughput
  averageMs = $avgMs
  p95Ms = $p95
  p99Ms = $p99
  successOrders = $successOrders
  failedOrders = $failedOrders
  oversoldCount = $oversoldCount
  redisStockAfter = $redisStockAfter
  dbStockAfter = $dbStockAfter
  dbOrderCount = $dbOrderCount
  redisDbInconsistencyCount = $redisDbInconsistencyCount
  status = $status
}

$run = [ordered]@{
  summary = $summary
  reset = Get-Content "$resultsDir\reset.json" -Raw | ConvertFrom-Json
  warmup = Get-Content "$resultsDir\warmup.json" -Raw | ConvertFrom-Json
  consistency = $consistency
  artifacts = [ordered]@{
    jtl = "results.jtl"
    html = "html/index.html"
    summary = "summary-row.md"
  }
}

$run | ConvertTo-Json -Depth 10 | Out-File "$resultsDir\run.json" -Encoding utf8

$row = "| $date | $machine | ``$Strategy`` | $actualRequests | $Threads | $throughput | $avgMs | $p95 | $p99 | $successOrders | $failedOrders | $($consistency.result.oversoldCount) | $($consistency.result.redisStockAfter) | $($consistency.result.dbStockAfter) | $($consistency.result.dbOrderCount) | $($consistency.result.redisDbInconsistencyCount) |"
$row | Out-File "$resultsDir\summary-row.md"

Write-Host "Raw results: $resultsDir"
Write-Host "API run manifest: $resultsDir\run.json"
Write-Host $row
