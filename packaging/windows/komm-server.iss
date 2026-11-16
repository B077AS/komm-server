; Inno Setup script: Komm-Server-Setup.exe around the jpack install folder.
; Built by .github/workflows/release.yml:
;   ISCC.exe /DAppVersion=X /DImageDir=<target\jpack\image\windows-x64> /DWinSW=<WinSW-x64.exe> /DOutputDir=<dir> komm-server.iss
;
; Installs everything into one folder (default C:\ProgramData\Komm\Server, changeable):
;   komm-server.exe, jpack.json, app\, launcher\   the jpack install (updated in place by `kommserver update`)
;   komm-server-service.exe (+ .xml)              WinSW: the Windows Service that runs komm-server.exe
;   kommserver.cmd                                the command (the folder is added to PATH)
;   data\                                         everything the server writes; kept on uninstall
; Needs Java 21 or newer on the machine: no Java is bundled.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef ImageDir
  #define ImageDir "..\..\target\jpack\image\windows-x64"
#endif
#ifndef WinSW
  #define WinSW "..\..\target\WinSW-x64.exe"
#endif
#ifndef OutputDir
  #define OutputDir "..\..\target\installer"
#endif

[Setup]
; Same AppId as the old komm-server-launcher installer, so this one upgrades it in place.
AppId={{B6C1B6F4-6B3E-4A0D-9C7B-1A2B3C4D5E6F}
AppName=Komm Server
AppVersion={#AppVersion}
AppPublisher=Komm
DefaultDirName={commonappdata}\Komm\Server
DisableProgramGroupPage=yes
OutputDir={#OutputDir}
OutputBaseFilename=Komm-Server-Setup
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern
SetupIconFile=..\..\src\jpack\icon.ico
UninstallDisplayIcon={app}\komm-server.exe
CloseApplications=yes

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#ImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#WinSW}"; DestDir: "{app}"; DestName: "komm-server-service.exe"; Flags: ignoreversion
Source: "kommserver.cmd"; DestDir: "{app}"; Flags: ignoreversion

[Dirs]
Name: "{app}\data"; Flags: uninsneveruninstall

[Icons]
; A prompt that stays open (/k) where kommserver commands can be typed.
Name: "{group}\Komm Server"; Filename: "{sys}\cmd.exe"; Parameters: "/k ""{app}\kommserver.cmd"" status"; \
    WorkingDir: "{app}"; IconFilename: "{app}\komm-server.exe"
Name: "{autodesktop}\Komm Server"; Filename: "{sys}\cmd.exe"; Parameters: "/k ""{app}\kommserver.cmd"" status"; \
    WorkingDir: "{app}"; IconFilename: "{app}\komm-server.exe"; Tasks: desktopicon
; The tray icon at sign-in. A shortcut cannot set JPACK_START, so it runs the tool's jar with javaw directly; the tool
; finds the installation from its own location.
Name: "{commonstartup}\Komm Server Tray"; Filename: "javaw.exe"; \
    Parameters: "-jar ""{app}\launcher\komm-server-launcher.jar"" tray"; WorkingDir: "{app}"; IconFilename: "{app}\komm-server.exe"

[Registry]
; {app} on the machine PATH, for `kommserver` in any new terminal.
Root: HKLM; Subkey: "SYSTEM\CurrentControlSet\Control\Session Manager\Environment"; \
    ValueType: expandsz; ValueName: "Path"; ValueData: "{olddata};{app}"; Check: NeedsAddPath('{app}')

[UninstallRun]
Filename: "{app}\kommserver.cmd"; Parameters: "uninstall-service"; Flags: runhidden; RunOnceId: "UninstallKommServerService"
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; \
    Parameters: "-NoProfile -Command ""Get-CimInstance Win32_Process -Filter \""Name='javaw.exe'\"" | Where-Object {{ $_.CommandLine -like '*komm-server-launcher.jar*tray*' } | ForEach-Object {{ Stop-Process -Id $_.ProcessId -Force }"""; \
    Flags: runhidden; RunOnceId: "StopKommServerTray"

[UninstallDelete]
; What jpack and the service wrapper create next to the installed files. Not data\.
Type: filesandordirs; Name: "{app}\update"
Type: filesandordirs; Name: "{app}\old"
Type: filesandordirs; Name: "{app}\trash"
Type: files; Name: "{app}\jpack*"
Type: files; Name: "{app}\komm-server.exe.old"
Type: files; Name: "{app}\komm-server-service.xml"
Type: files; Name: "{app}\kommserver-update.properties"

[Code]
var
  ActivationPage: TInputQueryWizardPage;

const
  WM_SETTINGCHANGE = $001A;
  SMTO_ABORTIFHUNG = $0002;

function SendMessageTimeoutA(hWnd: Longint; Msg: Longint; wParam: Longint; lParam: PAnsiChar;
  fuFlags, uTimeout: Longint; var lpdwResult: Longint): Longint;
  external 'SendMessageTimeoutA@user32.dll stdcall';

// Tells Explorer the PATH changed, so terminals opened from now on find `kommserver` without signing out.
procedure RefreshEnvironment;
var
  ResultCode: Longint;
begin
  SendMessageTimeoutA(HWND_BROADCAST, WM_SETTINGCHANGE, 0, 'Environment', SMTO_ABORTIFHUNG, 5000, ResultCode);
end;

function NeedsAddPath(Param: string): boolean;
var
  OrigPath: string;
begin
  if not RegQueryStringValue(HKLM, 'SYSTEM\CurrentControlSet\Control\Session Manager\Environment', 'Path', OrigPath) then
  begin
    Result := True;
    exit;
  end;
  Result := Pos(';' + Param + ';', ';' + OrigPath + ';') = 0;
end;

// The stub finds Java through JAVA_HOME, then java.exe on the PATH. Without either the server cannot start.
function JavaFound(): Boolean;
var
  JavaHome: String;
begin
  JavaHome := GetEnv('JAVA_HOME');
  Result := ((JavaHome <> '') and FileExists(AddBackslash(JavaHome) + 'bin\java.exe'))
    or (FileSearch('java.exe', GetEnv('PATH')) <> '');
end;

function InitializeSetup(): Boolean;
begin
  Result := True;
  if not JavaFound() then
  begin
    MsgBox('Komm Server needs Java 21 or newer, and none was found (no JAVA_HOME, no java.exe on the PATH).' + #13#10#13#10 +
      'Install Java first (for example Eclipse Temurin 21 from adoptium.net, with "Set JAVA_HOME" ticked), then run this setup again.',
      mbError, MB_OK);
    Result := False;
  end;
end;

// The hub's certificate in data\keys means this server is activated; a waiting setup token means a code was given.
function NeedsActivation(): Boolean;
begin
  Result := (not FileExists(ExpandConstant('{app}\data\keys\tls-cert.pem')))
    and (not FileExists(ExpandConstant('{app}\data\setup-token.txt')));
end;

procedure InitializeWizard;
begin
  ActivationPage := CreateInputQueryPage(wpSelectTasks,
    'Server Activation', 'Enter your verification code',
    'Paste the verification code from the Komm client so this server can activate. Leave it blank to do it later ' +
    'with "kommserver install-service" in a Command Prompt opened as Administrator.');
  ActivationPage.Add('Verification code:', False);
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := False;
  if PageID = ActivationPage.ID then
    Result := not NeedsActivation();
end;

function RunTool(Params: String; var Output: String): Boolean;
var
  ResultCode: Integer;
  Captured: TExecOutput;
  I: Integer;
begin
  Output := '';
  Result := ExecAndCaptureOutput(ExpandConstant('{sys}\cmd.exe'), '/c ""' + ExpandConstant('{app}\kommserver.cmd') + '" ' + Params + '"',
    ExpandConstant('{app}'), SW_HIDE, ewWaitUntilTerminated, ResultCode, Captured) and (ResultCode = 0);
  for I := 0 to GetArrayLength(Captured.StdOut) - 1 do
    Output := Output + Captured.StdOut[I] + #13#10;
  for I := 0 to GetArrayLength(Captured.StdErr) - 1 do
    Output := Output + Captured.StdErr[I] + #13#10;
end;

// The old komm-server-launcher installer kept the server's files directly in {app} (it ran `java -jar` there).
// Before the new files go in: stop that server and move its data into {app}\data, where the server now looks.
procedure MoveOldLayout;
var
  App, Data: String;
  ResultCode: Integer;
  Names: TArrayOfString;
  I: Integer;
begin
  App := ExpandConstant('{app}');
  Data := App + '\data';
  if not (DirExists(App + '\keys') or DirExists(App + '\komm-postgres-data')) then
    exit;
  Exec(ExpandConstant('{sys}\sc.exe'), 'stop KommServer', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Sleep(5000);
  Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
    '-NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name=''javaw.exe''\" | Where-Object { $_.CommandLine -like ''*komm-server-launcher.jar*tray*'' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"',
    '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  ForceDirectories(Data);
  SetArrayLength(Names, 6);
  Names[0] := 'keys';
  Names[1] := 'komm-postgres-data';
  Names[2] := 'uploads';
  Names[3] := 'logs';
  Names[4] := 'installation-ports.properties';
  Names[5] := 'setup-token.txt';
  for I := 0 to GetArrayLength(Names) - 1 do
    if (FileExists(App + '\' + Names[I]) or DirExists(App + '\' + Names[I]))
       and not (FileExists(Data + '\' + Names[I]) or DirExists(Data + '\' + Names[I])) then
      if not RenameFile(App + '\' + Names[I], Data + '\' + Names[I]) then
        MsgBox('Could not move ' + App + '\' + Names[I] + ' to ' + Data + '. Move it by hand before starting the server.', mbError, MB_OK);
  // What the old installer put there and nothing uses any more (sfu\ is unpacked again into data\ at start).
  DeleteFile(App + '\komm-server.jar');
  DeleteFile(App + '\komm-server-launcher.jar');
  DeleteFile(App + '\launcher.properties');
  DeleteFile(App + '\activate.cmd');
  DeleteFile(App + '\stop-tray.ps1');
  DeleteFile(App + '\icon.ico');
  DelTree(App + '\sfu', True, True, True);
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  Code, Output: String;
  ProgressPage: TOutputProgressWizardPage;
begin
  if CurStep = ssInstall then
  begin
    MoveOldLayout;
    exit;
  end;
  if CurStep <> ssPostInstall then
    exit;
  RefreshEnvironment;
  Code := Trim(ActivationPage.Values[0]);
  if Code <> '' then
    SaveStringToFile(ExpandConstant('{app}\data\setup-token.txt'), Code, False);
  if NeedsActivation() then
    exit; // no code: install-service would only ask for one; the status shortcut says what to run

  ProgressPage := CreateOutputProgressPage('Setting Up Komm Server',
    'Registering the service and starting the server. The first start activates it with the hub and restarts once; ' +
    'this can take up to a minute.');
  ProgressPage.ProgressBar.Style := npbstMarquee;
  ProgressPage.Show;
  try
    ProgressPage.SetText('Registering the service...', '');
    if not RunTool('install-service', Output) then
    begin
      MsgBox('The Komm Server service could not be registered:' + #13#10#13#10 + Output, mbError, MB_OK);
      exit;
    end;
    ProgressPage.SetText('Starting the server...', '');
    if not RunTool('start', Output) then
      MsgBox('Komm Server did not come up:' + #13#10#13#10 + Output + #13#10 +
        'See "kommserver logs" in a Command Prompt.', mbError, MB_OK);
  finally
    ProgressPage.Hide;
  end;
end;

[Run]
Filename: "javaw.exe"; Parameters: "-jar ""{app}\launcher\komm-server-launcher.jar"" tray"; \
    Description: "Start the Komm Server tray icon now"; Flags: nowait postinstall skipifsilent
