<#
  Builds a Jinky release: engine, TV app, Windows app (self-contained) and the installer.

  Output: dist\Jinky-Setup-<version>.exe and its .sha256 (used by the app's updater), and
  dist\Jinky-TV-<version>.apk (the TV app, for first installs).
  Usage:  powershell -ExecutionPolicy Bypass -File scripts\build-release.ps1
  Needs:  Visual Studio 2022 (C++), Android Studio (JDK/SDK/NDK), .NET 10 SDK, Inno Setup 6,
          and the TV signing key in %USERPROFILE%\.jinky-signing (see docs\RELEASING.md).
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$version = (Get-Content "$root\VERSION" -Raw).Trim()
$parts = $version.Split('.') | ForEach-Object { [int]$_ }
$versionCode = $parts[0] * 10000 + $parts[1] * 100 + $parts[2]
Write-Host "== Jinky $version (TV versionCode $versionCode)"

function Step($name, [scriptblock]$body) {
    Write-Host "== $name"
    & $body
    if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "$name failed (exit $LASTEXITCODE)" }
}

$cmake = "${env:ProgramFiles}\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
$iscc = @("$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe", "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
$aapt = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools\*\aapt.exe" | Sort-Object FullName | Select-Object -Last 1
$env:JAVA_HOME = "${env:ProgramFiles}\Android\Android Studio\jbr"

Step "Protocol tests" {
    & $cmake -S "$root\proto" -B "$root\build\proto" | Out-Null
    & $cmake --build "$root\build\proto" --config Release | Out-Null
    & (Join-Path (Split-Path $cmake) 'ctest.exe') --test-dir "$root\build\proto" -C Release --output-on-failure
}

Step "Engine" {
    & $cmake -S "$root\host\engine" -B "$root\build\host" -G "Visual Studio 17 2022" -A x64 | Out-Null
    & $cmake --build "$root\build\host" --config Release | Out-Null
    $v = & "$root\build\host\Release\jinky-engine.exe" --version
    if ($v -ne "jinky-engine $version") { throw "engine reports '$v'" }
}

Step "TV app" {
    Push-Location "$root\tv"
    & .\gradlew.bat assembleRelease --console=plain -q
    Pop-Location
    $apk = "$root\tv\app\build\outputs\apk\release\app-release.apk"
    $badging = (& $aapt.FullName dump badging $apk | Select-Object -First 1)
    if ($badging -notmatch "versionCode='$versionCode'") { throw "APK is not version $versionCode : $badging" }
    $global:LASTEXITCODE = 0  # aapt exits non-zero even when it read the APK fine
    # Published with the release too: a TV's first install (later updates come from its PC).
    Copy-Item $apk "$root\dist\Jinky-TV-$version.apk" -Force
}

Step "Windows app (self-contained)" {
    $stage = "$root\dist\stage\app"
    if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
    # Own intermediate folder: never touches build\app, where a development Jinky may be running.
    & dotnet publish "$root\host\app\Jinky.csproj" -c Release -r win-x64 --self-contained true -o $stage -nologo -v quiet `
        "-p:OutputPath=$root\build\publish\"
    foreach ($f in @('Jinky.exe', 'jinky-engine.exe', 'jinky.ico', 'tv\jinky-tv.apk')) {
        if (-not (Test-Path "$stage\$f")) { throw "missing $f in the staged app" }
    }
}

Step "Installer" {
    & $iscc "/DAppVersion=$version" /Q "$root\installer\jinky.iss"
    $setup = "$root\dist\Jinky-Setup-$version.exe"
    $hash = (Get-FileHash $setup -Algorithm SHA256).Hash.ToLower()
    Set-Content -Path "$setup.sha256" -Value "$hash  Jinky-Setup-$version.exe" -Encoding ascii -NoNewline
    Write-Host ("== Done: {0} ({1:N1} MB)  sha256 {2}" -f $setup, ((Get-Item $setup).Length / 1MB), $hash)
}
