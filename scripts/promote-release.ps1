<#
  Makes a tested release (published as a test by publish-release.ps1) the stable one: every
  installed Jinky is then offered it, and the fixed TV app link points to it.
  Usage: powershell -ExecutionPolicy Bypass -File scripts\promote-release.ps1 [-Version 0.6.7]
         (defaults to the version in VERSION)
#>
param([string]$Version = "")
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Version) { $Version = (Get-Content "$root\VERSION" -Raw).Trim() }
$repo = (Select-String -Path "$root\host\app\Updater.cs" -Pattern 'Repository = "([^"]+)"').Matches[0].Groups[1].Value
$gh = "${env:ProgramFiles}\GitHub CLI\gh.exe"

& $gh release edit "v$Version" --repo $repo --prerelease=false --latest --title "Jinky $Version"
if ($LASTEXITCODE -ne 0) { throw "gh release edit failed (is v$Version published?)" }
Write-Host "== v$Version is now the stable release for everyone: https://github.com/$repo/releases/latest"
