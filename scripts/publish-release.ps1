<#
  Publishes dist\Jinky-Setup-<VERSION>.exe (+ .sha256) as release v<VERSION> on the public releases
  repository, where every installed Jinky looks for updates.
  Usage: powershell -ExecutionPolicy Bypass -File scripts\publish-release.ps1 -Notes "what changed"
#>
param([string]$Notes = "")
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$version = (Get-Content "$root\VERSION" -Raw).Trim()
$repo = (Select-String -Path "$root\host\app\Updater.cs" -Pattern 'Repository = "([^"]+)"').Matches[0].Groups[1].Value
$gh = "${env:ProgramFiles}\GitHub CLI\gh.exe"
$setup = "$root\dist\Jinky-Setup-$version.exe"

if ($repo -like 'OWNER/*') { throw "Set Updater.Repository in host\app\Updater.cs first" }
foreach ($f in @($setup, "$setup.sha256")) { if (-not (Test-Path $f)) { throw "missing $f — run build-release.ps1" } }
$expected = (Get-Content "$setup.sha256" -Raw).Split(' ')[0]
$actual = (Get-FileHash $setup -Algorithm SHA256).Hash.ToLower()
if ($expected -ne $actual) { throw "checksum file does not match the installer" }

if (-not $Notes) { $Notes = "Jinky $version" }
& $gh release create "v$version" $setup "$setup.sha256" --repo $repo --title "Jinky $version" --notes $Notes
if ($LASTEXITCODE -ne 0) { throw "gh release create failed" }
Write-Host "== Published v$version to https://github.com/$repo/releases"
