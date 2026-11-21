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
;
; Setup commands: /firewall=yes|no   add the Windows Firewall rules without asking / never (a silent setup adds none)
; Uninstall:      /deletedata=yes    also delete data\ without asking
; The installer asks before it touches Windows Firewall, with the ports the server really uses (read from
; data\installation-ports.properties once the server has activated); the uninstaller removes the three KommServer-* rules.

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
Source: "stop-komm-server.ps1"; DestDir: "{app}"; Flags: ignoreversion

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
; First of all: stop the service and end everything the server has running from this folder. The service can stop and
; leave the server's Java, LiveKit and PostgreSQL behind, which keep the files open and make the folder impossible to
; delete. This also removes the service registration, so nothing starts the server again.
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; \
    Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\stop-komm-server.ps1"" -Root ""{app}"""; \
    Flags: runhidden waituntilterminated; RunOnceId: "StopKommServerEverything"
; The Windows Firewall rules the installer added when the user allowed it (KommServer-*); nothing happens if there are none.
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=KommServer-API"; Flags: runhidden; RunOnceId: "FirewallKommApi"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=KommServer-Voice-TCP"; Flags: runhidden; RunOnceId: "FirewallKommVoiceTcp"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=KommServer-Voice-UDP"; Flags: runhidden; RunOnceId: "FirewallKommVoiceUdp"
Filename: "{app}\kommserver.cmd"; Parameters: "uninstall-service"; Flags: runhidden; RunOnceId: "UninstallKommServerService"
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; \
    Parameters: "-NoProfile -Command ""Get-CimInstance Win32_Process -Filter \""Name='javaw.exe'\"" | Where-Object {{ $_.CommandLine -like '*komm-server-launcher.jar*tray*' } | ForEach-Object {{ Stop-Process -Id $_.ProcessId -Force }"""; \
    Flags: runhidden; RunOnceId: "StopKommServerTray"

[UninstallDelete]
; What jpack and the service wrapper create next to the installed files. Not data\.
Type: filesandordirs; Name: "{app}\update"
Type: filesandordirs; Name: "{app}\old"
Type: filesandordirs; Name: "{app}\trash"
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\launcher"
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

// The launcher ("kommserver <command>") for the installer. It is started directly on javaw.exe, not through
// komm-server.exe: the stub starts the launcher (a console program) with java.exe, and when its output is captured, as
// it is here, the stub has no console to give it, so Windows opens a window for it that stays up for the whole step.
// javaw.exe has no console at all. The launcher finds the installation from its own location.
function RunTool(Params: String; var Output: String): Boolean;
var
  ResultCode: Integer;
  Captured: TExecOutput;
  I: Integer;
  JavaHome, JavaW: String;
