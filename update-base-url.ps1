<#
.SYNOPSIS
    Rewrites APP_BASE_URL in .env to this machine's current LAN IPv4 address.

.DESCRIPTION
    This network doesn't let us reserve a fixed IP (no router access), so the
    LAN IP can change whenever the laptop reconnects. Verification emails are
    built from eac.app.base-url (see application.properties), which is fed by
    APP_BASE_URL in .env (loaded via spring.config.import). If that's stuck on
    "localhost", links sent to anyone else are useless - they resolve
    localhost to their own device, not this laptop.

    Run this BEFORE starting the app (mvnw spring-boot:run, your IDE's run
    config, or before deploying the WAR to Tomcat) any time you're unsure the
    IP is still current - e.g. every time you reconnect to the network. It
    only touches the APP_BASE_URL line; MAIL_USERNAME/MAIL_APP_PASSWORD and
    everything else in .env is left alone.

.EXAMPLE
    .\update-base-url.ps1
    Detects the active adapter's IPv4, writes APP_BASE_URL=http://<ip>:8084
    into .env, and prints the new verification-link base URL.
#>

$ErrorActionPreference = 'Stop'

$envFile = Join-Path $PSScriptRoot '.env'
$port = 8084

# Pick the first non-virtual, non-loopback IPv4 on an adapter that's actually
# up - this is the address other devices on the same LAN can reach.
$candidate = Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object {
        $_.IPAddress -ne '127.0.0.1' -and
        $_.PrefixOrigin -ne 'WellKnown' -and
        (Get-NetAdapter -InterfaceIndex $_.InterfaceIndex -ErrorAction SilentlyContinue).Status -eq 'Up'
    } |
    Sort-Object -Property InterfaceIndex |
    Select-Object -First 1 -ExpandProperty IPAddress

if (-not $candidate) {
    Write-Error "Could not detect a LAN IPv4 address. Are you connected to a network?"
    exit 1
}

$newBaseUrl = "http://${candidate}:${port}"

if (-not (Test-Path $envFile)) {
    Write-Error ".env not found at $envFile - copy .env.example to .env first."
    exit 1
}

$lines = Get-Content $envFile
$found = $false
$updated = $lines | ForEach-Object {
    if ($_ -match '^\s*APP_BASE_URL\s*=') {
        $found = $true
        "APP_BASE_URL=$newBaseUrl"
    } else {
        $_
    }
}

if (-not $found) {
    $updated += "APP_BASE_URL=$newBaseUrl"
}

Set-Content -Path $envFile -Value $updated -Encoding utf8

Write-Host "APP_BASE_URL set to $newBaseUrl" -ForegroundColor Green
Write-Host "Verification emails sent after this point will link to that address."
Write-Host "Share this with friends on the same network: $newBaseUrl" -ForegroundColor Cyan
