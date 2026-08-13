@echo off
setlocal enabledelayedexpansion
set "GRADLE_VERSION=8.14.3"
set "DIST_NAME=gradle-%GRADLE_VERSION%-bin.zip"
set "DIST_URL=https://services.gradle.org/distributions/%DIST_NAME%"
if defined GRADLE_USER_HOME (
  set "CACHE_ROOT=%GRADLE_USER_HOME%\bootstrap\%GRADLE_VERSION%"
) else (
  set "CACHE_ROOT=%USERPROFILE%\.gradle\bootstrap\%GRADLE_VERSION%"
)
set "GRADLE_HOME=%CACHE_ROOT%\gradle-%GRADLE_VERSION%"
set "ZIP_PATH=%CACHE_ROOT%\%DIST_NAME%"

if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  if not exist "%CACHE_ROOT%" mkdir "%CACHE_ROOT%"
  if not exist "%ZIP_PATH%" (
    where curl >nul 2>nul
    if errorlevel 1 (
      echo curl is required to download Gradle %GRADLE_VERSION%. 1>&2
      exit /b 1
    )
    curl --fail --location --retry 3 --output "%ZIP_PATH%" "%DIST_URL%"
    if errorlevel 1 exit /b 1
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%ZIP_PATH%' -DestinationPath '%CACHE_ROOT%' -Force"
  if errorlevel 1 exit /b 1
)

call "%GRADLE_HOME%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
