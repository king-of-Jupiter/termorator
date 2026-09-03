@echo off
setlocal
pushd "%~dp0"

REM Termora dev launch - prefers JetBrains Runtime (JDK 25) as recommended in README
REM https://github.com/JetBrains/JetBrainsRuntime

REM If JBR is installed, use it (covers foojay download failures behind proxy/firewall)
if exist "C:\Program Files\JBR\bin\java.exe" (
    if not defined JAVA_HOME (
        set "JAVA_HOME=C:\Program Files\JBR"
    ) else (
        REM JAVA_HOME is set but check if it points to Java 25; if not, prefer JBR
        "%JAVA_HOME%\bin\java.exe" -version 2>&1 | findstr /C:"25." >nul
        if errorlevel 1 set "JAVA_HOME=C:\Program Files\JBR"
    )
)

if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"

REM Optional: show which Java will be used
where java >nul 2>&1
if %errorlevel% equ 0 (
    echo Using Java:
    java -version 2>&1 | findstr /R "version"
    if defined JAVA_HOME echo JAVA_HOME=%JAVA_HOME%
    echo.
)

call gradlew.bat run %*
set EXIT_CODE=%ERRORLEVEL%
popd
if %EXIT_CODE% neq 0 (
    echo.
    echo Termora exited with code %EXIT_CODE%
    pause
)
exit /b %EXIT_CODE%
