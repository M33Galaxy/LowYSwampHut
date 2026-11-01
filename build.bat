@echo off
REM Full product package: Gradle builds the Base DLL + standalone CLI + the
REM runnable LowYSwampHut-<version>.jar, then stages dist\.
REM Double-click this file, or run it from a console at the repository root.
setlocal
cd /d "%~dp0"

echo ============================================
echo  LowYSwampHut full package
echo  (Base DLL + standalone CLI + Java jar -^> dist\)
echo ============================================
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-dist.ps1"
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%EXIT_CODE%"=="0" (
    echo BUILD FAILED with exit code %EXIT_CODE%.
    pause
    exit /b %EXIT_CODE%
)

echo BUILD OK. Output is in:
echo   %~dp0dist\
echo.
pause
endlocal & exit /b 0
