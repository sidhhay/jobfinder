# Creates the database tables for a fresh installation.
#
# WARNING: database\schema\setup.sql drops and recreates every table. This script therefore
# refuses to run when the schema already contains tables, unless -Force is given.
#
#   .\scripts\init-db.ps1 -User postgres -Database postgres
param(
    [string]$DbHost = "localhost",
    [int]$Port = 5432,
    [string]$User = "postgres",
    [string]$Database = "postgres",
    [string]$Schema = "jobful",
    [string]$Psql = "C:\Program Files\PostgreSQL\18\bin\psql.exe",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $Psql)) { $Psql = (Get-Command psql).Source }
if ($Schema -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { throw "Schema must be a plain identifier" }
if (-not $env:PGPASSWORD) {
    $secure = Read-Host "Password for $User" -AsSecureString
    $env:PGPASSWORD = [System.Net.NetworkCredential]::new("", $secure).Password
}
$connection = @("-h", $DbHost, "-p", $Port, "-U", $User, "-d", $Database, "-v", "ON_ERROR_STOP=1", "-q")

$count = & $Psql @connection -At -c "select count(*) from pg_tables where schemaname = '$Schema'"
if ($LASTEXITCODE -ne 0) { throw "Could not connect to the database" }
if ([int]$count -gt 0 -and -not $Force) {
    throw "Schema '$Schema' already has $count tables. Re-running setup would DELETE ALL DATA. Use -Force to do that."
}

& $Psql @connection -c "create extension if not exists citext with schema public" `
    -c "create extension if not exists pgcrypto with schema public" `
    -c "create schema if not exists $Schema"
if ($LASTEXITCODE -ne 0) { throw "Could not prepare the schema" }

$env:PGOPTIONS = "-c search_path=$Schema,public"
try {
    & $Psql @connection -f (Join-Path $PSScriptRoot "..\database\schema\setup.sql")
    if ($LASTEXITCODE -ne 0) { throw "setup.sql failed" }
} finally {
    Remove-Item Env:PGOPTIONS
}
Write-Host "Database ready in schema '$Schema'." -ForegroundColor Green
