<#
  Publishes dist\Jinky-Setup-<VERSION>.exe (+ .sha256) and the TV app as release v<VERSION> on the
  public releases repository.

  By default the release is a TEST (GitHub pre-release): only PCs with "Receber versoes de teste"
  turned on are offered it. Once tested, scripts\promote-release.ps1 makes it the stable release
  every installed Jinky updates to. -Stable publishes straight to everyone (urgent fixes).

  The TV app is attached as "Jinky-TV.apk" (same name in every release), so the stable one is always
  at https://github.com/<repo>/releases/latest/download/Jinky-TV.apk

  Usage: powershell -ExecutionPolicy Bypass -File scripts\publish-release.ps1 -Notes "what changed" [-Stable]
         -NotesFile notes.md reads the notes from a UTF-8 file instead.
#>
param([string]$Notes = "", [string]$NotesFile = "", [switch]$Stable)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$version = (Get-Content "$root\VERSION" -Raw).Trim()
$repo = (Select-String -Path "$root\host\app\Updater.cs" -Pattern 'Repository = "([^"]+)"').Matches[0].Groups[1].Value
$gh = "${env:ProgramFiles}\GitHub CLI\gh.exe"
$setup = "$root\dist\Jinky-Setup-$version.exe"
$apk = "$root\dist\Jinky-TV-$version.apk"

if ($repo -like 'OWNER/*') { throw "Set Updater.Repository in host\app\Updater.cs first" }
foreach ($f in @($setup, "$setup.sha256", $apk)) { if (-not (Test-Path $f)) { throw "missing $f - run build-release.ps1" } }
$expected = (Get-Content "$setup.sha256" -Raw).Split(' ')[0]
$actual = (Get-FileHash $setup -Algorithm SHA256).Hash.ToLower()
if ($expected -ne $actual) { throw "checksum file does not match the installer" }

# Same asset name in every release, for the fixed "latest" download link.
$stage = Join-Path $env:TEMP "jinky-publish-$version"
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item $apk "$stage\Jinky-TV.apk" -Force

$ghArgs = @("release", "create", "v$version", $setup, "$setup.sha256", "$stage\Jinky-TV.apk", "--repo", $repo,
          "--title", ("Jinky $version" + $(if ($Stable) { "" } else { " (teste)" })))
if ($NotesFile) { $ghArgs += @("--notes-file", $NotesFile) }
else { $ghArgs += @("--notes", $(if ($Notes) { $Notes } else { "Jinky $version" })) }
if ($Stable) { $ghArgs += "--latest" } else { $ghArgs += "--prerelease" }
& $gh @ghArgs
if ($LASTEXITCODE -ne 0) { throw "gh release create failed" }
Remove-Item $stage -Recurse -Force

if ($Stable) { Write-Host "== Published v$version to everyone: https://github.com/$repo/releases" }
else { Write-Host "== Published v$version as a TEST (only PCs with test versions on). When it is OK: scripts\promote-release.ps1" }
