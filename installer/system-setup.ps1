<#
  What Jinky needs once per PC, whoever installs it (needs administrator rights; the installer runs
  it elevated): firewall rules for the home network and the virtual controller driver.
    - Firewall: only the local subnet may reach Jinky, on its ports: 47100/47101 for the first
      Jinky on the PC, 47110/47111... for more Windows users / ASTER workplaces at once. By port,
      not by program, so every user's own install is covered.
    - ViGEmBus (virtual Xbox controllers), when not installed yet.
  Usage: powershell -NoProfile -ExecutionPolicy Bypass -File system-setup.ps1 [-ViGEm <installer.exe>]
#>
param([string]$ViGEm = "")
$netsh = "$env:SystemRoot\System32\netsh.exe"
& $netsh advfirewall firewall delete rule name="Jinky (streaming)" | Out-Null
& $netsh advfirewall firewall delete rule name="Jinky (atualizacoes das TVs)" | Out-Null
# Older installers used an accented rule name; remove it too.
& $netsh advfirewall firewall delete rule name="Jinky (atualiza$([char]0xE7)$([char]0xF5)es das TVs)" | Out-Null
& $netsh advfirewall firewall add rule name="Jinky (streaming)" dir=in action=allow protocol=UDP localport=47100-47139 remoteip=localsubnet profile=any | Out-Null
& $netsh advfirewall firewall add rule name="Jinky (atualizacoes das TVs)" dir=in action=allow protocol=TCP localport=47100-47139 remoteip=localsubnet profile=any | Out-Null
if ($ViGEm -and (Test-Path $ViGEm) -and -not (Test-Path "HKLM:\SYSTEM\CurrentControlSet\Services\ViGEmBus")) {
    Start-Process -FilePath $ViGEm -ArgumentList "/exenoui", "/qn", "/norestart" -Wait
}
