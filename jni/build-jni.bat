@echo off
setlocal EnableExtensions
set MASTER=%~dp0
if "%JAVA_HOME%"=="" (
  for /f "delims=" %%i in ('where java 2^>nul') do set "JAVA_HOME=%%~dpi..\.."
)
set PATH=C:\msys64\ucrt64\bin;%PATH%

rem Prefer repo-root ./cubiomes when present (local override, gitignored); else submodule jni/cubiomes
set CUB_ROOT=%MASTER%..\cubiomes
set CUB_SUB=%MASTER%cubiomes
set CUB_INC=%MASTER%
if exist "%CUB_ROOT%\noise.c" (
  set CUB=%CUB_ROOT%
  rem So #include "cubiomes/..." resolves to ../cubiomes/...
  set CUB_INC=%MASTER%..
  echo Using local cubiomes: %CUB_ROOT%
) else (
  set CUB=%CUB_SUB%
  set CUB_INC=%MASTER%
  echo Using submodule cubiomes: %CUB_SUB%
)

set OUT=%MASTER%native\jni\build\native
set RF=%MASTER%..\native

if not exist "%CUB%\noise.c" (
  echo ERROR: cubiomes not found at:
  echo   %CUB_ROOT%
  echo   %CUB_SUB%
  echo Place cubiomes in repo root, or: git submodule update --init jni/cubiomes
  exit /b 1
)

mkdir "%OUT%" 2>nul
mkdir "%RF%\windows" 2>nul
cd /d "%MASTER%"

set INC=%JAVA_HOME%\include
set INCW=%JAVA_HOME%\include\win32
set CDEF=-DNDEBUG -D_WIN32 -I"%CUB_INC%" -I. -I"%INC%" -I"%INCW%"
set CXXDEF=%CDEF% -include cxx_compat/pre_cubiomes.h

del /q *.o bridge.o 2>nul
gcc -std=c17 -O3 %CDEF% -c "%CUB%\generator.c" "%CUB%\layers.c" "%CUB%\biomenoise.c" "%CUB%\biomes.c" "%CUB%\noise.c" "%CUB%\util.c"
if errorlevel 1 exit /b 1
g++ -std=c++20 -O3 %CXXDEF% -c SwampHutPhase1.cpp
if errorlevel 1 exit /b 1
g++ -std=c++20 -O3 %CXXDEF% -c native/jni/project_CubiomesBridge.cpp -o bridge.o
if errorlevel 1 exit /b 1
g++ -shared -O3 -o "%OUT%\libLowYSwampHutJ.dll" generator.o layers.o biomenoise.o biomes.o noise.o util.o SwampHutPhase1.o bridge.o -static -static-libgcc -static-libstdc++ -lm
if errorlevel 1 exit /b 1

copy /y "%OUT%\libLowYSwampHutJ.dll" "%RF%\windows\libLowYSwampHutJ.dll"
echo Built: %OUT%\libLowYSwampHutJ.dll
echo Copied to: %RF%\windows\libLowYSwampHutJ.dll
echo Cubiomes: %CUB%
