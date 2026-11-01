<#
.SYNOPSIS
    Build the LowYSwampHut product with Gradle and stage dist\.

.DESCRIPTION
    Gradle is the single build path now:

      :lowyswamphutfinderbase:copyNativeArtifacts -> LowYSwampHutFinderBase.dll
      :lowyswamphutfinderbase:copyCliArtifact     -> LowYSwampHutFinderBase-cli.exe
      verifyJniContract                           -> Java native declarations vs Base headers
      buildMainJar                                -> LowYSwampHut-<version>.jar
                                                     (embeds /native/LowYSwampHutFinderBase.dll)

    The resulting dist\ directory is the deliverable:
      LowYSwampHut-<version>.jar
      LowYSwampHutFinderBase.dll        (optional override; the jar already embeds it)
      LowYSwampHutFinderBase-cli.exe    (standalone CLI, --out writes /tp lines)
      run.bat
      README.txt

.PARAMETER SkipNative
    Reuse the existing LowYSwampHutFinderBase/build/native/LowYSwampHutFinderBase.dll.
    Passed to Gradle as -PskipNativeBuild=true.

.PARAMETER SkipCompile
    Accepted for backwards compatibility. Gradle's incremental compiler decides
    whether Java sources are recompiled; this switch only suppresses `clean`.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File build-dist.ps1
