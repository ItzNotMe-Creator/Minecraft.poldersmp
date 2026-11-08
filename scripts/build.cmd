@echo off
setlocal
cd /d "%~dp0\.."
call mvn -B -U clean verify %*
exit /b %errorlevel%
