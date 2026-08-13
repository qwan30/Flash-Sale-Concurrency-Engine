param(
  [string]$RunDirectory = "",
  [string]$BaselineDirectory = "",
  [string]$ThresholdFile = ".\benchmark\effectiveness-thresholds.json",
  [string]$ReportFile = ".\docs\reports\reservation-effectiveness-report.md"
)

$ErrorActionPreference = "Stop"
$thresholds = Get-Content -LiteralPath $ThresholdFile -Raw | ConvertFrom-Json
$sha = (& git rev-parse HEAD).Trim()
$missing = [System.Collections.Generic.List[string]]::new()

function Add-Missing([string]$Reason) {
  if (-not [string]::IsNullOrWhiteSpace($Reason)) { $missing.Add($Reason) }
}

function Read-JsonArtifact([string]$Directory, [string]$Name) {
  $path = Join-Path $Directory $Name
  if (-not (Test-Path -LiteralPath $path)) {
    Add-Missing "Missing artifact: $Name in $Directory"
    return $null
  }
  try {
    return Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
  } catch {
    Add-Missing "Invalid JSON artifact: $path ($($_.Exception.Message))"
    return $null
  }
}

function Get-RequiredNumber([object]$Object, [string]$Property, [string]$Label) {
  if ($null -eq $Object -or $null -eq $Object.PSObject.Properties[$Property]) {
    Add-Missing "Missing numeric field: $Label"
    return $null
  }
  $value = $Object.PSObject.Properties[$Property].Value
  if ($null -eq $value -or -not ($value -is [ValueType]) -or $value -is [bool]) {
    Add-Missing "Numeric field is null or invalid: $Label"
    return $null
  }
  try { return [double]$value } catch {
    Add-Missing "Numeric field is invalid: $Label"
    return $null
  }
}

