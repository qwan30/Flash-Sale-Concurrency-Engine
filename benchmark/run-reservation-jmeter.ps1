param(
  [string]$BaseUrl = "http://localhost:1122",
  [long]$TicketItemId = 950015,
  [int]$Threads = 100,
  [int]$Attempts = 5000,
  [ValidateSet("REDIS_FIRST", "MYSQL_CONDITIONAL")]
  [string]$Strategy = "REDIS_FIRST",
  [ValidateSet("none", "confirm", "release")]
  [string]$TerminalAction = "none",
  [string]$FixtureResetUrl = "",
  [string]$FixtureResetToken = "",
  [int]$FixtureStock = 1000,
  [ValidateSet("healthy", "duplicate-retry", "overload", "kafka-recovery")]
  [string]$Scenario = "healthy",
  [string]$JMeterBin = ".\benchmark\jmeter\bin\jmeter.bat",
  [ValidateRange(0, 2147483647)]
  [int]$HealthyP95BaselineMs = 0
)

$ErrorActionPreference = "Stop"
$utc = (Get-Date).ToUniversalTime()
$runId = "reservation-$Scenario-$($utc.ToString('yyyyMMdd-HHmmssZ'))"
$resultsDir = Join-Path ".\benchmark\results" $runId
$uri = [Uri]$BaseUrl
$loops = [Math]::Ceiling($Attempts / $Threads)
$sha = (& git rev-parse HEAD).Trim()
$status = @(git status --porcelain=v1)
$null = New-Item -ItemType Directory -Force -Path $resultsDir

if ([string]::IsNullOrWhiteSpace($FixtureResetUrl)) {
  throw "FixtureResetUrl is required; the strategy comparison must reset and seed both durable and Redis reservation stock before JMeter starts."
}
if ([string]::IsNullOrWhiteSpace($FixtureResetToken)) {
  throw "FixtureResetToken is required; the destructive fixture reset endpoint must be token-gated."
}

$resetBody = @{
  ticketItemId = $TicketItemId
  stock = $FixtureStock
  strategy = $Strategy
  reservationFixture = $true
} | ConvertTo-Json
$resetEnvelope = Invoke-RestMethod -Method Post -Uri $FixtureResetUrl -Headers @{
  "X-Flashsale-Synthetic" = "true"
  "X-Flashsale-Fixture-Token" = $FixtureResetToken
} -ContentType "application/json" -Body $resetBody
if (($resetEnvelope.success -ne $true) -or ($null -eq $resetEnvelope.result)) {
  throw "Fixture reset did not return the expected success envelope for $TicketItemId."
}
$reset = $resetEnvelope.result
if (($reset.success -ne $true) -or
    ($reset.reservationFixtureReset -ne $true) -or
    ([long]$reset.ticketItemId -ne $TicketItemId) -or
    ([int]$reset.stock -ne $FixtureStock) -or
    ([int]$reset.reservationStockAfter -ne $FixtureStock) -or
    ([int]$reset.reservationRedisStockAfter -ne $FixtureStock) -or
    ([int]$reset.reserved -ne 0) -or
    ([int]$reset.confirmed -ne 0) -or
    ([long]$reset.fenceVersion -ne 0) -or
    ($reset.admissionState -ne "OPEN") -or
    ($reset.strategy -ne $Strategy)) {
  throw "Fixture reset did not prove a fresh durable and Redis reservation account for $TicketItemId."
}

$resetEnvelope | ConvertTo-Json -Depth 10 | Out-File (Join-Path $resultsDir "reset.json") -Encoding utf8

function Write-JsonFile([string]$Path, $Value) {
  $Value | ConvertTo-Json -Depth 12 | Out-File -LiteralPath $Path -Encoding utf8
}

