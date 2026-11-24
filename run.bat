
@echo off
rem Starts MediQueue Web. Open http://localhost:8080 in your browser, and close this window (or press Ctrl+C) to stop it.
cd /d "%~dp0"
java -jar MediQueueWeb.jar %*
pause
