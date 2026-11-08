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
; "Só para mim" by default: Jinky inside this Windows user's profile (%LOCALAPPDATA%\Programs), so
; each Windows user / ASTER workplace has its own Jinky, with its own version and updates.
; "Para todos os usuários" (Program Files) stays as an option. What the whole PC needs once
; (firewall rules, virtual controller driver) is done by system-setup.ps1, elevated only if missing.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog commandline
; Always ask (an all-users install on the PC must not decide for another Windows user).
UsePreviousPrivileges=no
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
Source: "system-setup.ps1"; DestDir: "{tmp}"; Flags: deleteafterinstall; Check: NeedsSystemSetup

[Icons]
; Only for the Windows user installing, even "para todos": another user (another ASTER workplace)
; has their own Jinky and must not see this one's shortcuts.
Name: "{userprograms}\Jinky"; Filename: "{app}\Jinky.exe"
Name: "{userdesktop}\Jinky"; Filename: "{app}\Jinky.exe"; Tasks: desktopicon

[InstallDelete]
; Shortcuts older installers put on every user's desktop and Start menu.
Type: files; Name: "{commondesktop}\Jinky.lnk"; Check: IsAdminInstallMode
Type: files; Name: "{commonprograms}\Jinky.lnk"; Check: IsAdminInstallMode

[Dirs]
; Where the Jinkys of the PC's Windows users / ASTER workplaces see each other (a workplace
; without a screen is run from another one). Every user may write there.
Name: "{commonappdata}\Jinky\lugares"; Permissions: users-modify; Check: IsAdminInstallMode

[Registry]
; ASTER PC, installed for all users: Jinky starts in every workplace's Windows account, so a
; workplace without a screen of its own (a "TV" one) has its Jinky up without anyone there.
Root: HKLM; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Jinky"; ValueData: """{app}\Jinky.exe"" --tray"; Flags: uninsdeletevalue; Check: IsAdminInstallMode and IsMultiseat
; Same value the app's "Iniciar com o Windows" switch writes, so both stay in sync.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Jinky"; ValueData: """{app}\Jinky.exe"" --tray"; Tasks: startup; Flags: uninsdeletevalue

[Run]
; Once per PC (firewall rules for the home network, virtual controller driver), only if missing:
; directly when installing for all users, else elevated (Windows asks for permission once).
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{tmp}\system-setup.ps1"" -ViGEm ""{tmp}\ViGEmBus_1.22.0_x64_x86_arm64.exe"""; StatusMsg: "Preparando o firewall e o controle virtual…"; Flags: runhidden waituntilterminated; Check: IsAdminInstallMode and NeedsSystemSetup
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{tmp}\system-setup.ps1"" -ViGEm ""{tmp}\ViGEmBus_1.22.0_x64_x86_arm64.exe"""; StatusMsg: "Preparando o firewall e o controle virtual (o Windows vai pedir permissão)…"; Verb: "runas"; Flags: shellexec runhidden waituntilterminated; Check: (not IsAdminInstallMode) and NeedsSystemSetup
; Interactive install: offer to open Jinky. Silent update (from the app): reopen it in the tray.
Filename: "{app}\Jinky.exe"; Description: "Abrir o Jinky"; Flags: nowait postinstall skipifsilent runasoriginaluser
Filename: "{app}\Jinky.exe"; Parameters: "--tray"; Flags: nowait runasoriginaluser; Check: WizardSilent

; Uninstalling keeps the firewall rules and the driver: other Windows users' own Jinky use them.

[Code]
var
  SystemSetupChecked, SystemSetupNeeded: Boolean;

{ ASTER (several workplaces on one PC) is installed: its kernel driver's service. }
function IsMultiseat: Boolean;
begin
  Result := RegKeyExists(HKLM, 'SYSTEM\CurrentControlSet\Services\MUTENX_SERVICE');
end;

function ViGEmBusInstalled: Boolean;
begin
  Result := RegKeyExists(HKLM, 'SYSTEM\CurrentControlSet\Services\ViGEmBus');
end;

{ The firewall rule for all of Jinky's ports (47100-47139) exists. Reading rules needs no admin. }
function FirewallReady: Boolean;
var
  Code: Integer;
begin
  Result := Exec(ExpandConstant('{cmd}'), '/c netsh advfirewall firewall show rule name="Jinky (streaming)" | findstr /c:"47100-47139"',
                 '', SW_HIDE, ewWaitUntilTerminated, Code) and (Code = 0);
end;

{ Something the whole PC needs is missing (decided once, before anything is changed). }
function NeedsSystemSetup: Boolean;
begin
  if not SystemSetupChecked then
  begin
    SystemSetupNeeded := (not ViGEmBusInstalled) or (not FirewallReady);
    SystemSetupChecked := True;
  end;
  Result := SystemSetupNeeded;
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
  { Only this installation's processes: another Windows user's own Jinky ("Só para mim" install)
    keeps running. Other users of this same installation are closed: its files are replaced. }
  Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
       '-NoProfile -NonInteractive -Command "Get-Process Jinky,jinky-engine -ErrorAction SilentlyContinue | ' +
       'Where-Object { $_.Path -like ''' + ExpandConstant('{app}') + '\*'' } | Stop-Process -Force"',
       '', SW_HIDE, ewWaitUntilTerminated, Code);
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

{ The permission for the PC-wide part was refused: say what is missing and how to fix it. }
procedure CurStepChanged(CurStep: TSetupStep);
begin
  if (CurStep = ssPostInstall) and not WizardSilent and ((not ViGEmBusInstalled) or (not FirewallReady)) then
    MsgBox('O Jinky foi instalado, mas falta uma parte que vale para o PC inteiro (firewall da rede de casa ' +
           'e driver do controle virtual), que precisa da permissão de administrador do Windows. ' +
           'Rode o instalador de novo e aceite a permissão quando o Windows pedir.', mbInformation, MB_OK);
end;
