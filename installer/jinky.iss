; Jinky for Windows — installer (Inno Setup 6). Built by scripts\build-release.ps1, which passes
; /DAppVersion=<VERSION> and stages the files in dist\stage\app.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif

[Setup]
AppId={{6F2B4C1E-8E0A-4C55-9C61-2E7B3B9A5D41}
AppName=Jinky
AppVersion={#AppVersion}
AppVerName=Jinky {#AppVersion}
AppPublisher=Jinky
AppComments=Jogue os jogos do PC na TV
DefaultDirName={autopf}\Jinky
DisableProgramGroupPage=yes
DisableDirPage=auto
; Firewall rules and the controller driver need administrator rights.
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
OutputDir=..\dist
OutputBaseFilename=Jinky-Setup-{#AppVersion}
SetupIconFile=..\host\app\Assets\jinky.ico
UninstallDisplayIcon={app}\Jinky.exe
UninstallDisplayName=Jinky
WizardStyle=modern
Compression=lzma2/ultra64
SolidCompression=yes
; Jinky is closed cleanly by [Code] (it must restore the display and sound), not by Restart Manager.
CloseApplications=no
RestartApplications=no

[Languages]
Name: "pt"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Tasks]
Name: "desktopicon"; Description: "Criar um atalho na área de trabalho"
Name: "startup"; Description: "Iniciar o Jinky junto com o Windows (fica perto do relógio, pronto para a TV)"

[Files]
Source: "..\dist\stage\app\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion
; Virtual Xbox controller driver (ViGEmBus, BSD-3-Clause), only when not installed yet.
Source: "..\third_party\redist\ViGEmBus_1.22.0_x64_x86_arm64.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall; Check: not ViGEmBusInstalled

[Icons]
Name: "{autoprograms}\Jinky"; Filename: "{app}\Jinky.exe"
Name: "{autodesktop}\Jinky"; Filename: "{app}\Jinky.exe"; Tasks: desktopicon

[Registry]
; Same value the app's "Iniciar com o Windows" switch writes, so both stay in sync.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Jinky"; ValueData: """{app}\Jinky.exe"" --tray"; Tasks: startup; Flags: uninsdeletevalue

[Run]
; Firewall: only the home network (local subnet) may reach Jinky. Recreated on every install/update.
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Jinky (streaming)"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Jinky (atualizações das TVs)"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Jinky (streaming)"" dir=in action=allow program=""{app}\jinky-engine.exe"" protocol=UDP localport=47100 remoteip=localsubnet profile=any"; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Jinky (atualizações das TVs)"" dir=in action=allow program=""{app}\Jinky.exe"" protocol=TCP localport=47101 remoteip=localsubnet profile=any"; Flags: runhidden
Filename: "{tmp}\ViGEmBus_1.22.0_x64_x86_arm64.exe"; Parameters: "/exenoui /qn /norestart"; StatusMsg: "Instalando o driver de controle virtual (ViGEmBus)…"; Check: not ViGEmBusInstalled
; Interactive install: offer to open Jinky. Silent update (from the app): reopen it in the tray.
Filename: "{app}\Jinky.exe"; Description: "Abrir o Jinky"; Flags: nowait postinstall skipifsilent runasoriginaluser
Filename: "{app}\Jinky.exe"; Parameters: "--tray"; Flags: nowait runasoriginaluser; Check: WizardSilent

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Jinky (streaming)"""; Flags: runhidden; RunOnceId: "fw1"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Jinky (atualizações das TVs)"""; Flags: runhidden; RunOnceId: "fw2"

[Code]
function ViGEmBusInstalled: Boolean;
begin
  Result := RegKeyExists(HKLM, 'SYSTEM\CurrentControlSet\Services\ViGEmBus');
end;

{ Asks a running Jinky to quit cleanly ("Jinky.exe --quit"): it stops streaming and gives the PC
  its normal refresh rate and sound back. Kills it only if it does not exit in time. }
procedure CloseJinky(const Exe: String);
var
  Code: Integer;
begin
  if FileExists(Exe) then
    Exec(Exe, '--quit', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Sleep(1500);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/IM Jinky.exe /F', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/IM jinky-engine.exe /F', '', SW_HIDE, ewWaitUntilTerminated, Code);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  CloseJinky(ExpandConstant('{app}\Jinky.exe'));
  Result := '';
end;

function InitializeUninstall: Boolean;
begin
  CloseJinky(ExpandConstant('{app}\Jinky.exe'));
  Result := True;
end;