function Assert-ArtifactSha256([string]$Directory, [string]$Artifact, [string]$DigestArtifact) {
  $artifactPath = Join-Path $Directory $Artifact
  $digestPath = Join-Path $Directory $DigestArtifact
  if (-not (Test-Path -LiteralPath $artifactPath)) {
    Add-Missing "Missing artifact for digest check: $Artifact in $Directory"
    return
  }
  if (-not (Test-Path -LiteralPath $digestPath)) {
    Add-Missing "Missing digest artifact: $DigestArtifact in $Directory"
    return
  }
  $expected = (Get-Content -LiteralPath $digestPath -Raw).Trim().ToLowerInvariant()
  $actual = (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($expected -ne $actual) {
    Add-Missing "Digest mismatch for $Artifact in $Directory"
  }
}

function Assert-CompletedGitIdentity([object]$Git, [string]$Label) {
  if ($null -eq $Git -or $null -eq $Git.PSObject.Properties["completedSha"] -or [string]::IsNullOrWhiteSpace([string]$Git.completedSha)) {
    Add-Missing "$Label is missing completedSha; exact-SHA evidence must cover workload completion."
  } elseif ([string]$Git.completedSha -ne $sha) {
    Add-Missing "$Label completedSha does not match current HEAD: $($Git.completedSha) != $sha"
  }
  if ($null -eq $Git -or $null -eq $Git.PSObject.Properties["completedStatus"]) {
    Add-Missing "$Label is missing completedStatus; exact-SHA evidence must cover workload completion."
  } elseif (@($Git.completedStatus).Count -ne 0) {
    Add-Missing "$Label records a dirty working tree at completion; exact-SHA evidence must be clean."
  }
}

function Assert-SameService([object]$BaseUrl, [object]$ResetUrl, [string]$Label) {
  try {
    $base = [Uri]([string]$BaseUrl)
    $reset = [Uri]([string]$ResetUrl)
    if ($base.Scheme -ne $reset.Scheme -or $base.Host -ne $reset.Host -or $base.Port -ne $reset.Port) {
      Add-Missing "$Label does not target the same scheme/host/port as the workload base URL."
    }
  } catch {
    Add-Missing "$Label contains an invalid URL identity."
  }
}

function Assert-AtMost([double]$Actual, [double]$Limit, [string]$Label) {
  if ($Actual -gt $Limit) { Add-Missing "$Label=$Actual exceeds threshold $Limit" }
}

function Assert-AtLeast([double]$Actual, [double]$Limit, [string]$Label) {
  if ($Actual -lt $Limit) { Add-Missing "$Label=$Actual is below threshold $Limit" }
}

if (-not (Test-Path -LiteralPath $ThresholdFile)) {
  Add-Missing "Threshold file does not exist: $ThresholdFile"
}
foreach ($section in @("correctness", "recovery", "performance", "ui")) {
  if ($null -eq $thresholds -or $null -eq $thresholds.PSObject.Properties[$section]) {
    Add-Missing "Threshold section is missing: $section"
  }
}

$run = $null
$baseline = $null
$runDirectoryExists = -not [string]::IsNullOrWhiteSpace($RunDirectory) -and (Test-Path -LiteralPath $RunDirectory)
$baselineDirectoryExists = -not [string]::IsNullOrWhiteSpace($BaselineDirectory) -and (Test-Path -LiteralPath $BaselineDirectory)

if (-not $runDirectoryExists) {
  Add-Missing "RunDirectory was not supplied or does not exist; measured workload evidence is required."
} else {
    foreach ($required in @("manifest.json", "git.json", "reset.json", "environment.json", "fault-timeline.json", "results.jtl", "html\index.html", "consistency.json", "convergence.json", "consistency.json.sha256", "convergence.json.sha256", "metrics.json", "metrics.json.sha256", "ui.json", "summary.md")) {
    if (-not (Test-Path -LiteralPath (Join-Path $RunDirectory $required))) {
      Add-Missing "Missing run artifact: $required"
    }
  }
  $manifest = Read-JsonArtifact $RunDirectory "manifest.json"
  $git = Read-JsonArtifact $RunDirectory "git.json"
  $run = [ordered]@{
    manifest = $manifest
    git = $git
    environment = Read-JsonArtifact $RunDirectory "environment.json"
    reset = Read-JsonArtifact $RunDirectory "reset.json"
    metrics = Read-JsonArtifact $RunDirectory "metrics.json"
    consistency = Read-JsonArtifact $RunDirectory "consistency.json"
    convergence = Read-JsonArtifact $RunDirectory "convergence.json"
    faults = Read-JsonArtifact $RunDirectory "fault-timeline.json"
    ui = Read-JsonArtifact $RunDirectory "ui.json"
  }
  if ($null -ne $git -and $git.sha -ne $sha) { Add-Missing "Run git.json SHA does not match current HEAD: $($git.sha) != $sha" }
  if ($null -ne $manifest -and $manifest.sha -ne $sha) { Add-Missing "Run manifest SHA does not match current HEAD: $($manifest.sha) != $sha" }
  if ($null -ne $git -and @($git.status).Count -ne 0) { Add-Missing "Run git.json records a dirty working tree; exact-SHA evidence must be clean." }
  Assert-CompletedGitIdentity $git "Run git.json"
  if ($null -eq $manifest -or $manifest.fixtureResetTokenConfigured -ne $true) {
    Add-Missing "Run manifest does not prove that the token-gated fixture reset was used."
  }
  if ($null -eq $manifest -or [string]::IsNullOrWhiteSpace([string]$manifest.fixtureResetUrl)) {
    Add-Missing "Run manifest is missing the fixture reset endpoint identity."
  }
  Assert-ArtifactSha256 $RunDirectory "metrics.json" "metrics.json.sha256"
  Assert-ArtifactSha256 $RunDirectory "consistency.json" "consistency.json.sha256"
  Assert-ArtifactSha256 $RunDirectory "convergence.json" "convergence.json.sha256"
  if ($null -ne $manifest) {
    $manifestTicket = Get-RequiredNumber $manifest "ticketItemId" "manifest.ticketItemId"
    $manifestStock = Get-RequiredNumber $manifest "fixtureStock" "manifest.fixtureStock"
    if ($null -ne $manifest.baseUrl -and $null -ne $manifest.workloadUrl -and
        $manifest.workloadUrl -ne "$(([string]$manifest.baseUrl).TrimEnd('/'))$([string]$manifest.api)") {
      Add-Missing "Run manifest workloadUrl is not derived from baseUrl and api."
    }
    if ($null -ne $manifest.baseUrl -and $null -ne $manifest.fixtureResetUrl) {
      Assert-SameService $manifest.baseUrl $manifest.fixtureResetUrl "Run fixtureResetUrl"
    }
    if ($null -ne $manifest.fixture -and $null -ne $manifestTicket -and
        [double]$manifest.fixture.ticketItemId -ne $manifestTicket) {
      Add-Missing "Run manifest nested fixture ticketItemId does not match manifest.ticketItemId."
    }
    if ($null -ne $manifest.fixture -and $null -ne $manifestStock -and
        [double]$manifest.fixture.stock -ne $manifestStock) {
      Add-Missing "Run manifest nested fixture stock does not match manifest.fixtureStock."
    }
  }
  if ($null -ne $run.environment -and $null -ne $manifest) {
    if ($run.environment.baseUrl -ne $manifest.baseUrl -or
         $run.environment.fixtureResetUrl -ne $manifest.fixtureResetUrl -or
         [long]$run.environment.ticketItemId -ne [long]$manifest.ticketItemId -or
         [int]$run.environment.fixtureStock -ne [int]$manifest.fixtureStock -or
         $run.environment.strategy -ne $manifest.strategy -or
         $run.environment.scenario -ne $manifest.scenario -or
         $run.environment.terminalAction -ne $manifest.terminalAction) {
      Add-Missing "Run environment identity does not match the workload manifest."
    }
  }
  if ($null -ne $run.metrics -and $null -ne $manifest) {
    if ($run.metrics.sha -ne $sha -or
        $run.metrics.strategy -ne $manifest.strategy -or
        $run.metrics.scenario -ne $manifest.scenario -or
        [long]$run.metrics.ticketItemId -ne [long]$manifest.ticketItemId -or
        [int]$run.metrics.fixtureStock -ne [int]$manifest.fixtureStock -or
        $run.metrics.baseUrl -ne $manifest.baseUrl -or
        $run.metrics.fixtureResetUrl -ne $manifest.fixtureResetUrl) {
      Add-Missing "Run metrics identity does not match the workload manifest."
    }
  }
  foreach ($artifactName in @("consistency", "convergence")) {
    $artifact = $run.$artifactName
    if ($null -ne $artifact -and $null -ne $manifest) {
      if ($artifact.sha -ne $sha -or
          $artifact.baseUrl -ne $manifest.baseUrl -or
          $artifact.fixtureResetUrl -ne $manifest.fixtureResetUrl -or
          [long]$artifact.ticketItemId -ne [long]$manifest.ticketItemId -or
          [int]$artifact.fixtureStock -ne [int]$manifest.fixtureStock -or
          $artifact.strategy -ne $manifest.strategy -or
          $artifact.scenario -ne $manifest.scenario -or
          $artifact.terminalAction -ne $manifest.terminalAction) {
        Add-Missing "Run $artifactName identity does not match the workload manifest."
      }
    }
  }
  if ($null -eq $run.reset) {
    Add-Missing "Reset proof is missing; durable and Redis fixture parity must be proven before the workload."
  } elseif ($run.reset.success -ne $true -or $null -eq $run.reset.result) {
    Add-Missing "Reset proof does not contain a successful standard response envelope."
  } else {
    $resetProof = $run.reset.result
    if ($resetProof.success -ne $true) { Add-Missing "Reset proof nested success is not true." }
    if ($resetProof.reservationFixtureReset -ne $true) { Add-Missing "Reset proof does not mark reservationFixtureReset=true." }
    $expectedTicket = if ($null -ne $manifest) { $manifest.ticketItemId } else { $null }
    $expectedStock = if ($null -ne $manifest) { $manifest.fixtureStock } else { $null }
    $proofTicket = Get-RequiredNumber $resetProof "ticketItemId" "reset.result.ticketItemId"
    $proofStock = Get-RequiredNumber $resetProof "stock" "reset.result.stock"
    $proofDurable = Get-RequiredNumber $resetProof "reservationStockAfter" "reset.result.reservationStockAfter"
    $proofRedis = Get-RequiredNumber $resetProof "reservationRedisStockAfter" "reset.result.reservationRedisStockAfter"
    $proofReserved = Get-RequiredNumber $resetProof "reserved" "reset.result.reserved"
    $proofConfirmed = Get-RequiredNumber $resetProof "confirmed" "reset.result.confirmed"
    $proofFence = Get-RequiredNumber $resetProof "fenceVersion" "reset.result.fenceVersion"
    if ($null -eq $expectedTicket -or $null -eq $expectedStock) {
      Add-Missing "Run manifest is missing fixture ticketItemId/stock identity."
    } else {
      if ($null -ne $proofTicket -and $proofTicket -ne [double]$expectedTicket) { Add-Missing "Reset proof ticketItemId does not match manifest fixture." }
      if ($null -ne $proofStock -and $proofStock -ne [double]$expectedStock) { Add-Missing "Reset proof stock does not match manifest fixture." }
      if ($null -ne $proofDurable -and $proofDurable -ne [double]$expectedStock) { Add-Missing "Reset proof durable stock does not match manifest fixture." }
      if ($null -ne $proofRedis -and $proofRedis -ne [double]$expectedStock) { Add-Missing "Reset proof Redis stock does not match manifest fixture." }
    }
    if ($null -ne $proofReserved -and $proofReserved -ne 0) { Add-Missing "Reset proof reserved bucket is not zero." }
    if ($null -ne $proofConfirmed -and $proofConfirmed -ne 0) { Add-Missing "Reset proof confirmed bucket is not zero." }
    if ($null -ne $proofFence -and $proofFence -ne 0) { Add-Missing "Reset proof fenceVersion is not zero." }
    if ($resetProof.admissionState -ne "OPEN") { Add-Missing "Reset proof admissionState is not OPEN." }
    if ($null -eq $manifest.strategy -or $resetProof.strategy -ne $manifest.strategy) { Add-Missing "Reset proof strategy does not match manifest strategy." }
  }
}

if (-not $baselineDirectoryExists) {
  Add-Missing "BaselineDirectory was not supplied or does not exist; same-machine baseline evidence is required."
} else {
  foreach ($required in @("manifest.json", "metrics.json", "metrics.json.sha256", "git.json", "environment.json")) {
    if (-not (Test-Path -LiteralPath (Join-Path $BaselineDirectory $required))) {
      Add-Missing "Missing baseline artifact: $required"
    }
  }
  $baseline = [ordered]@{
    manifest = Read-JsonArtifact $BaselineDirectory "manifest.json"
    metrics = Read-JsonArtifact $BaselineDirectory "metrics.json"
    git = Read-JsonArtifact $BaselineDirectory "git.json"
    environment = Read-JsonArtifact $BaselineDirectory "environment.json"
  }
  Assert-ArtifactSha256 $BaselineDirectory "metrics.json" "metrics.json.sha256"
  if ($null -ne $baseline.git -and $baseline.git.sha -ne $sha) {
    Add-Missing "Baseline git.json SHA does not match current HEAD: $($baseline.git.sha) != $sha"
  }
  if ($null -ne $baseline.git -and @($baseline.git.status).Count -ne 0) {
    Add-Missing "Baseline git.json records a dirty working tree; exact-SHA evidence must be clean."
  }
  Assert-CompletedGitIdentity $baseline.git "Baseline git.json"
  if ($null -ne $baseline.manifest) {
    if ($baseline.manifest.sha -ne $sha) {
      Add-Missing "Baseline manifest SHA does not match current HEAD: $($baseline.manifest.sha) != $sha"
    }
    if ($null -ne $baseline.manifest.baseUrl -and $null -ne $baseline.manifest.fixtureResetUrl) {
      Assert-SameService $baseline.manifest.baseUrl $baseline.manifest.fixtureResetUrl "Baseline fixtureResetUrl"
    }
    if ($null -ne $baseline.metrics -and
        ($baseline.metrics.sha -ne $baseline.manifest.sha -or
         $baseline.metrics.strategy -ne $baseline.manifest.strategy -or
         $baseline.metrics.scenario -ne $baseline.manifest.scenario -or
         [long]$baseline.metrics.ticketItemId -ne [long]$baseline.manifest.ticketItemId -or
         [int]$baseline.metrics.fixtureStock -ne [int]$baseline.manifest.fixtureStock -or
         $baseline.metrics.baseUrl -ne $baseline.manifest.baseUrl -or
          $baseline.metrics.fixtureResetUrl -ne $baseline.manifest.fixtureResetUrl)) {
      Add-Missing "Baseline metrics identity does not match the baseline manifest."
    }
  }
}

if ($null -ne $run -and $null -ne $run.metrics -and $null -ne $baseline -and $null -ne $baseline.metrics) {
  $runP95 = if ($null -ne $run.manifest -and $run.manifest.scenario -eq "healthy") {
    Get-RequiredNumber $run.metrics "healthyP95Ms" "run.metrics.healthyP95Ms"
  } else {
    $null
  }
  $baselineP95 = Get-RequiredNumber $baseline.metrics "healthyP95Ms" "baseline.metrics.healthyP95Ms"
  $samples = Get-RequiredNumber $run.metrics "samples" "run.metrics.samples"
  $terminalSuccess = $null
  $terminalCoverage = $null
  $terminalSampleCount = $null
  $createSuccessSampleCount = $null
  if ($null -ne $run.manifest -and
      ($run.manifest.terminalAction -ne "none" -or $run.manifest.scenario -eq "overload")) {
    $terminalSuccess = Get-RequiredNumber $run.metrics "terminalSuccessPercent" "run.metrics.terminalSuccessPercent"
    $terminalCoverage = Get-RequiredNumber $run.metrics "terminalCoveragePercent" "run.metrics.terminalCoveragePercent"
    $terminalSampleCount = Get-RequiredNumber $run.metrics "terminalSamples" "run.metrics.terminalSamples"
    $createSuccessSampleCount = Get-RequiredNumber $run.metrics "createSuccessSamples" "run.metrics.createSuccessSamples"
  }
  $unexpectedHttp = Get-RequiredNumber $run.metrics "unexpectedHttpFailurePercent" "run.metrics.unexpectedHttpFailurePercent"
  if ($null -ne $samples -and $samples -le 0) { Add-Missing "run.metrics.samples must be greater than zero" }
  if ($null -ne $runP95 -and $null -ne $baselineP95 -and $baselineP95 -le 0) { Add-Missing "baseline.metrics.healthyP95Ms must be greater than zero" }
  if ($null -ne $runP95 -and $null -ne $baselineP95 -and $baselineP95 -gt 0) {
    $regression = (($runP95 - $baselineP95) / $baselineP95) * 100
    Assert-AtMost $regression $thresholds.performance.maxHealthyP95RegressionPercent "healthyP95RegressionPercent"
  }
  if ($null -ne $terminalSuccess) { Assert-AtLeast $terminalSuccess $thresholds.performance.minTerminalSuccessPercentDuringCreateFlood "terminalSuccessPercent" }
  if ($null -ne $terminalCoverage) { Assert-AtLeast $terminalCoverage $thresholds.performance.minTerminalCoveragePercent "terminalCoveragePercent" }
  if ($null -ne $terminalSampleCount -and $terminalSampleCount -le 0) { Add-Missing "terminalSamples must be greater than zero during terminal-priority measurement" }
  if ($null -ne $createSuccessSampleCount -and $null -ne $terminalSampleCount -and
      $terminalSampleCount -gt $createSuccessSampleCount) {
    Add-Missing "terminalSamples=$terminalSampleCount exceeds createSuccessSamples=$createSuccessSampleCount"
  }
  if ($null -ne $unexpectedHttp) { Assert-AtMost $unexpectedHttp $thresholds.performance.maxUnexpectedHttpFailurePercent "unexpectedHttpFailurePercent" }

  foreach ($field in @("oversoldUnits", "negativeStockUnits", "duplicateReservations", "duplicateOrders", "finalDriftUnits")) {
    $value = Get-RequiredNumber $run.metrics $field "run.metrics.$field"
    $thresholdProperty = switch ($field) {
      "negativeStockUnits" { "maxNegativeStockObservations" }
      default { "max" + $field.Substring(0, 1).ToUpper() + $field.Substring(1) }
    }
    if ($null -ne $value) {
      Assert-AtMost $value $thresholds.correctness.PSObject.Properties[$thresholdProperty].Value "run.metrics.$field"
    }
  }
}

if ($null -ne $run -and $null -ne $run.convergence) {
  if ($run.convergence.verdict -ne "PASS") { Add-Missing "Convergence artifact is not a PASS verdict." }
  if ($run.convergence.invariantPass -ne $true) { Add-Missing "Convergence invariantPass is not true." }
  if ($run.convergence.parityPass -ne $true) { Add-Missing "Convergence parityPass is not true." }
  $convergenceSeconds = Get-RequiredNumber $run.convergence "convergenceSeconds" "run.convergence.convergenceSeconds"
  $pendingJournal = Get-RequiredNumber $run.convergence "pendingJournal" "run.convergence.pendingJournal"
  $pendingOutbox = Get-RequiredNumber $run.convergence "pendingOutbox" "run.convergence.pendingOutbox"
  $convergenceDrift = Get-RequiredNumber $run.convergence "finalDriftUnits" "run.convergence.finalDriftUnits"
  if ($null -ne $convergenceSeconds) { Assert-AtMost $convergenceSeconds $thresholds.recovery.maxConvergenceSeconds "convergenceSeconds" }
  if ($null -ne $pendingJournal) { Assert-AtMost $pendingJournal $thresholds.recovery.maxPendingJournalAfterConvergence "pendingJournal" }
  if ($null -ne $pendingOutbox) { Assert-AtMost $pendingOutbox $thresholds.recovery.maxPendingOutboxAfterConvergence "pendingOutbox" }
  if ($null -ne $convergenceDrift) { Assert-AtMost $convergenceDrift $thresholds.correctness.maxFinalDriftUnits "convergence.finalDriftUnits" }
  if ($null -ne $run.consistency -and $null -ne $run.consistency.evidence) {
    $evidence = $run.consistency.evidence
    if ($evidence.invariantPass -ne $run.convergence.invariantPass -or
        $evidence.parityPass -ne $run.convergence.parityPass -or
        [long]$evidence.finalDriftUnits -ne [long]$run.convergence.finalDriftUnits -or
        [long]$evidence.pendingJournal -ne [long]$run.convergence.pendingJournal -or
        [long]$evidence.pendingOutbox -ne [long]$run.convergence.pendingOutbox) {
      Add-Missing "Consistency evidence does not match convergence component evidence."
    }
  } else {
    Add-Missing "Consistency evidence is required to validate convergence components."
  }
  if ($null -ne $run.metrics) {
    if ([long]$run.metrics.finalDriftUnits -ne [long]$run.convergence.finalDriftUnits -or
        [long]$run.metrics.pendingJournal -ne [long]$run.convergence.pendingJournal -or
        [long]$run.metrics.pendingOutbox -ne [long]$run.convergence.pendingOutbox -or
        $run.metrics.convergenceSeconds -ne $run.convergence.convergenceSeconds) {
      Add-Missing "Metrics convergence fields do not match convergence.json."
    }
  }
}

if ($null -ne $run -and $null -ne $run.faults) {
  $requiredFaults = @("AFTER_REDIS_BEFORE_DB", "AFTER_DB_COMMIT_BEFORE_RESPONSE", "REDIS_MIRROR_TIMEOUT", "KAFKA_UNAVAILABLE", "CONFIRM_EXPIRE_RACE")
  foreach ($fault in $requiredFaults) {
    $entry = @($run.faults.faults | Where-Object { $_.name -eq $fault })
    if ($entry.Count -ne 1) { Add-Missing "Fault timeline is missing exactly one entry for $fault" }
    elseif ($null -eq $entry[0].convergenceSeconds) { Add-Missing "Fault timeline has no convergenceSeconds for $fault" }
  }
}

if ($null -ne $run -and $null -ne $run.ui) {
  $uiPass = Get-RequiredNumber $run.ui "passPercent" "run.ui.passPercent"
  $consoleErrors = Get-RequiredNumber $run.ui "unexpectedConsoleErrors" "run.ui.unexpectedConsoleErrors"
  $networkFailures = Get-RequiredNumber $run.ui "unexpectedNetworkFailures" "run.ui.unexpectedNetworkFailures"
  if ($null -ne $uiPass) { Assert-AtLeast $uiPass $thresholds.ui.requiredControlPassPercent "ui.passPercent" }
  if ($null -ne $consoleErrors) { Assert-AtMost $consoleErrors $thresholds.ui.maxUnexpectedConsoleErrors "ui.unexpectedConsoleErrors" }
  if ($null -ne $networkFailures) { Assert-AtMost $networkFailures $thresholds.ui.maxUnexpectedNetworkFailures "ui.unexpectedNetworkFailures" }
}

$verdict = if ($missing.Count -eq 0) { "PASS" } else { "NO-GO" }
$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add("# Reservation effectiveness report")
$lines.Add("")
$lines.Add("- Verdict: **$verdict**")
$lines.Add("- Target SHA: ``$sha``")
$lines.Add("- Threshold file: ``$ThresholdFile``")
$lines.Add("- Run directory: ``$RunDirectory``")
$lines.Add("- Baseline directory: ``$BaselineDirectory``")
$lines.Add("")
$lines.Add("## Gate disposition")
$lines.Add("")
if ($missing.Count -eq 0) {
  $lines.Add("Every required artifact was identity-bound and every locked correctness, recovery, performance, and UI threshold passed.")
} else {
  $lines.Add("This report is intentionally fail-closed because required evidence is missing, identity is not exact, or a threshold failed:")
  foreach ($reason in $missing) { $lines.Add("- $reason") }
}
$reportParent = Split-Path -Parent $ReportFile
if ($reportParent) { $null = New-Item -ItemType Directory -Force -Path $reportParent }
$lines | Out-File -LiteralPath $ReportFile -Encoding utf8
Write-Host "Effectiveness verdict: $verdict"
if ($verdict -ne "PASS") { exit 1 }
