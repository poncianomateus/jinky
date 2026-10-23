<#
  Opens the Jinky TV app in an Android TV emulator on this PC, to try the TV screens (look and
  navigation) without a TV. The PC's Jinky is reached at 10.0.2.2 from inside the emulator.

  Usage:  powershell -ExecutionPolicy Bypass -File scripts\tv-emulator.ps1 [-Build]
          -Build  rebuilds the TV app first (otherwise the last build is installed)

  Keys in the emulator window: arrows = D-pad, Enter = OK, Esc = Back. A DualSense works too,
  through jinky-padbridge (started here; build it with the engine).
  Needs: SVM (AMD-V) enabled in the BIOS and the Android Emulator hypervisor driver installed.
#>
param([switch]$Build)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME = "${env:ProgramFiles}\Android\Android Studio\jbr"
$env:ANDROID_SDK_ROOT = $sdk
$avd = 'JinkyTV'
$image = 'system-images;android-31;google-tv;x86'
$adb = "$sdk\platform-tools\adb.exe"
$emulator = "$sdk\emulator\emulator.exe"
$apk = "$root\tv\app\build\outputs\apk\release\app-release.apk"

if (-not (Test-Path $emulator)) { throw "Android emulator not installed (sdkmanager --install emulator)" }

# One-time: create the virtual TV (1080p Google TV, Android 12 like the TCL).
$avdDir = "$env:USERPROFILE\.android\avd\$avd.avd"
if (-not (Test-Path $avdDir)) {
    Write-Host "== Creating the virtual TV '$avd'"
    'no' | & "$sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd -n $avd -k $image -d tv_1080p --force | Out-Null
    Add-Content "$avdDir\config.ini" "hw.keyboard=yes`nhw.gpu.enabled=yes`nhw.gpu.mode=auto`nhw.ramSize=3072`nshowDeviceFrame=no"
}

if ($Build) {
    Write-Host "== Building the TV app"
    Push-Location "$root\tv"
    & .\gradlew.bat assembleRelease --console=plain -q
    Pop-Location
}

$running = (& $adb devices) -match '^emulator-\d+\s+device'
if (-not $running) {
    Write-Host "== Starting the emulator"
    Start-Process $emulator -ArgumentList "-avd $avd -no-snapshot-save -no-boot-anim -netdelay none -netspeed full"
    & $adb wait-for-device
    do { Start-Sleep 2 } until ((& $adb -e shell getprop sys.boot_completed 2>$null) -match '1')
}

Write-Host "== Installing Jinky"
& $adb -e install -r $apk | Out-Null
& $adb -e shell am start -n com.jinky.tv/com.tclstream.tv.MainActivity | Out-Null
Write-Host "== Jinky is open in the emulator window (arrows, Enter = OK, Esc = Back)"

# A DualSense on the PC drives the emulator too (it doesn't pass host controllers through).
$bridge = "$root\build\host\Release\jinky-padbridge.exe"
if ((Test-Path $bridge) -and -not (Get-Process jinky-padbridge -ErrorAction SilentlyContinue)) {
    Start-Process $bridge -WindowStyle Minimized
    Write-Host "== DualSense bridge on: D-pad/stick = arrows, Cross = OK, Circle = Back, Options = Menu"
}