$manifest = Get-Content ".\benchmark\reservation-experiment-spec.json" -Raw | ConvertFrom-Json
$manifest.threads = $Threads
$manifest.attempts = $Attempts
$manifest | Add-Member -NotePropertyName runId -NotePropertyValue $runId
$manifest | Add-Member -NotePropertyName scenario -NotePropertyValue $Scenario
$manifest | Add-Member -NotePropertyName strategy -NotePropertyValue $Strategy
$manifest | Add-Member -NotePropertyName terminalAction -NotePropertyValue $TerminalAction
$manifest | Add-Member -NotePropertyName fixtureStock -NotePropertyValue $FixtureStock
$manifest | Add-Member -NotePropertyName fixtureResetUrl -NotePropertyValue $FixtureResetUrl
$manifest | Add-Member -NotePropertyName fixtureResetTokenConfigured -NotePropertyValue $true
$manifest | Add-Member -NotePropertyName sha -NotePropertyValue $sha
$manifest | Add-Member -NotePropertyName startedAtUtc -NotePropertyValue $utc.ToString("o")
$manifest | Add-Member -NotePropertyName baseUrl -NotePropertyValue $BaseUrl -Force
$manifest | Add-Member -NotePropertyName workloadUrl -NotePropertyValue "$($BaseUrl.TrimEnd('/'))$($manifest.api)" -Force
$manifest | Add-Member -NotePropertyName ticketItemId -NotePropertyValue $TicketItemId -Force
$manifest.fixture | Add-Member -NotePropertyName ticketItemId -NotePropertyValue $TicketItemId -Force
$manifest.fixture | Add-Member -NotePropertyName stock -NotePropertyValue $FixtureStock -Force
Write-JsonFile (Join-Path $resultsDir "manifest.json") $manifest

Write-JsonFile (Join-Path $resultsDir "git.json") ([ordered]@{
  sha = $sha
  status = $status
  capturedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
})

$versions = [ordered]@{}
foreach ($tool in @("docker", "java", "mvn", "node", "npm")) {
  $command = Get-Command $tool -ErrorAction SilentlyContinue
  $versions[$tool] = if ($command) { @(& $tool --version 2>&1) -join "`n" } else { "UNAVAILABLE" }
}
$versions["jmeter"] = if (Test-Path -LiteralPath $JMeterBin) { @(& $JMeterBin --version 2>&1) -join "`n" } else { "UNAVAILABLE" }
Write-JsonFile (Join-Path $resultsDir "environment.json") ([ordered]@{
  capturedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
  baseUrl = $BaseUrl
  workloadUrl = "$($BaseUrl.TrimEnd('/'))$($manifest.api)"
  fixtureResetUrl = $FixtureResetUrl
  ticketItemId = $TicketItemId
  fixtureStock = $FixtureStock
  strategy = $Strategy
  scenario = $Scenario
  terminalAction = $TerminalAction
  computer = $env:COMPUTERNAME
  versions = $versions
})
$activation = $null
$restoration = $null
if ($Scenario -eq "kafka-recovery") {
  Write-Host "Injecting Kafka failure via Docker pause..."
  docker pause pre-event-kafka 2>&1 | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "Failed to pause pre-event-kafka container." }
  $activation = (Get-Date).ToUniversalTime().ToString("o")
  
  # Lên lịch phục hồi Kafka sau 15 giây chạy background
  $restoreJob = Start-Job -ScriptBlock {
    Start-Sleep -Seconds 15
    docker unpause pre-event-kafka 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to unpause pre-event-kafka container." }
  }
} elseif ($Scenario -eq "overload") {
  Write-Host "Simulating Overload scenario..."
  $activation = (Get-Date).ToUniversalTime().ToString("o")
}

Write-JsonFile (Join-Path $resultsDir "fault-timeline.json") ([ordered]@{
  scenario = $Scenario
  activation = $activation
  restoration = $null
  firstHealthyRequest = $null
  convergenceSeconds = $null
  note = "Populated activation timestamp. Restoration will be updated after run."
})

$jtl = Join-Path $resultsDir "results.jtl"
$html = Join-Path $resultsDir "html"
if (-not (Test-Path -LiteralPath $JMeterBin)) {
  throw "JMeter executable not found at $JMeterBin; no benchmark claim was produced."
}

