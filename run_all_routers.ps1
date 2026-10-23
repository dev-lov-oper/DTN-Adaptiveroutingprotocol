# Script to run all 14 DTN routing protocols and generate simulation reports
$ErrorActionPreference = "Stop"

$configsDir = "configs"
if (!(Test-Path $configsDir)) {
    New-Item -ItemType Directory -Path $configsDir | Out-Null
}

$reportsDir = "reports"
if (!(Test-Path $reportsDir)) {
    New-Item -ItemType Directory -Path $reportsDir | Out-Null
}

$routers = @(
    @{ Name = "EpidemicRouter"; Extra = "" },
    @{ Name = "FirstContactRouter"; Extra = "" },
    @{ Name = "DirectDeliveryRouter"; Extra = "" },
    @{ Name = "SprayAndWaitRouter"; Extra = "" },
    @{ Name = "ProphetRouter"; Extra = "" },
    @{ Name = "ProphetV2Router"; Extra = "ProphetV2Router.secondsInTimeUnit = 30" },
    @{ Name = "ProphetRouterWithEstimation"; Extra = "ProphetRouterWithEstimation.timeScale = 30" },
    @{ Name = "MaxPropRouter"; Extra = "" },
    @{ Name = "MaxPropRouterWithEstimation"; Extra = "MaxPropRouterWithEstimation.timeScale = 30" },
    @{ Name = "LifeRouter"; Extra = "Group.nmcount = 1, 3" },
    @{ Name = "WaveRouter"; Extra = "Group.immunityTime = 60`nGroup.custodyFraction = 0.5" },
    @{ Name = "EpidemicOracleRouter"; Extra = "" },
    @{ Name = "PassiveRouter"; Extra = "" },
    @{ Name = "AdaptiveHybridRouter"; Extra = "" }
)

$commonReportSettings = @"
Report.nrofReports = 4
Report.reportDir = reports/
Report.report1 = MessageStatsReport
Report.report2 = ContactTimesReport
Report.report3 = DeliveredMessagesReport
Report.report4 = MessageDelayReport
"@

$total = $routers.Count
$current = 0
$startTimeTotal = Get-Date

Write-Host "=========================================================="
Write-Host "Starting simulation batch for $total routing protocols..."
Write-Host "Simulated time per scenario: 43200s (12.0 hours)"
Write-Host "=========================================================="

foreach ($r in $routers) {
    $current++
    $routerName = $r.Name
    $extraSettings = $r.Extra
    $cfgPath = "$configsDir\$routerName.txt"
    
    $cfgContent = @"
Scenario.name = $routerName
Group.router = $routerName
$commonReportSettings
$extraSettings
"@
    Set-Content -Path $cfgPath -Value $cfgContent -Encoding ASCII
    
    Write-Host "`n[$current/$total] Running $routerName..." -ForegroundColor Cyan
    $simStart = Get-Date
    
    $proc = Start-Process -FilePath "java" `
        -ArgumentList @("-Xmx512M", "-cp", "target;lib/ECLA.jar;lib/DTNConsoleConnection.jar", "core.DTNSim", "-b", "1", "default_settings.txt", $cfgPath) `
        -NoNewWindow -PassThru -Wait
        
    $simElapsed = [Math]::Round(((Get-Date) - $simStart).TotalSeconds, 2)
    
    if ($proc.ExitCode -eq 0) {
        Write-Host "[$current/$total] SUCCESS: $routerName finished in ${simElapsed}s" -ForegroundColor Green
    } else {
        Write-Host "[$current/$total] FAILED: $routerName (ExitCode: $($proc.ExitCode))" -ForegroundColor Red
    }
}

$totalElapsed = [Math]::Round(((Get-Date) - $startTimeTotal).TotalSeconds, 1)
Write-Host "`n=========================================================="
Write-Host "All simulations completed in ${totalElapsed}s!"
Write-Host "Parsing reports and generating comparison summary..."
Write-Host "=========================================================="

# Parse MessageStatsReport for all routers
$results = @()

foreach ($r in $routers) {
    $routerName = $r.Name
    $repFile = "$reportsDir\${routerName}_MessageStatsReport.txt"
    
    if (Test-Path $repFile) {
        $lines = Get-Content $repFile
        $data = @{
            Router = $routerName
            Created = "N/A"
            Started = "N/A"
            Relayed = "N/A"
            Aborted = "N/A"
            Dropped = "N/A"
            Delivered = "N/A"
            DeliveryProb = "N/A"
            OverheadRatio = "N/A"
            LatencyAvg = "N/A"
            LatencyMed = "N/A"
            HopCountAvg = "N/A"
            BufferTimeAvg = "N/A"
        }
        
        foreach ($line in $lines) {
            $parts = $line -split ':\s*'
            if ($parts.Count -ge 2) {
                $key = $parts[0].Trim()
                $val = $parts[1].Trim()
                
                switch ($key) {
                    "created" { $data.Created = $val }
                    "started" { $data.Started = $val }
                    "relayed" { $data.Relayed = $val }
                    "aborted" { $data.Aborted = $val }
                    "dropped" { $data.Dropped = $val }
                    "delivered" { $data.Delivered = $val }
                    "delivery_prob" { $data.DeliveryProb = $val }
                    "overhead_ratio" { $data.OverheadRatio = $val }
                    "latency_avg" { $data.LatencyAvg = $val }
                    "latency_med" { $data.LatencyMed = $val }
                    "hopcount_avg" { $data.HopCountAvg = $val }
                    "buffertime_avg" { $data.BufferTimeAvg = $val }
                }
            }
        }
        
        $results += [PSCustomObject]$data
    }
}

# Output to console
$results | Format-Table -AutoSize | Out-String | Write-Host

# Export to CSV
$csvPath = "$reportsDir\All_Routers_Comparison_Summary.csv"
$results | Export-Csv -Path $csvPath -NoTypeInformation
Write-Host "Saved CSV summary to: $csvPath"

# Export formatted text report
$txtSummaryPath = "$reportsDir\All_Routers_Comparison_Summary.txt"
$summaryText = @"
========================================================================================================================
                                 THE ONE SIMULATOR - ROUTING PROTOCOLS BENCHMARK REPORT
========================================================================================================================
Generated At: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
Scenario Duration: 43200s (12 Hours)
Total Protocols Evaluated: $($results.Count)

$($results | Format-Table -AutoSize | Out-String)
========================================================================================================================
"@
Set-Content -Path $txtSummaryPath -Value $summaryText -Encoding UTF8
Write-Host "Saved text summary to: $txtSummaryPath"
