@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..\..") do set "PROJECT_ROOT=%%~fI"
set "DEFAULT_AVD_NAME=Pixel_5_API_34"

set "USAGE_AVD_NAME=%DEFAULT_AVD_NAME%"
if defined AVD_NAME set "USAGE_AVD_NAME=%AVD_NAME%"

if /I "%~1"=="-h" goto :usage
if /I "%~1"=="--help" goto :usage

if not "%~1"=="" (
    set "AVD_NAME=%~1"
) else if not defined AVD_NAME (
    set "AVD_NAME=%DEFAULT_AVD_NAME%"
)

call :resolve_sdk_root SDK_ROOT
call :find_emulator_exe "%SDK_ROOT%" EMULATOR_EXE
if errorlevel 1 (
    >&2 echo Emulator executable not found: %SDK_ROOT%
    exit /b 1
)

"%EMULATOR_EXE%" -list-avds >nul 2>nul
if errorlevel 1 (
    >&2 echo Failed to list AVDs. Check SDK: %SDK_ROOT%
    exit /b 1
)

set "HAS_AVD="
set "FOUND_AVD="
for /f "usebackq delims=" %%A in (`"%EMULATOR_EXE%" -list-avds 2^>nul`) do (
    set "HAS_AVD=1"
    if "%%~A"=="!AVD_NAME!" set "FOUND_AVD=1"
)

if not defined HAS_AVD (
    >&2 echo No AVD was found. Create one before starting the emulator.
    exit /b 1
)

if not defined FOUND_AVD (
    >&2 echo AVD not found: !AVD_NAME!
    >&2 echo Available AVDs:
    "%EMULATOR_EXE%" -list-avds 1>&2
    exit /b 1
)

echo Starting AVD: !AVD_NAME!
echo SDK: %SDK_ROOT%
start "" "%EMULATOR_EXE%" -avd "!AVD_NAME!" -netdelay none -netspeed full
exit /b 0

:usage
echo Usage: %~nx0 [AVD_NAME]
echo.
echo Start the Android Emulator.
echo - AVD_NAME is optional. Default: %USAGE_AVD_NAME%
echo - You can also set ANDROID_SDK_ROOT or ANDROID_HOME
exit /b 0

:resolve_sdk_root
set "%~1="
if defined ANDROID_SDK_ROOT (
    set "%~1=%ANDROID_SDK_ROOT%"
    exit /b 0
)

if defined ANDROID_HOME (
    set "%~1=%ANDROID_HOME%"
    exit /b 0
)

if exist "%PROJECT_ROOT%\.dev-env\android-sdk\" (
    set "%~1=%PROJECT_ROOT%\.dev-env\android-sdk"
    exit /b 0
)

set "%~1=C:\Android\Sdk"
exit /b 0

:find_emulator_exe
set "%~2="
set "SDK_ROOT_CANDIDATE=%~1"

if exist "%SDK_ROOT_CANDIDATE%\emulator\emulator.exe" (
    set "%~2=%SDK_ROOT_CANDIDATE%\emulator\emulator.exe"
    exit /b 0
)

exit /b 1
