# Jobfinder: builds and starts the Java backend and the Next.js frontend.
#   .\run.ps1            start both
#   .\run.ps1 -Rebuild   recompile the backend first
#   .\run.ps1 -Stop      stop the processes listening on ports 8000 and 3000
# Close the two server windows (or use -Stop) to shut down.
param(
    [switch]$Rebuild,
    [switch]$Stop
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$Backend = Join-Path $Root "backend"
$Frontend = Join-Path $Root "frontend"
$Jar = Join-Path $Backend "target\jobfinder-backend.jar"

function Write-Step { param([string]$Message) Write-Host "`n[ >> ] $Message" -ForegroundColor Cyan }

function Get-PortOwner {
    param([int]$Port)
    Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
}

if ($Stop) {
    foreach ($port in 8000, 3000) {
        foreach ($owner in Get-PortOwner $port) {
            Write-Host "Stopping process $owner on port $port"
            Stop-Process -Id $owner -Force
        }
    }
    return
}

# Use the portable toolchain next to this folder when present, otherwise the system installation.
$Kit = Join-Path (Split-Path $Root -Parent) "javakit"
if (Test-Path "$Kit\jdk\bin\java.exe") {
    $env:JAVA_HOME = "$Kit\jdk"
}
$Java = if ($env:JAVA_HOME) { "$env:JAVA_HOME\bin\java.exe" } else { (Get-Command java).Source }
$Maven = if (Test-Path "$Kit\maven\bin\mvn.cmd") { "$Kit\maven\bin\mvn.cmd" } else { (Get-Command mvn).Source }

foreach ($port in 8000, 3000) {
    if (Get-PortOwner $port) {
        throw "Port $port is already in use. Run .\run.ps1 -Stop first."
    }
}

if (-not (Test-Path "$Backend\.env")) {
    throw "backend\.env is missing. Copy backend\.env.example to backend\.env and fill it in."
}

if ($Rebuild -or -not (Test-Path $Jar)) {
    Write-Step "Building the backend"
    Push-Location $Backend
    try {
        & $Maven -B -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
    } finally {
        Pop-Location
    }
}

Write-Step "Checking the Chromium browser used for PDF export"
Push-Location $Backend
try {
    & $Java -cp $Jar "-Dloader.main=com.microsoft.playwright.CLI" `
        org.springframework.boot.loader.launch.PropertiesLauncher install chromium
    if ($LASTEXITCODE -ne 0) { Write-Warning "Chromium install failed; PDF export will not work." }
} finally {
    Pop-Location
}

if (-not (Test-Path "$Frontend\node_modules")) {
    Write-Step "Installing frontend packages"
    Push-Location $Frontend
    try {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw "npm ci failed" }
    } finally {
        Pop-Location
    }
}

Write-Step "Starting backend  -> http://localhost:8000"
Start-Process -FilePath $Java -ArgumentList "-jar", "target\jobfinder-backend.jar" -WorkingDirectory $Backend

Write-Step "Starting frontend -> http://localhost:3000"
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "npm run dev" -WorkingDirectory $Frontend

Write-Host "`nJobfinder is starting. Open http://localhost:3000" -ForegroundColor Green
