$ErrorActionPreference = "Stop"
$reportsDir = "reports"

$scenarios = @(
    @{ Name = "Sparse";  TotalHosts = 51 },
    @{ Name = "Medium";  TotalHosts = 126 },
    @{ Name = "Dense";   TotalHosts = 246 }
)

$routers = @(
    "DirectDeliveryRouter",
    "EpidemicRouter",
    "ProphetRouter",
    "MaxPropRouter",
    "AdaptiveHybridRouter"
)

$results = @()

foreach ($sc in $scenarios) {
    $scName = $sc.Name
    $totHosts = $sc.TotalHosts
    
    foreach ($rName in $routers) {
        $simName = "${scName}_${rName}"
        $statsFile = "$reportsDir\${simName}_MessageStatsReport.txt"
        $costFile  = "$reportsDir\${simName}_DeliveryCostReport.txt"
        
        $item = [ordered]@{
            Scenario       = $scName
            Nodes          = $totHosts
            Router         = $rName
            Created        = "N/A"
            Delivered      = "N/A"
            DeliveryProb   = "N/A"
            OverheadRatio  = "N/A"
            LatencyAvg     = "N/A"
            LatencyMed     = "N/A"
            HopCountAvg    = "N/A"
            BufferTimeAvg  = "N/A"
            Dropped        = "N/A"
            Relayed        = "N/A"
            TxDeliveryCost = "N/A"
            CPI            = "N/A"
            CoreCost_C     = "N/A"
            BandwidthCost  = "N/A"
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
                if ($_ -match "(?:core_delivery_cost|composite_resource_cost).*:\s*([0-9.]+)") { $item.CoreCost_C = $matches[1] }
                if ($_ -match "bandwidth_delivery_cost.*:\s*([0-9.]+)") { $item.BandwidthCost = $matches[1] }
            }
        }
        
        $results += [PSCustomObject]$item
    }
}

$csvPath = "$reportsDir\Density_Scenarios_Comparison.csv"
$results | Export-Csv -Path $csvPath -NoTypeInformation
Write-Host "CSV generated: $csvPath"
$results | Format-Table -AutoSize
