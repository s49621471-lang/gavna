@echo off
setlocal

cmake -S "%~dp0" -B "%~dp0build" -A x64
if errorlevel 1 goto :fail

cmake --build "%~dp0build" --config Release
if errorlevel 1 goto :fail

echo.
echo built: %~dp0build\Release\cs2-esp.exe
exit /b 0

:fail
echo.
echo build failed
exit /b 1
