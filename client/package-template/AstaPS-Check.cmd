@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Connect-AstaPS.ps1" -CheckOnly
pause
