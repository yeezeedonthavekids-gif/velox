# Logs GPU utilization, clocks, power and temperature once a second while a benchmark runs,
# using nvidia-smi from the NVIDIA driver (read-only; changes nothing). Stop with Ctrl+C.
#   .\gpu-log.ps1 -Out gpu-optimized-run1.csv
param([string] $Out = "gpu-$(Get-Date -Format yyyyMMdd-HHmmss).csv")
$smi = Get-Command nvidia-smi -ErrorAction SilentlyContinue
if (-not $smi) { Write-Error "nvidia-smi not found (it ships with the NVIDIA driver)"; exit 1 }
& nvidia-smi --query-gpu=timestamp,utilization.gpu,utilization.memory,clocks.gr,clocks.mem,power.draw,temperature.gpu,memory.used `
    --format=csv -l 1 -f $Out
