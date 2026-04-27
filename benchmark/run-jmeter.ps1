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
$resultsDir = ".\benchmark\results\$Strategy-$(Get-Date -Format yyyyMMdd-HHmmss)"
New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null
$uri = [Uri]$BaseUrl

.\benchmark\smoke-local.ps1 -BaseUrl $BaseUrl -TicketItemId $TicketItemId -Stock $Stock -YearMonth $YearMonth -Strategy $Strategy | Out-File "$resultsDir\reset-and-smoke.txt"

$loops = [Math]::Ceiling($TotalRequests / $Threads)
& $JMeterBin -n -t ".\benchmark\flash-sale-order.jmx" -l "$resultsDir\results.jtl" -e -o "$resultsDir\html" `
  -JbaseUrl=$BaseUrl `
  -Jprotocol=$($uri.Scheme) `
  -Jhost=$($uri.Host) `
  -Jport=$($uri.Port) `
  -JticketItemId=$TicketItemId `
  -Jstrategy=$Strategy `
  -Jthreads=$Threads `
  -Jloops=$loops

Invoke-RestMethod -Method Get -Uri "$BaseUrl/admin/benchmarks/consistency?ticketItemId=$TicketItemId&yearMonth=$YearMonth" |
  ConvertTo-Json -Depth 5 |
  Out-File "$resultsDir\consistency.json"

Write-Host "Raw results: $resultsDir"
Write-Host "Paste one README row after reading results.jtl/html report and consistency.json."
