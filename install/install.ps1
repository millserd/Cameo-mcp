<#
.SYNOPSIS
  Installs (or removes) the Cameo MCP plugin into Cameo Systems Modeler / CATIA Magic 2024x or 2026x.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File install\install.ps1
  powershell -ExecutionPolicy Bypass -File install\install.ps1 -CameoHome "C:\Program Files\Cameo Systems Modeler"
  powershell -ExecutionPolicy Bypass -File install\install.ps1 -All
  powershell -ExecutionPolicy Bypass -File install\install.ps1 -Uninstall
#>
param(
    [string[]]$CameoHome,
    [switch]$All,
    [switch]$Uninstall,
    [switch]$NoClaude,
    [int]$Port = 18740
)
$ErrorActionPreference = 'Stop'
$PluginId = 'com.cameomcp.plugin'
$PackageRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$PluginSource = Join-Path $PackageRoot "plugin\$PluginId"

function Test-CameoHome([string]$dir) {
    return (Test-Path (Join-Path $dir 'plugins')) -and (Test-Path (Join-Path $dir 'lib'))
}

function Find-CameoHomes {
    $roots = @($env:ProgramFiles, ${env:ProgramFiles(x86)}, (Join-Path $env:LOCALAPPDATA 'Programs'), 'C:\', 'D:\') |
        Where-Object { $_ -and (Test-Path $_) }
    $pattern = 'Cameo|CATIA ?Magic|Magic ?Systems|MagicDraw|Magic Cyber|Magic Software|No ?Magic|Dassault'
    $found = @()
    foreach ($r in $roots) {
        foreach ($d in Get-ChildItem $r -Directory -ErrorAction SilentlyContinue | Where-Object Name -match $pattern) {
            if (Test-CameoHome $d.FullName) { $found += $d.FullName; continue }
            foreach ($sub in Get-ChildItem $d.FullName -Directory -ErrorAction SilentlyContinue) {
                if (Test-CameoHome $sub.FullName) { $found += $sub.FullName }
            }
        }
    }
    return $found | Sort-Object -Unique
}

if (-not $Uninstall -and -not (Test-Path (Join-Path $PluginSource 'plugin.xml'))) {
    throw "Plugin files not found at $PluginSource. Run this script from the unzipped cameo-mcp package."
}

if (-not $CameoHome) {
    $homes = @(Find-CameoHomes)
    if ($homes.Count -eq 0) {
        throw "No Cameo installation found. Pass -CameoHome '<install folder containing plugins and lib>'."
    }
    if ($homes.Count -eq 1 -or $All) {
        $CameoHome = $homes
    } else {
        Write-Host 'Several Cameo installations were found:'
        for ($i = 0; $i -lt $homes.Count; $i++) { Write-Host ("  [{0}] {1}" -f ($i + 1), $homes[$i]) }
        $answer = Read-Host 'Install into which one? Number, or A for all'
        if ($answer -match '^[Aa]$') { $CameoHome = $homes } else { $CameoHome = @($homes[[int]$answer - 1]) }
    }
}

foreach ($cameo in $CameoHome) {
    if (-not (Test-CameoHome $cameo)) { Write-Warning "$cameo is not a Cameo installation (no plugins\ and lib\). Skipped."; continue }
    $target = Join-Path $cameo "plugins\$PluginId"
    try {
        if (Test-Path $target) { Remove-Item $target -Recurse -Force }
        if ($Uninstall) {
            Write-Host "Removed Cameo MCP from $cameo"
            continue
        }
        Copy-Item $PluginSource $target -Recurse -Force
        Write-Host "Installed Cameo MCP into $target"
    } catch [System.UnauthorizedAccessException] {
        throw "No write access to $cameo\plugins. Re-run PowerShell as administrator."
    } catch {
        if ($_.Exception.Message -match 'denied') { throw "No write access to $cameo\plugins. Re-run PowerShell as administrator." }
        throw
    }
}

if ($Uninstall) {
    if (-not $NoClaude -and (Get-Command claude -ErrorAction SilentlyContinue)) {
        & claude mcp remove --scope user cameo 2>$null | Out-Null
    }
    Write-Host 'Done. Settings remain in ~\.cameo-mcp (delete that folder to remove them).'
    exit 0
}

$url = "http://127.0.0.1:$Port/mcp"
if (-not $NoClaude -and (Get-Command claude -ErrorAction SilentlyContinue)) {
    & claude mcp add --scope user --transport http cameo $url 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { Write-Host "Registered with Claude Code as 'cameo' -> $url" }
    else { Write-Host "Claude Code already has a 'cameo' server or registration failed; check with: claude mcp list" }
}

Write-Host ''
Write-Host 'Next steps:'
Write-Host '  1. Start Cameo. An MCP menu appears; the Notification window shows "[Cameo MCP] MCP server started".'
Write-Host '  2. MCP > MCP Server Status > Test connection.'
Write-Host "  3. Point your MCP client at $url (see the 'Connect a client' tab for ready-made configs)."