& $JMeterBin -n -t ".\benchmark\flash-sale-reservation.jmx" -l $jtl -e -o $html `
  "-JbaseUrl=$BaseUrl" `
  "-Jprotocol=$($uri.Scheme)" `
  "-Jhost=$($uri.Host)" `
  "-Jport=$($uri.Port)" `
  "-JticketItemId=$TicketItemId" `
  "-Jthreads=$Threads" `
  "-Jloops=$loops" `
  "-Jscenario=$Scenario" `
  "-Jstrategy=$Strategy" `
  "-JterminalAction=$TerminalAction" `
  "-JresultsFile=$jtl"
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $jtl)) {
  throw "JMeter failed; see $resultsDir."
}

if ($Scenario -eq "kafka-recovery") {
  Wait-Job -Job $restoreJob | Out-Null
  Receive-Job -Job $restoreJob | Out-Null
  Remove-Job -Job $restoreJob
  $restoration = (Get-Date).ToUniversalTime().ToString("o")
  
  $timeline = Get-Content (Join-Path $resultsDir "fault-timeline.json") -Raw | ConvertFrom-Json
  $timeline.restoration = $restoration
  Write-JsonFile (Join-Path $resultsDir "fault-timeline.json") $timeline
} elseif ($Scenario -eq "overload") {
  $restoration = (Get-Date).ToUniversalTime().ToString("o")
  $timeline = Get-Content (Join-Path $resultsDir "fault-timeline.json") -Raw | ConvertFrom-Json
  $timeline.restoration = $restoration
  Write-JsonFile (Join-Path $resultsDir "fault-timeline.json") $timeline
}

$inventory = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/inventory/$TicketItemId"
$evidenceHeaders = @{
  "X-Flashsale-Synthetic" = "true"
  "X-Flashsale-Fixture-Token" = $FixtureResetToken
}
$evidenceUri = "$BaseUrl/admin/reservation-fixtures/evidence?ticketItemId=$TicketItemId"
$convergenceStarted = Get-Date
$evidence = $null
$converged = $false
$convergenceSeconds = $null
do {
  $evidenceEnvelope = Invoke-RestMethod -Method Get -Uri $evidenceUri -Headers $evidenceHeaders
  if (($evidenceEnvelope.success -ne $true) -or ($null -eq $evidenceEnvelope.result)) {
    throw "Reservation evidence did not return the expected success envelope for $TicketItemId."
  }
  $evidence = $evidenceEnvelope.result
  $converged = ($evidence.pendingJournal -eq 0 -and
                $evidence.pendingOutbox -eq 0 -and
                $evidence.invariantPass -eq $true -and
                $evidence.parityPass -eq $true -and
                $evidence.finalDriftUnits -eq 0)
  if ($converged) {
    $convergenceSeconds = [Math]::Round(((Get-Date) - $convergenceStarted).TotalSeconds, 3)
    break
  }
  Start-Sleep -Seconds 1
} while (((Get-Date) - $convergenceStarted).TotalSeconds -lt 30)

Write-JsonFile (Join-Path $resultsDir "consistency.json") ([ordered]@{
  capturedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
  sha = $sha
  baseUrl = $BaseUrl
  fixtureResetUrl = $FixtureResetUrl
  ticketItemId = $TicketItemId
  fixtureStock = $FixtureStock
  strategy = $Strategy
  scenario = $Scenario
  terminalAction = $TerminalAction
  inventory = $inventory
  evidence = $evidence
})
Write-JsonFile (Join-Path $resultsDir "convergence.json") ([ordered]@{
  capturedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
  sha = $sha
  baseUrl = $BaseUrl
  fixtureResetUrl = $FixtureResetUrl
  ticketItemId = $TicketItemId
  fixtureStock = $FixtureStock
  strategy = $Strategy
  scenario = $Scenario
  terminalAction = $TerminalAction
  pendingJournal = [long]$evidence.pendingJournal
  pendingOutbox = [long]$evidence.pendingOutbox
  convergenceSeconds = $convergenceSeconds
  invariantPass = $evidence.invariantPass
  parityPass = $evidence.parityPass
  finalDriftUnits = [long]$evidence.finalDriftUnits
  verdict = if ($converged) { "PASS" } else { "NO-GO" }
})
$consistencyHash = (Get-FileHash -LiteralPath (Join-Path $resultsDir "consistency.json") -Algorithm SHA256).Hash.ToLowerInvariant()
$consistencyHash | Out-File -LiteralPath (Join-Path $resultsDir "consistency.json.sha256") -Encoding ascii
$convergenceHash = (Get-FileHash -LiteralPath (Join-Path $resultsDir "convergence.json") -Algorithm SHA256).Hash.ToLowerInvariant()
$convergenceHash | Out-File -LiteralPath (Join-Path $resultsDir "convergence.json.sha256") -Encoding ascii

$samples = @(Import-Csv -LiteralPath $jtl)
$elapsed = @($samples | ForEach-Object { [int]$_.elapsed } | Sort-Object)
$count = $elapsed.Count
$percentile = {
  param([object[]]$Values, [double]$Percent)
  if ($Values.Count -eq 0) { return $null }
  return $Values[[Math]::Min($Values.Count - 1, [Math]::Ceiling($Values.Count * $Percent) - 1)]
}
$p50 = & $percentile $elapsed .50
$p95 = if ($count) { $elapsed[[Math]::Min($count - 1, [Math]::Ceiling($count * .95) - 1)] } else { 0 }
$p99 = if ($count) { $elapsed[[Math]::Min($count - 1, [Math]::Ceiling($count * .99) - 1)] } else { 0 }
$createSamples = @($samples | Where-Object { $_.label -eq "POST /api/v1/reservations" })
$createSuccessSamples = @($createSamples | Where-Object {
  [string]$_.responseCode -eq "201" -and [string]$_.success -eq "true"
})
$terminalSamples = @($samples | Where-Object { $_.label -like "*terminal action*" })
$terminalElapsed = @($terminalSamples | ForEach-Object { [int]$_.elapsed } | Sort-Object)
$terminalSuccessPercent = if ($terminalSamples.Count) {
  [Math]::Round((@($terminalSamples | Where-Object { [string]$_.success -eq "true" }).Count / $terminalSamples.Count) * 100, 4)
} else { $null }
$terminalCoveragePercent = if ($TerminalAction -eq "none") {
  $null
} elseif ($createSuccessSamples.Count -eq 0) {
  0
} else {
  [Math]::Round(($terminalSamples.Count / $createSuccessSamples.Count) * 100, 4)
}
$terminalP95 = if ($terminalElapsed.Count) {
  $terminalElapsed[[Math]::Min($terminalElapsed.Count - 1, [Math]::Ceiling($terminalElapsed.Count * .95) - 1)]
} else { $null }
$expectedCodes = @("200", "201", "202", "404", "409", "429", "503")
$unexpectedHttpFailurePercent = if ($count) {
  [Math]::Round((@($samples | Where-Object { $expectedCodes -notcontains [string]$_.responseCode }).Count / $count) * 100, 4)
} else { 100 }
$gateFailures = [System.Collections.Generic.List[string]]::new()
if ($count -eq 0) {
  $gateFailures.Add("JMeter produced no samples.")
}
if ($unexpectedHttpFailurePercent -gt 0) {
  $gateFailures.Add("JMeter produced unexpected HTTP statuses ($unexpectedHttpFailurePercent%).")
}
if (-not $converged) {
  $gateFailures.Add("Reservation invariant or convergence evidence did not pass within 30 seconds.")
}
if ($Scenario -eq "duplicate-retry") {
  $expectedDuplicateSamples = $Threads * $loops
  $actualDuplicateSamples = @($samples | Where-Object { $_.label -eq "POST /api/v1/reservations duplicate retry" }).Count
  if ($actualDuplicateSamples -ne $expectedDuplicateSamples) {
    $gateFailures.Add("Duplicate-retry workload recorded $actualDuplicateSamples retries; expected $expectedDuplicateSamples for one retry per logical intent.")
  }
}
if ($TerminalAction -ne "none" -and $terminalCoveragePercent -lt 100) {
  $gateFailures.Add("Terminal action coverage was $terminalCoveragePercent%; expected 100% of successful creates.")
}
if ($Scenario -eq "healthy") {
  if ($HealthyP95BaselineMs -le 0) {
    $gateFailures.Add("HealthyP95BaselineMs is required to certify the healthy latency gate.")
  } elseif ($p95 -gt ($HealthyP95BaselineMs * 1.2)) {
    $gateFailures.Add("Healthy p95 $p95 ms exceeds the permitted 20% regression over baseline $HealthyP95BaselineMs ms.")
  }
}
$startMs = if ($count) { ($samples | ForEach-Object { [long]$_.timeStamp } | Measure-Object -Minimum).Minimum } else { 0 }
$endMs = if ($count) { ($samples | ForEach-Object { [long]$_.timeStamp + [long]$_.elapsed } | Measure-Object -Maximum).Maximum } else { 0 }
$durationSeconds = [Math]::Max(0.001, ($endMs - $startMs) / 1000.0)
$throughput = [Math]::Round($count / $durationSeconds, 2)
$statusCounts = [ordered]@{}
$samples | Group-Object responseCode | ForEach-Object { $statusCounts[[string]$_.Name] = $_.Count }
$rejectedRequests = @($samples | Where-Object { @("409", "429", "503") -contains [string]$_.responseCode }).Count
$completedSha = (& git rev-parse HEAD).Trim()
$completionStatusMarker = ("benchmark/results/$runId").Replace('\', '/')
$completedStatus = @(git status --porcelain=v1 | Where-Object { $_ -notlike "*$completionStatusMarker*" })
Write-JsonFile (Join-Path $resultsDir "git.json") ([ordered]@{
  sha = $sha
  status = $status
  completedSha = $completedSha
  completedStatus = $completedStatus
  completedStatusExcludes = @($completionStatusMarker)
  capturedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
})
$metrics = [ordered]@{
  sha = $sha
  baseUrl = $BaseUrl
  fixtureResetUrl = $FixtureResetUrl
  ticketItemId = $TicketItemId
  fixtureStock = $FixtureStock
  strategy = $Strategy
  scenario = $Scenario
  terminalAction = $TerminalAction
  samples = $count
  statusCounts = $statusCounts
  acceptedUnits = [long]$evidence.acceptedUnits
  rejectedRequests = $rejectedRequests
  rejectedUnits = $null
  throughputRequestsPerSecond = $throughput
  averageMs = if ($count) { [Math]::Round((($elapsed | Measure-Object -Average).Average), 2) } else { $null }
  p50Ms = $p50
  p95Ms = $p95
  p99Ms = $p99
  healthyP95Ms = if ($Scenario -eq "healthy") { $p95 } else { $null }
  terminalSuccessPercent = $terminalSuccessPercent
  terminalP95Ms = $terminalP95
  createSuccessSamples = $createSuccessSamples.Count
  terminalSamples = $terminalSamples.Count
  terminalCoveragePercent = $terminalCoveragePercent
  unexpectedHttpFailurePercent = $unexpectedHttpFailurePercent
  oversoldUnits = [long]$evidence.oversoldUnits
  negativeStockUnits = [long]$evidence.negativeStockUnits
  duplicateReservations = [long]$evidence.duplicateReservations
  duplicateOrders = [long]$evidence.duplicateOrders
  finalDriftUnits = [long]$evidence.finalDriftUnits
  convergenceSeconds = $convergenceSeconds
  pendingJournal = [long]$evidence.pendingJournal
  pendingOutbox = [long]$evidence.pendingOutbox
  gates = [ordered]@{
    healthyP95BaselineMs = $HealthyP95BaselineMs
    failures = @($gateFailures)
  }
  verdict = if ($gateFailures.Count -eq 0) { "PASS" } else { "NO-GO" }
}
$metricsPath = Join-Path $resultsDir "metrics.json"
Write-JsonFile $metricsPath $metrics
$metricsHash = (Get-FileHash -LiteralPath $metricsPath -Algorithm SHA256).Hash.ToLowerInvariant()
$metricsHash | Out-File -LiteralPath (Join-Path $resultsDir "metrics.json.sha256") -Encoding ascii
$summary = @(
  "# Reservation workload: $runId",
  "",
  "- Scenario: ``$Scenario``",
  "- SHA: ``$sha``",
  "- Samples: $count (requested $Attempts, threads $Threads)",
  "- Average ms: $([Math]::Round((($elapsed | Measure-Object -Average).Average), 2))",
  "- p95 ms: $p95",
  "- p99 ms: $p99",
  "- Terminal success percent: $terminalSuccessPercent",
  "- Terminal coverage percent: $terminalCoveragePercent ($($terminalSamples.Count) terminal samples / $($createSuccessSamples.Count) successful creates)",
  "- Convergence: verdict=$(if ($converged) { 'PASS' } else { 'NO-GO' }), seconds=$convergenceSeconds, pendingJournal=$($evidence.pendingJournal), pendingOutbox=$($evidence.pendingOutbox), drift=$($evidence.finalDriftUnits)",
  "- Inventory snapshot: available=$($inventory.available), reserved=$($inventory.reserved), confirmed=$($inventory.confirmed)",
  "- Verdict: $($metrics.verdict). $($gateFailures -join ' ')"
)
$summary | Out-File -LiteralPath (Join-Path $resultsDir "summary.md") -Encoding utf8
Write-Host "Reservation evidence written to $resultsDir"
if ($gateFailures.Count -gt 0) {
  throw "Reservation workload gate failed: $($gateFailures -join ' ')"
}
