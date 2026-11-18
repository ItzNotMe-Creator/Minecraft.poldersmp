@echo off
rem PolderSMP Nether maintenance tool launcher (Windows).
rem Usage:  nether-maintenance.cmd status|run|start|recover|verify|cleanup [--server-dir "C:\path\to\smp"]
rem Put this file and PolderMaintenance.jar in the same folder (for example next to your SMP server folder).
rem This script never stops or kills the server. Stop the server normally first.
setlocal
set "JAR=%~dp0PolderMaintenance.jar"
if not exist "%JAR%" set "JAR=%~dp0..\polder-maintenance\target\PolderMaintenance.jar"
if not exist "%JAR%" (
  echo PolderMaintenance.jar was not found next to this script.
  exit /b 2
)
where java >nul 2>nul
if errorlevel 1 (
  echo Java was not found. Install Java 21 or newer and try again.
  exit /b 2
)
java -jar "%JAR%" %*
exit /b %ERRORLEVEL%
