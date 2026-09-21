<#
.SYNOPSIS
    Build the self-contained, directly runnable LowYSwampHut GUI jar into ..\dist\.

.DESCRIPTION
    Why this exists: the product used to be launchable only as
        java -cp "<jar>;<20 dependency jars from _scratch\test\cp.txt>" project.Launcher
    because its search path was Java code (SeedChecker + Mojang + mc_core/mc_feature +
    noise-sampler). That Java path has been deleted: phase 1 and phase 2 are computed
    entirely by the C core (lysh.dll) through project.NativePhase1 / project.NativePhase2.
    The product compiles against the JDK alone; there is no third-party jar today. There is
    deliberately NO guard that forbids dependencies -- if a future version needs one, add it
    to the -classpath at the marked place in the compile step below and to the jar packaging.
    The build will not block you.

    Offline, no Gradle, no network: plain javac + jar.

    Output layout (one obvious thing to run), relative to the repository root:
        dist\LowYSwampHut.jar   product classes + resources + /native/lysh.dll
        dist\lysh.dll           the C core; also embedded in the jar (override only)
        dist\run.bat            double-click launcher
        dist\README.txt         layout / requirements

    The jar is SELF-CONTAINED: /native/lysh.dll travels inside it and NativePhase1 releases
    it to the per-user cache on first run, so the jar alone is enough to run a search. The
    loose lysh.dll is kept only as an explicit override for swapping/repairing the core.

    The script finishes by listing the jar and FAILING if the embedded /native/lysh.dll is
    missing -- that is what keeps the jar self-contained. It does NOT police dependencies.

    NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads .ps1 as the system ANSI
    codepage when there is no BOM, and non-ASCII text breaks parsing.

.PARAMETER SkipCompile
    Reuse the existing _scratch\dist-build\classes instead of recompiling.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File LowYSwampHut-main\build-dist.ps1
#>
[CmdletBinding()]
param(
    [switch] $SkipCompile
)

$ErrorActionPreference = 'Stop'

$repo      = Split-Path -Parent $MyInvocation.MyCommand.Path          # LowYSwampHut-main
$workspace = Split-Path -Parent $repo                                # the repository root
$srcDir    = Join-Path $repo 'src\main\java'
$resDir    = Join-Path $repo 'src\main\resources'
$buildRoot = Join-Path $workspace '_scratch\dist-build'
$classes   = Join-Path $buildRoot 'classes'
$distDir   = Join-Path $workspace 'dist'
$jarPath   = Join-Path $distDir 'LowYSwampHut.jar'
$manifest  = Join-Path $buildRoot 'MANIFEST.MF'
$dllSource = Join-Path $workspace 'lysh-c\build\cmake\lysh.dll'
$legacyDll = Join-Path $repo '..\lysh-c\build\cmake\lysh.dll'

function Write-Step([string] $text) { Write-Host "==> $text" -ForegroundColor Cyan }

# javac/jar write notes to stderr; with $ErrorActionPreference='Stop' Windows PowerShell
# 5.1 would turn that into a terminating NativeCommandError. Run natives with 'Continue'
# and check $LASTEXITCODE instead.
$lastExitCode = 0
function Invoke-Native([scriptblock] $command) {
    $previous = $ErrorActionPreference
    $previousToolOptions = $env:JAVA_TOOL_OPTIONS
    $ErrorActionPreference = 'Continue'
    # JDK 24+ warns on System.load() unless native access is granted (harmless, but noisy).
    $env:JAVA_TOOL_OPTIONS = '--enable-native-access=ALL-UNNAMED'
    try {
        & $command
    } finally {
        $lastExitCode = $LASTEXITCODE
        $env:JAVA_TOOL_OPTIONS = $previousToolOptions
        $ErrorActionPreference = $previous
    }
}

if (-not $SkipCompile) {
    Write-Step "Compiling the product (JDK only today; see the -classpath marker below) -> $classes"
    if (Test-Path $classes) { Remove-Item $classes -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $classes, $distDir | Out-Null

    $sources = @(Get-ChildItem -Path $srcDir -Recurse -Filter '*.java' -File | ForEach-Object { $_.FullName })
    if ($sources.Count -eq 0) { throw "No sources found under $srcDir" }

    $argFile = Join-Path $buildRoot 'javac-sources.txt'
    # BOM-less UTF-8, otherwise javac treats the BOM as part of the first path.
    [System.IO.File]::WriteAllLines(
        $argFile, [string[]] $sources, (New-Object System.Text.UTF8Encoding($false)))

    $log = Join-Path $buildRoot 'javac.log'
    # --release 17: the product uses no API newer than Java 17, and the README promises
    # "Java 17 or higher", so build against the 17 platform API explicitly.
    #
    # No -classpath today: the product needs no third-party jar.
    # >>> ADDING A DEPENDENCY LATER: append -classpath "<jar>" here (and copy the jar into
    # >>> dist\ / adjust run.bat if it must be shipped alongside). Nothing forbids it.
    Invoke-Native { javac --release 17 -encoding UTF-8 -nowarn -d $classes "@$argFile" 2>&1 | Out-File -FilePath $log -Encoding UTF8 }
    if ($lastExitCode -ne 0) {
        Get-Content $log | Select-Object -Last 40 | Write-Host
        throw "javac failed (full log: $log)"
    }
} elseif (-not (Test-Path $classes)) {
    throw "-SkipCompile was given but $classes does not exist"
}

Write-Step "Copying resources (messages / icon / font)"
Copy-Item -Path (Join-Path $resDir '*') -Destination $classes -Recurse -Force

Write-Step "Writing the manifest (Main-Class + native access for JDK 24+)"
$manifestText = @(
    'Manifest-Version: 1.0'
    'Main-Class: project.Launcher'
    'Enable-Native-Access: ALL-UNNAMED'
    'Created-By: build-dist.ps1 (offline javac + jar, no Gradle)'
    ''
) -join "`r`n"
[System.IO.File]::WriteAllText($manifest, $manifestText + "`r`n", (New-Object System.Text.ASCIIEncoding))

Write-Step "Embedding lysh.dll into the jar as /native/lysh.dll"
$dll = @($dllSource, $legacyDll) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $dll) {
    throw "lysh.dll not found (looked in: $dllSource , $legacyDll). Build it with CMake first; the product has no Java fallback."
}
$nativeStage = Join-Path $classes 'native'
New-Item -ItemType Directory -Force -Path $nativeStage | Out-Null
Copy-Item $dll (Join-Path $nativeStage 'lysh.dll') -Force
Write-Host "    embedded : $dll"

