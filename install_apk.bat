@echo off
setlocal enabledelayedexpansion
title Install VCamdroid APK

echo ============================================
echo   Installing VCamdroid to Android Device
echo ============================================

set "ADB_EXE="
if exist "%~dp0..\adb\adb.exe" set "ADB_EXE=%~dp0..\adb\adb.exe"
if not defined ADB_EXE if exist "%~dp0adb\adb.exe" set "ADB_EXE=%~dp0adb\adb.exe"
if not defined ADB_EXE if exist "%~dp0windows\adb\adb.exe" set "ADB_EXE=%~dp0windows\adb\adb.exe"
if not defined ADB_EXE set "ADB_EXE=adb"

set "APK_PATH="
if exist "%~dp0..\apk\app-release.apk" set "APK_PATH=%~dp0..\apk\app-release.apk"
if not defined APK_PATH if exist "%~dp0dist\apk\app-release.apk" set "APK_PATH=%~dp0dist\apk\app-release.apk"
if not defined APK_PATH if exist "%~dp0android\app\build\outputs\apk\debug\app-debug.apk" set "APK_PATH=%~dp0android\app\build\outputs\apk\debug\app-debug.apk"

if not defined APK_PATH (
    echo [ERROR] Could not find VCamdroid APK.
    echo Please build the project or place app-release.apk in the apk folder.
    pause
    exit /b 1
)

echo [INFO] Using ADB: %ADB_EXE%
echo [INFO] Installing APK: %APK_PATH%
"%ADB_EXE%" install -r "%APK_PATH%"

if %ERRORLEVEL% equ 0 (
    echo.
    echo [SUCCESS] VCamdroid APK installed successfully!
) else (
    echo.
    echo [ERROR] Installation failed (Error code %ERRORLEVEL%).
    echo Ensure your phone is connected via USB and USB Debugging is authorized.
)

pause