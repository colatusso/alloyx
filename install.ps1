<#
  AlloyX installer for Windows (PowerShell 5.1+).

  One-liner:
    irm https://raw.githubusercontent.com/colatusso/alloyx/main/install.ps1 | iex

  What it does, with zero manual steps:
    1. ensures a JDK 21+ (installs Temurin 21 via winget if missing),
    2. downloads the latest allx-*.zip from GitHub Releases,
    3. installs it under %LOCALAPPDATA%\Programs\allx,
    4. adds bin\ to the user PATH (idempotent) so `allx` works from any folder.

  Re-running upgrades in place. No admin rights required.
#>

$ErrorActionPreference = 'Stop'
$ProgressPreference     = 'SilentlyContinue'   # Invoke-WebRequest is ~10x faster without the bar
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}

$Repo   = 'colatusso/alloyx'
$Dest   = Join-Path $env:LOCALAPPDATA 'Programs\allx'
$BinDir = Join-Path $Dest 'bin'

function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "    $m" -ForegroundColor Green }
function Note($m) { Write-Host "    $m" -ForegroundColor Yellow }

# --- 1) JDK 21+ -------------------------------------------------------------
# A JDK, not a JRE: AlloyX compiles Java at runtime, so it needs javac.
function Test-Jdk {
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if (-not $javac) { return $false }
    try {
        $out = (& javac -version 2>&1) | Out-String      # "javac 21.0.2"
        if ($out -match 'javac\s+(\d+)') { return [int]$Matches[1] -ge 21 }
    } catch {}
    return $false
}

Step 'Checking for a JDK 21+ (javac)...'
if (Test-Jdk) {
    Ok 'JDK 21+ found.'
} else {
    Note 'No JDK 21+ on PATH. Installing Eclipse Temurin 21 via winget...'
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        winget install --id EclipseAdoptium.Temurin.21.JDK -e `
            --accept-source-agreements --accept-package-agreements
        # pull the just-installed JDK into THIS session's PATH so the check below can see it
        $env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' +
                    [Environment]::GetEnvironmentVariable('Path','User')
        if (-not (Test-Jdk)) {
            Note 'Java was installed but is not visible in this session yet.'
            Note 'That is fine: a NEW terminal will pick it up. Continuing the install...'
        }
    } else {
        throw "winget is unavailable. Install a JDK 21 manually, then re-run: https://adoptium.net/temurin/releases/?version=21"
    }
}

# --- 2) locate the latest release ------------------------------------------
Step 'Finding the latest release...'
$api = @{ 'User-Agent' = 'allx-installer'; 'Accept' = 'application/vnd.github+json' }
$rel   = Invoke-RestMethod -Headers $api -Uri "https://api.github.com/repos/$Repo/releases/latest"
$asset = $rel.assets | Where-Object { $_.name -like 'allx-*.zip' } | Select-Object -First 1
if (-not $asset) { throw "No allx-*.zip asset on release $($rel.tag_name)." }
Ok "$($rel.tag_name) -> $($asset.name)"

# --- 3) download + install -------------------------------------------------
$tmp = Join-Path $env:TEMP ('allx-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
try {
    $zip = Join-Path $tmp $asset.name
    Step 'Downloading...'
    Invoke-WebRequest -Headers @{ 'User-Agent' = 'allx-installer' } `
        -Uri $asset.browser_download_url -OutFile $zip
    Ok 'Downloaded.'

    Step "Installing into $Dest ..."
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    # the zip carries a single root folder allx-<version>\ holding bin\ and lib\
    $root = Get-ChildItem $tmp -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\allx.bat') } |
        Select-Object -First 1
    if (-not $root) { throw 'Unexpected archive layout (no bin\allx.bat).' }

    if (Test-Path $Dest) { Remove-Item $Dest -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $Dest | Out-Null
    Copy-Item -Path (Join-Path $root.FullName '*') -Destination $Dest -Recurse -Force
    Ok 'Files installed.'
} finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

# --- 4) PATH (idempotent) --------------------------------------------------
Step 'Configuring PATH...'
$userPath = [Environment]::GetEnvironmentVariable('Path','User')
if (($userPath -split ';') -notcontains $BinDir) {
    $newPath = if ([string]::IsNullOrEmpty($userPath)) { $BinDir } else { "$userPath;$BinDir" }
    [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
    Ok "Added to PATH: $BinDir"
} else {
    Ok 'Already on PATH.'
}
if (($env:Path -split ';') -notcontains $BinDir) { $env:Path += ";$BinDir" }

Write-Host ''
Write-Host 'AlloyX is installed.' -ForegroundColor Green
Write-Host 'Open a NEW terminal and run:  allx --version' -ForegroundColor Green
