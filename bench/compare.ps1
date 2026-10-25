# Compare benchmark runs recorded by the Velox mod (F8 in game).
#   .\compare.ps1 -GameDir "$env:APPDATA\.velox\instances" -Scenario static `
#       -Baseline baseline\velox-benchmarks\static\baseline-balanced `
#       -Candidate default\velox-benchmarks\static\optimized-balanced
param(
    [Parameter(Mandatory)] [string] $Baseline,
    [Parameter(Mandatory)] [string] $Candidate,
    [string] $GameDir = "$env:APPDATA\.velox\instances",
    [double] $MinEffect = 1.0,
    [string] $Launcher = "$PSScriptRoot\..\launcher\build\libs\velox-launcher-0.1.0.jar"
)
$ErrorActionPreference = 'Stop'
$b = if ([IO.Path]::IsPathRooted($Baseline)) { $Baseline } else { Join-Path $GameDir $Baseline }
$c = if ([IO.Path]::IsPathRooted($Candidate)) { $Candidate } else { Join-Path $GameDir $Candidate }
java -jar $Launcher bench compare --baseline $b --candidate $c --min-effect $MinEffect
