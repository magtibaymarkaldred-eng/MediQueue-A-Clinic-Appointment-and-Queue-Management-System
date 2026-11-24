
@echo off
rem Builds both versions of MediQueue (needs JDK 17 or newer).
cd /d "%~dp0"

if exist build rmdir /s /q build
mkdir build

javac --release 17 -encoding UTF-8 -d build src\MediQueueData.java src\MediQueueService.java src\MediQueueWeb.java src\Json.java src\MediQueue.java
if errorlevel 1 exit /b 1

jar --create --file MediQueueWeb.jar --main-class mediqueue.MediQueueWeb -C build . web
if errorlevel 1 exit /b 1

jar --create --file MediQueue.jar --main-class mediqueue.MediQueue -C build .
if errorlevel 1 exit /b 1

echo.
echo Build complete.
echo   Web version:     java -jar MediQueueWeb.jar
echo   Desktop version: java -jar MediQueue.jar