begin
  Output := '';
  JavaHome := GetEnv('JAVA_HOME');
  if (JavaHome <> '') and FileExists(AddBackslash(JavaHome) + 'bin\javaw.exe') then
    JavaW := AddBackslash(JavaHome) + 'bin\javaw.exe'
  else
    JavaW := 'javaw.exe';
  Result := ExecAndCaptureOutput(JavaW,
    '-XX:+UseSerialGC -Xmx96m -XX:TieredStopAtLevel=1 --enable-native-access=ALL-UNNAMED -jar "' +
    ExpandConstant('{app}\launcher\komm-server-launcher.jar') + '" ' + Params,
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

const
  ServiceName = 'KommServer';
  // What the server uses until the Komm client's choice reaches it (data\installation-ports.properties).
  DefaultApiPort = 8090;
  DefaultVoiceTcpPort = 7881;
  DefaultVoiceUdpPort = 7882;

function DataFile(Name: String): String;
begin
  Result := ExpandConstant('{app}\data\') + Name;
end;

// A value from data\installation-ports.properties: the ports chosen in the Komm client, written when the server
// activates with the hub. Falls back to the default when the file or the key is not there.
function PortFromFile(Key: String; Default: Integer): Integer;
var
  Lines: TArrayOfString;
  I: Integer;
  Line: String;
begin
  Result := Default;
  if not LoadStringsFromFile(DataFile('installation-ports.properties'), Lines) then
    exit;
  for I := 0 to GetArrayLength(Lines) - 1 do
  begin
    Line := Trim(Lines[I]);
    if Pos(Key + '=', Line) = 1 then
    begin
      Result := StrToIntDef(Trim(Copy(Line, Length(Key) + 2, Length(Line))), Default);
      exit;
    end;
  end;
end;

// While the hub does not accept the verification code the server serves nothing and says so in data\activation.status.
function ActivationRejected(var Reason: String): Boolean;
var
  Lines: TArrayOfString;
  I: Integer;
  Line: String;
begin
  Result := False;
  Reason := '';
  if not LoadStringsFromFile(DataFile('activation.status'), Lines) then
    exit;
  for I := 0 to GetArrayLength(Lines) - 1 do
  begin
    Line := Trim(Lines[I]);
    if Line = 'state=rejected' then
      Result := True
    else if Pos('message=', Line) = 1 then
      Reason := Copy(Line, 9, Length(Line));
  end;
  StringChangeEx(Reason, '\', '', True); // the file is a Java properties file: it escapes some characters
end;

function ServiceRunning(): Boolean;
var
  Captured: TExecOutput;
  ResultCode, I: Integer;
begin
  Result := False;
  if ExecAndCaptureOutput(ExpandConstant('{sys}\sc.exe'), 'query ' + ServiceName, '', SW_HIDE,
       ewWaitUntilTerminated, ResultCode, Captured) then
    for I := 0 to GetArrayLength(Captured.StdOut) - 1 do
      if Pos('RUNNING', Captured.StdOut[I]) > 0 then
        Result := True;
end;

function StartServerService(): Boolean;
var
  ResultCode: Integer;
begin
  // 1056: already running
  Result := Exec(ExpandConstant('{sys}\sc.exe'), 'start ' + ServiceName, '', SW_HIDE, ewWaitUntilTerminated, ResultCode)
    and ((ResultCode = 0) or (ResultCode = 1056));
end;

// Waits for the server to say it is up (data\ready, written once it is serving), up to two minutes, with a progress bar.
// Returns 0 when it is up, 1 when the hub rejected the code (Reason says why), 2 when it is still not up.
function WaitForServer(Page: TOutputProgressWizardPage; var Reason: String): Integer;
var
  I: Integer;
begin
  Result := 2;
  for I := 0 to 239 do
  begin
    if FileExists(DataFile('ready')) then
    begin
      Result := 0;
      exit;
    end;
    if ActivationRejected(Reason) then
    begin
      Result := 1;
      exit;
    end;
    if FileExists(DataFile('keys\tls-cert.pem')) then
      Page.SetText('Starting the server...', 'Starting the database and the voice server. This takes a little while the first time.')
    else
      Page.SetText('Activating with the hub...', 'Exchanging the certificate with the hub.');
    Page.SetProgress(25 + (I * 70) div 240, 100);
    Sleep(500);
  end;
  // Still not "ready": fine if the service is running (a server from before the ready file does not write one).
  if ServiceRunning() then
    Result := 0;
end;

function AddFirewallRule(Name, Protocol: String; Port: Integer): Boolean;
var
  ResultCode: Integer;
begin
  // Replace a rule left by an earlier install, whatever port it had.
  Exec(ExpandConstant('{sys}\netsh.exe'), 'advfirewall firewall delete rule name=' + Name, '', SW_HIDE,
    ewWaitUntilTerminated, ResultCode);
  Result := Exec(ExpandConstant('{sys}\netsh.exe'),
    'advfirewall firewall add rule name=' + Name + ' dir=in action=allow protocol=' + Protocol +
    ' localport=' + IntToStr(Port) + ' profile=any', '', SW_HIDE, ewWaitUntilTerminated, ResultCode)
    and (ResultCode = 0);
end;

// Asks, with the ports the server really uses, before touching Windows Firewall. /firewall=yes adds the rules without
// asking (unattended installs), /firewall=no never does; a silent install without either does not add them.
procedure OfferFirewallRules;
var
  Api, VoiceTcp, VoiceUdp: Integer;
  Choice: String;
  Allow: Boolean;
begin
  Choice := LowerCase(ExpandConstant('{param:firewall|ask}'));
  if Choice = 'no' then
    exit;
  Api := PortFromFile('server.port', DefaultApiPort);
  VoiceTcp := PortFromFile('sfu.tcp-port', DefaultVoiceTcpPort);
  VoiceUdp := PortFromFile('sfu.media-port', DefaultVoiceUdpPort);
  Allow := (Choice = 'yes');
  if not Allow then
    Allow := SuppressibleMsgBox(
      'Komm Server listens on these ports:' + #13#10#13#10 +
      '      TCP ' + IntToStr(Api) + '   the server' + #13#10 +
      '      TCP ' + IntToStr(VoiceTcp) + '   voice' + #13#10 +
      '      UDP ' + IntToStr(VoiceUdp) + '   voice' + #13#10#13#10 +
      'Windows Firewall keeps incoming connections to a background service out until they are allowed, so friends ' +
      'could not connect or hear each other.' + #13#10#13#10 +
      'Allow these ports in Windows Firewall now?' + #13#10#13#10 +
      'Uninstalling Komm Server removes the rules again. Your router still needs the same ports forwarded.',
      mbConfirmation, MB_YESNO, IDNO) = IDYES;
  if not Allow then
    exit;
  if not (AddFirewallRule('KommServer-API', 'TCP', Api)
      and AddFirewallRule('KommServer-Voice-TCP', 'TCP', VoiceTcp)
      and AddFirewallRule('KommServer-Voice-UDP', 'UDP', VoiceUdp)) then
    MsgBox('Windows Firewall could not be changed, so the ports are still closed. Allow TCP ' + IntToStr(Api) + ', TCP ' +
      IntToStr(VoiceTcp) + ' and UDP ' + IntToStr(VoiceUdp) + ' yourself in Windows Defender Firewall (Inbound Rules).',
      mbError, MB_OK);
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  Code, Output, Reason: String;
  ProgressPage: TOutputProgressWizardPage;
  Outcome: Integer;
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

  Outcome := -1;
  ProgressPage := CreateOutputProgressPage('Setting Up Komm Server',
    'Registering the service and starting the server. The first start activates it with the hub; this can take up to a minute.');
  ProgressPage.Show;
  try
    ProgressPage.SetText('Registering the Windows service...', '');
    ProgressPage.SetProgress(5, 100);
    if not RunTool('install-service', Output) then
    begin
      MsgBox('The Komm Server service could not be registered:' + #13#10#13#10 + Output, mbError, MB_OK);
      exit;
    end;
    ProgressPage.SetText('Starting the server...', '');
    ProgressPage.SetProgress(20, 100);
    // Forget what an earlier run left, so only this start's word counts.
    DeleteFile(DataFile('ready'));
    DeleteFile(DataFile('activation.status'));
    if not StartServerService() then
    begin
      MsgBox('The Komm Server service could not be started. See "kommserver logs" in a Command Prompt.', mbError, MB_OK);
      exit;
    end;
    Outcome := WaitForServer(ProgressPage, Reason);
  finally
    ProgressPage.Hide;
  end;

  if Outcome = 0 then
    OfferFirewallRules
  else if Outcome = 1 then
    MsgBox('The hub did not accept the verification code:' + #13#10#13#10 + Reason + #13#10#13#10 +
      'Copy the code from the Komm client again and run "kommserver install-service --code <code>" in a Command Prompt ' +
      'opened as Administrator. The server picks it up by itself.', mbError, MB_OK)
  else if Outcome = 2 then
    MsgBox('Komm Server is taking longer than expected to start. It keeps trying in the background; check with ' +
      '"kommserver status" in a Command Prompt.', mbInformation, MB_OK);
end;

// ── Uninstall ───────────────────────────────────────────────────────────────

var
  DeleteDataToo: Boolean;

// The server's data (database with every message, keys, uploads) is kept by default: removing it cannot be undone, and
// without the keys this installation cannot be activated again. /deletedata=yes removes it without asking.
function InitializeUninstall(): Boolean;
begin
  Result := True;
  DeleteDataToo := False;
  if not DirExists(ExpandConstant('{app}\data')) then
    exit;
  DeleteDataToo := (LowerCase(ExpandConstant('{param:deletedata|ask}')) = 'yes') or
    (SuppressibleMsgBox(
      'Also delete the server''s data?' + #13#10#13#10 +
      'That is the database with all messages, the keys and the uploaded files in ' + ExpandConstant('{app}\data') + '. ' +
      'This cannot be undone, and without the keys this installation cannot be activated again.' + #13#10#13#10 +
      'Choose No to keep it, for example to reinstall later.',
      mbConfirmation, MB_YESNO or MB_DEFBUTTON2, IDNO) = IDYES);
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  I: Integer;
begin
  if (CurUninstallStep = usPostUninstall) and DeleteDataToo then
  begin
    for I := 1 to 8 do
    begin
      DelTree(ExpandConstant('{app}'), True, True, True);
      if not DirExists(ExpandConstant('{app}\data')) then
        break;
      Sleep(1000);
    end;
  end;
end;

[Run]
Filename: "javaw.exe"; Parameters: "-jar ""{app}\launcher\komm-server-launcher.jar"" tray"; \
    Description: "Start the Komm Server tray icon now"; Flags: nowait postinstall skipifsilent