#>
[CmdletBinding()]
param(
    [switch] $SkipCompile,
    [switch] $SkipNative
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$dist = Join-Path $root 'dist'
$gradlew = Join-Path $root 'gradlew.bat'
$baseDll = Join-Path $root 'LowYSwampHutFinderBase\build\native\LowYSwampHutFinderBase.dll'
$baseCli = Join-Path $root 'LowYSwampHutFinderBase\build\cli\LowYSwampHutFinderBase-cli.exe'

if (-not (Test-Path $gradlew)) {
    throw "gradlew.bat not found at $gradlew"
}

# Gradle itself and the JNI build need a JDK with include\jni.h. Prefer JDK 17+.
$jdkCandidates = @(
    'C:\Program Files\Java\jdk-17',
    'C:\Program Files\Java\jdk-21',
    'C:\Program Files\Eclipse Adoptium\jdk-17',
    'C:\Program Files\Eclipse Adoptium\jdk-21',
    $env:JAVA_HOME
)
$jdk = $jdkCandidates | Where-Object {
    $_ -and (Test-Path (Join-Path $_ 'include\jni.h')) -and (Test-Path (Join-Path $_ 'bin\javac.exe'))
} | Select-Object -First 1
if (-not $jdk) {
    throw 'A JDK 17+ with include\jni.h and bin\javac.exe was not found. Set JAVA_HOME.'
}
$env:JAVA_HOME = $jdk

# CMake / Ninja / gcc live in MSYS2 UCRT64 on this machine.
$msysDirs = @(
    'C:\msys64\ucrt64\bin',
    'C:\msys64\mingw64\bin',
    'C:\msys64\usr\bin'
) | Where-Object { Test-Path $_ }
if ($msysDirs.Count -gt 0) {
    $env:PATH = (($msysDirs -join ';') + ';' + $env:PATH)
}

Write-Host "==> JDK       : $jdk"
Write-Host "==> MSYS2     : $($msysDirs -join ';')"

$gradleArgs = @('--no-daemon')
if (-not ($SkipCompile -or $SkipNative)) {
    $gradleArgs += 'clean'
}
$gradleArgs += 'buildDistribution'
if ($SkipNative) {
    $gradleArgs += '-PskipNativeBuild=true'
    Write-Host '==> SkipNative: reusing the existing Base DLL (no clean)'
}
if ($SkipCompile) {
    Write-Host '==> SkipCompile: clean suppressed; Gradle decides what to recompile'
}

Write-Host "==> gradlew $($gradleArgs -join ' ')"
Push-Location $root
try {
    & $gradlew @gradleArgs
    $gradleExit = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($gradleExit -ne 0) {
    throw "Gradle build failed with exit code $gradleExit"
}

# The jar name follows AppVersion.VERSION, exactly like buildMainJar.
$appVersionSource = Join-Path $root 'src\main\java\project\AppVersion.java'
$versionMatch = Select-String -Path $appVersionSource `
    -Pattern 'public\s+static\s+final\s+String\s+VERSION\s*=\s*"([^"]+)"' |
    Select-Object -First 1
if (-not $versionMatch) {
    throw "Could not parse AppVersion.VERSION from $appVersionSource"
}
$version = $versionMatch.Matches[0].Groups[1].Value
if ($version -notmatch '^[0-9A-Za-z][0-9A-Za-z._+-]*$') {
    throw "Refusing unsafe AppVersion.VERSION value: $version"
}

$jar = Join-Path $root "build\libs\LowYSwampHut-$version.jar"
if (-not (Test-Path $jar)) {
    throw "Product jar missing: $jar"
}
if (-not (Test-Path $baseDll)) {
    throw "Base DLL missing: $baseDll (do not pass -SkipNative before building it once)"
}
if (-not (Test-Path $baseCli)) {
    throw "Base CLI missing: $baseCli"
}

if (Test-Path $dist) {
    if (-not $dist.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to delete dist outside the repository: $dist"
    }
    Remove-Item -Path $dist -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $dist | Out-Null

Copy-Item $jar (Join-Path $dist "LowYSwampHut-$version.jar") -Force
Copy-Item $baseDll (Join-Path $dist 'LowYSwampHutFinderBase.dll') -Force
Copy-Item $baseCli (Join-Path $dist 'LowYSwampHutFinderBase-cli.exe') -Force

$runBat = @"
@echo off
REM LowYSwampHut launcher (double-click friendly).
REM
REM   run.bat                       -> opens the Swing GUI
REM   run.bat --seed 123 -o out.txt -> command line search (arguments forwarded)
REM
REM This jar embeds the C core at /native/LowYSwampHutFinderBase.dll and releases
REM it to the per-user cache on first run, so the jar alone is enough. A
REM LowYSwampHutFinderBase.dll sitting in this folder overrides the embedded one.
REM (--min-x/--max-x/--min-z/--max-z are REGION indices, not block coordinates.)

setlocal
set "HERE=%~dp0"
set "JAR=%HERE%LowYSwampHut-$version.jar"
if not exist "%JAR%" (
    echo Missing "%JAR%".
    pause
    exit /b 1
)
pushd "%HERE%"
java -jar "%JAR%" %*
set "EXIT_CODE=%ERRORLEVEL%"
popd
if not "%EXIT_CODE%"=="0" (
    echo.
    echo LowYSwampHut exited with code %EXIT_CODE%.
    pause
)
endlocal & exit /b %EXIT_CODE%
"@

$readme = @"
LowYSwampHut $version - one self-contained jar + standalone Base CLI
=====================================================================

Run the GUI
-----------
  double-click LowYSwampHut-$version.jar
  or:  java -jar LowYSwampHut-$version.jar
  or:  java -jar LowYSwampHut-$version.jar --seed <seed> -o hits.txt   (CLI mode)
  (run.bat in this folder does the same from a console)

Standalone native CLI
---------------------
  LowYSwampHutFinderBase-cli.exe --help
  LowYSwampHutFinderBase-cli.exe scan --seed <seed> --out hits.txt
  LowYSwampHutFinderBase-cli.exe hut --seed <seed> --x <hutX> --z <hutZ> --out hut.txt

The C core
----------
The x86-64 Windows core (phase 1 + phase 2) travels INSIDE the jar at
/native/LowYSwampHutFinderBase.dll. On first run project.NativePhase1
releases it to
  %LOCALAPPDATA%\LowYSwampHut\native\<sha256-16>\LowYSwampHutFinderBase.dll
and loads it from there. The cache directory is keyed by the file's SHA-256,
so upgrading the jar never picks up an older core.

A loose LowYSwampHutFinderBase.dll in this folder is an OVERRIDE: if present
it wins over the embedded one. Delete it to fall back to the embedded core.

Search order (first hit wins)
  1. -Dlowyswamphut.nativeLib=<path>
  2. LYSH_NATIVE_LIB=<path>
  3. <cwd>\LowYSwampHutFinderBase\build\native\LowYSwampHutFinderBase.dll
  4. <cwd>\LowYSwampHutFinderBase\build\cmake\bin\LowYSwampHutFinderBase.dll
  5. <jar folder>\LowYSwampHutFinderBase.dll  /  <jar folder>\native\...
  6. the /native/LowYSwampHutFinderBase.dll embedded in the jar
  7. -Djava.library.path

Requirements
------------
  * Java 17 or newer on PATH.
  * x86-64 Windows. There is NO Java fallback: if the core cannot be loaded
    the GUI still opens but reports "native core unavailable".

What is inside the jar
----------------------
project/*.class, resources (messages, icon, font) and
native/LowYSwampHutFinderBase.dll. Every terrain / structure judgement is made
by the C core, so there is no Mojang or SeedChecker code in here.

Product version
---------------
  AppVersion.VERSION = $version
  jar name           = LowYSwampHut-$version.jar

Rebuild
-------
  powershell -NoProfile -ExecutionPolicy Bypass -File ..\build-dist.ps1
  (or: gradlew.bat clean buildDistribution)
"@

[System.IO.File]::WriteAllText(
    (Join-Path $dist 'run.bat'),
    ($runBat -replace "`r?`n", "`r`n"),
    (New-Object System.Text.ASCIIEncoding))
[System.IO.File]::WriteAllText(
    (Join-Path $dist 'README.txt'),
    ($readme -replace "`r?`n", "`r`n"),
    (New-Object System.Text.ASCIIEncoding))

Write-Host ''
Write-Host '==> Done'
Write-Host "    version : $version"
Write-Host "    jar     : $(Join-Path $dist "LowYSwampHut-$version.jar")"
Write-Host "    native  : $(Join-Path $dist 'LowYSwampHutFinderBase.dll')"
Write-Host "    CLI     : $(Join-Path $dist 'LowYSwampHutFinderBase-cli.exe')"
Write-Host "    run     : java -jar `"$(Join-Path $dist "LowYSwampHut-$version.jar")`""
