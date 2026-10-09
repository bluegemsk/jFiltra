@echo off
rem Compiles JFiltraServer and builds ..\lib\jfiltra-server.jar

rem Run from the script directory, wherever the script is called from
cd /d "%~dp0"

echo Compiling JFiltraServer...
if not exist bin mkdir bin
javac -encoding UTF-8 -d bin -cp "..\lib\*" ..\src\main\java\server\JFiltraServer.java
if errorlevel 1 (
  echo Compilation failed.
  exit /b 1
)
echo Creating JAR file...
jar cf ..\lib\jfiltra-server.jar -C bin .
echo JFiltraServer compiled successfully.
