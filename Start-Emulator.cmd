@echo off
setlocal
title RUSTREAM - Android TV testen
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-emulator.ps1" %*
if errorlevel 1 (
    echo.
    echo Der Start ist fehlgeschlagen. Details stehen oben.
    pause
    exit /b 1
)
exit /b 0
