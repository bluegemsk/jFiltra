@echo off
rem Compiles JFiltraServer and builds ..\lib\jfiltra-server.jar

rem Run from the script directory, wherever the script is called from
cd /d "%~dp0"

echo Compiling JFiltraServer...

rem Build Java 8 compatible classes, so the JAR runs on Java 8 or newer whatever JDK builds it.
rem --release exists from JDK 9; JDK 8 builds Java 8 classes anyway.
set "RELEASE="
javac --release 8 -version >nul 2>&1 && set "RELEASE=--release 8 -Xlint:-options"

if exist bin rmdir /s /q bin
mkdir bin
javac %RELEASE% -proc:none -encoding UTF-8 -d bin -cp "..\lib\*" ..\src\main\java\server\JFiltraServer.java
if errorlevel 1 (
  echo Compilation failed.
  exit /b 1
)
echo Creating JAR file...
jar cf ..\lib\jfiltra-server.jar -C bin .
echo JFiltraServer compiled successfully.
