@echo off
rem The kommserver command: runs komm-server's tool through the installation's jpack stub.
rem Keep this file ASCII-only: cmd.exe parses it with the current codepage until chcp has run.
chcp 65001 >nul
setlocal
set "JPACK_START=launcher"
"%~dp0komm-server.exe" %*
exit /b %errorlevel%
