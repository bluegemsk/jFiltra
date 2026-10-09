@echo off
rem Compiles JFiltraClient and builds ..\lib\jfiltra-client.jar

rem Run from the script directory, wherever the script is called from
cd /d "%~dp0"

echo Compiling JFiltraClient...
if not exist bin mkdir bin
javac -encoding UTF-8 -d bin -cp "..\lib\*" ..\src\main\java\client\JFiltraClient.java
if errorlevel 1 (
  echo Compilation failed.
  exit /b 1
)
echo Creating JAR file...
jar cf ..\lib\jfiltra-client.jar -C bin .
echo JFiltraClient compiled successfully.