Write-Step "Packaging -> $jarPath"
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }
$jarLog = Join-Path $buildRoot 'jar.log'
Invoke-Native { jar --create --file $jarPath --manifest $manifest -C $classes . 2>&1 |
    Out-File -FilePath $jarLog -Encoding UTF8 }
if ($lastExitCode -ne 0) {
    Get-Content $jarLog | Select-Object -Last 20 | Write-Host
    throw "jar packaging failed (log: $jarLog)"
}

Write-Step "Copying lysh.dll next to the jar (optional override; the jar runs without it)"
Copy-Item $dll (Join-Path $distDir 'lysh.dll') -Force
Write-Host "    beside jar: $(Join-Path $distDir 'lysh.dll')"

Write-Step "Writing dist\run.bat and dist\README.txt"
$runBat = @'
@echo off
REM LowYSwampHut GUI launcher (double-click friendly).
REM
REM   run.bat                       -> opens the Swing GUI
REM   run.bat --seed 123 -o out.txt -> command line search (arguments forwarded)
REM
REM This jar embeds the C core at /native/lysh.dll and releases it to the per-user
REM cache on first run, so LowYSwampHut.jar alone is enough. A lysh.dll sitting in
REM this folder overrides the embedded one (that is the swap/repair path).
REM (--min-x/--max-x/--min-z/--max-z are REGION indices, not block coordinates.)

setlocal
set "HERE=%~dp0"
set "JAR=%HERE%LowYSwampHut.jar"
if not exist "%JAR%" (
    echo Missing "%JAR%".
    echo Build it first:
    echo   powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%..\LowYSwampHut-main\build-dist.ps1"
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
'@
$readme = @'
LowYSwampHut - one self-contained jar
=====================================

Run it
------
  double-click LowYSwampHut.jar
  or:  java -jar LowYSwampHut.jar
  or:  java -jar LowYSwampHut.jar --seed <seed> -o hits.txt      (CLI mode)
  (run.bat in this folder does the same from a console)

No -cp, no Gradle, no network. ONE file: copy LowYSwampHut.jar anywhere and it
still runs.

The C core
----------
The x86-64 Windows core (phase 1 + phase 2) travels INSIDE the jar at
/native/lysh.dll. On first run project.NativePhase1 releases it to
  %LOCALAPPDATA%\LowYSwampHut\native\<sha256-16>\lysh.dll
and loads it from there. That directory is keyed by the file's SHA-256, so
upgrading the jar never picks up an older core, and later runs reuse the copy
that is already there.

A loose lysh.dll in this folder is an OVERRIDE: if present it wins over the
embedded one. Delete it to fall back to the embedded core.

Search order (first hit wins)
  1. -Dlowyswamphut.nativeLib=<path>
  2. LYSH_NATIVE_LIB=<path>
  3. <cwd>\lysh-c\build\cmake\lysh.dll    (developer build)
  4. <jar folder>\lysh.dll  /  <jar folder>\native\lysh.dll
  5. the /native/lysh.dll embedded in the jar
  6. -Djava.library.path

Requirements
------------
  * Java 17 or newer on PATH (the classes are compiled with javac --release 17).
  * x86-64 Windows. There is NO Java fallback: if the core cannot be loaded the
    GUI still opens but reports "native core unavailable". The engine line
    printed at startup names the exact file that was loaded.

What is inside the jar
----------------------
project/*.class, resources (messages, icon, font) and native/lysh.dll.
Every terrain / structure judgement is made by the C core, so there is no Mojang
or SeedChecker code in here today.

Rebuild
-------
  powershell -NoProfile -ExecutionPolicy Bypass -File ..\LowYSwampHut-main\build-dist.ps1
'@
[System.IO.File]::WriteAllText((Join-Path $distDir 'run.bat'), ($runBat -replace "`r?`n", "`r`n"), (New-Object System.Text.ASCIIEncoding))
[System.IO.File]::WriteAllText((Join-Path $distDir 'README.txt'), ($readme -replace "`r?`n", "`r`n"), (New-Object System.Text.ASCIIEncoding))

Write-Step "Verifying that the jar embeds the native core"
$entries = @(& jar --list --file $jarPath)
if (-not ($entries -contains 'native/lysh.dll')) {
    throw "The jar does not embed native/lysh.dll; the product would require a loose DLL again."
}
Write-Host "    OK: $($entries.Count) entries; /native/lysh.dll is embedded."

Write-Step "Done"
Write-Host "    jar    : $jarPath  ($((Get-Item $jarPath).Length) bytes)  [self-contained]"
Write-Host "    native : /native/lysh.dll inside the jar  (+ loose copy at $(Join-Path $distDir 'lysh.dll'))"
Write-Host "    run    : java -jar `"$jarPath`"   (the jar alone is enough)"
