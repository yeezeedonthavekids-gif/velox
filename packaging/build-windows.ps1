# Builds the Windows installer (Velox-<version>.exe) and a portable zip from the launcher jar.
# Requires a JDK with jpackage (21+) and WiX Toolset 3.x on PATH (CI installs both).
#   .\packaging\build-windows.ps1 -Jar launcher\build\libs\velox-launcher-0.1.0.jar
param(
    [Parameter(Mandatory)] [string] $Jar,
    [string] $Version = "0.1.0",
    [string] $Out = "dist"
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$staging = Join-Path $env:TEMP "velox-jpackage-input"
Remove-Item -Recurse -Force $staging, $Out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $staging, $Out | Out-Null
Copy-Item $Jar (Join-Path $staging "velox-launcher.jar")

$common = @(
    '--name', 'Velox',
    '--app-version', $Version,
    '--vendor', 'Velox',
    '--description', 'Velox - performance-focused Minecraft launcher',
    '--copyright', 'MIT License',
    '--input', $staging,
    '--main-jar', 'velox-launcher.jar',
    '--main-class', 'dev.velox.launcher.Main',
    '--icon', (Join-Path $here 'velox.ico'),
    # A trimmed Java runtime is bundled, so users don't need Java installed.
    '--add-modules', (Get-Content (Join-Path $here 'modules.txt') -Raw).Trim(),
    '--jlink-options', '--strip-debug --no-man-pages --no-header-files --compress=zip-6',
    # The launcher itself is small; the game gets its own JVM and heap.
    '--java-options', '-Xmx256m'
)

# Portable build: a folder with Velox.exe, zipped.
jpackage --type app-image @common --dest $Out
if ($LASTEXITCODE -ne 0) { throw "jpackage app-image failed" }
Compress-Archive -Path (Join-Path $Out 'Velox') -DestinationPath (Join-Path $Out "Velox-$Version-portable.zip")

# Installer: per-user (no admin prompt), Start Menu + desktop shortcuts, uninstaller, upgrades in place.
jpackage --type exe @common --dest $Out `
    --win-per-user-install --win-menu --win-menu-group 'Velox' --win-shortcut --win-dir-chooser `
    --win-upgrade-uuid '6677ae0f-bbdb-491a-ade7-d2a82da0c91e' `
    --license-file (Join-Path $here '..\LICENSE') `
    --about-url 'https://github.com/yeezeedonthavekids-gif/velox'
if ($LASTEXITCODE -ne 0) { throw "jpackage exe failed" }

Remove-Item -Recurse -Force (Join-Path $Out 'Velox')
Get-ChildItem $Out | Format-Table Name, Length
