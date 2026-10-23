# Density Experiments: Sparse (51 nodes), Medium (126 nodes), Dense (246 nodes)
# Evaluating: DirectDeliveryRouter, EpidemicRouter, ProphetRouter, AdaptiveHybridRouter, MaxPropRouter

$ErrorActionPreference = "Stop"

$configsDir = "configs"
$reportsDir = "reports"

$scenarios = @(
    @{ Name = "Sparse";  HostsPerGroup = 15; TotalHosts = 51 },
    @{ Name = "Medium";  HostsPerGroup = 40; TotalHosts = 126 },
    @{ Name = "Dense";   HostsPerGroup = 80; TotalHosts = 246 }
)

$routers = @(
    @{ Name = "DirectDeliveryRouter"; Extra = "" },
    @{ Name = "EpidemicRouter"; Extra = "" },
    @{ Name = "ProphetRouter"; Extra = "ProphetRouter.secondsInTimeUnit = 30" },
    @{ Name = "MaxPropRouter"; Extra = "" },
    @{ Name = "AdaptiveHybridRouter"; Extra = "" }
)

$commonReportSettings = @"
Report.nrofReports = 4
Report.reportDir = reports/
Report.report1 = MessageStatsReport
Report.report2 = ContactTimesReport
Report.report3 = DeliveredMessagesReport
Report.report4 = DeliveryCostReport
"@

$totalRuns = $scenarios.Count * $routers.Count
$currentRun = 0
Write-Host "================================================================="
Write-Host "Starting Density Scenarios Benchmark ($totalRuns simulations)..."
Write-Host "================================================================="

foreach ($sc in $scenarios) {
    $scName = $sc.Name
    $nHosts = $sc.HostsPerGroup
    $totHosts = $sc.TotalHosts
    
    foreach ($r in $routers) {
        $currentRun++
        $rName = $r.Name
        $simName = "${scName}_${rName}"
        $cfgPath = "$configsDir\density_${simName}.txt"
        $extra = $r.Extra
        
        $cfgContent = @"
Scenario.name = $simName
Group.router = $rName
Group.nrofHosts = $nHosts
Events1.hosts = 0,$totHosts
$commonReportSettings
$extra
"@
        Set-Content -Path $cfgPath -Value $cfgContent -Encoding ASCII
        
        Write-Host "`n[$currentRun/$totalRuns] Running $simName (Nodes: $totHosts)..." -ForegroundColor Cyan
        $simStart = Get-Date
        
        $proc = Start-Process -FilePath "java" `
            -ArgumentList @("-Xmx512M", "-cp", "target;lib/ECLA.jar;lib/DTNConsoleConnection.jar", "core.DTNSim", "-b", "1", "default_settings.txt", $cfgPath) `
            -NoNewWindow -PassThru -Wait
            
        $simElapsed = [Math]::Round(((Get-Date) - $simStart).TotalSeconds, 2)
        Write-Host "[$simName] finished in ${simElapsed}s with ExitCode: $($proc.ExitCode)" -ForegroundColor Green
    }
}

Write-Host "`nAll simulations completed! Parsing results into CSV..." -ForegroundColor Yellow

# Parse all results into reports/Density_Scenarios_Comparison.csv
$results = @()

foreach ($sc in $scenarios) {
    $scName = $sc.Name
    $totHosts = $sc.TotalHosts
    
    foreach ($r in $routers) {
        $rName = $r.Name
        $simName = "${scName}_${rName}"
        $statsFile = "$reportsDir\${simName}_MessageStatsReport.txt"
        $costFile  = "$reportsDir\${simName}_DeliveryCostReport.txt"
        
        $item = [ordered]@{
            Scenario       = $scName
            Nodes          = $totHosts
            Router         = $rName
            Created        = ""
            Delivered      = ""
            DeliveryProb   = ""
            OverheadRatio  = ""
            LatencyAvg     = ""
            LatencyMed     = ""
            HopCountAvg    = ""
            BufferTimeAvg  = ""
            Dropped        = ""
            Relayed        = ""
            TxDeliveryCost = ""
            CPI            = ""
            CompositeCost  = ""
            BandwidthCost  = ""
        }
        
        if (Test-Path $statsFile) {
            Get-Content $statsFile | ForEach-Object {
                if ($_ -match "^\s*created:\s*(\S+)")      { $item.Created = $matches[1] }
                if ($_ -match "^\s*delivered:\s*(\S+)")    { $item.Delivered = $matches[1] }
                if ($_ -match "^\s*delivery_prob:\s*(\S+)") { $item.DeliveryProb = $matches[1] }
                if ($_ -match "^\s*overhead_ratio:\s*(\S+)") { $item.OverheadRatio = $matches[1] }
                if ($_ -match "^\s*latency_avg:\s*(\S+)")  { $item.LatencyAvg = $matches[1] }
                if ($_ -match "^\s*latency_med:\s*(\S+)")  { $item.LatencyMed = $matches[1] }
                if ($_ -match "^\s*hopcount_avg:\s*(\S+)") { $item.HopCountAvg = $matches[1] }
                if ($_ -match "^\s*buffertime_avg:\s*(\S+)") { $item.BufferTimeAvg = $matches[1] }
                if ($_ -match "^\s*dropped:\s*(\S+)")      { $item.Dropped = $matches[1] }
                if ($_ -match "^\s*relayed:\s*(\S+)")      { $item.Relayed = $matches[1] }
            }
        }
        
        if (Test-Path $costFile) {
            Get-Content $costFile | ForEach-Object {
                if ($_ -match "tx_delivery_cost.*:\s*([0-9.]+)")       { $item.TxDeliveryCost = $matches[1] }
                if ($_ -match "cost_performance_index.*:\s*([0-9.]+)") { $item.CPI = $matches[1] }
                if ($_ -match "composite_resource_cost.*:\s*([0-9.]+)") { $item.CompositeCost = $matches[1] }
                if ($_ -match "bandwidth_delivery_cost.*:\s*([0-9.]+)") { $item.BandwidthCost = $matches[1] }
            }
        }
        
        $results += [PSCustomObject]$item
    }
}

$csvPath = "$reportsDir\Density_Scenarios_Comparison.csv"
$results | Export-Csv -Path $csvPath -NoTypeInformation
Write-Host "CSV Summary saved to: $csvPath" -ForegroundColor Green
