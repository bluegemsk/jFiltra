@echo off
setlocal

rem Set variables (paths are relative to this script, wherever it is called from)
for %%I in ("%~dp0..") do set "SERVER_DIR=%%~fI"
set "LIB_DIR=%SERVER_DIR%\lib"
set "CONFIG_DIR=%SERVER_DIR%\config"

rem Set classpath with absolute paths
set "JFILTRA_CP=%LIB_DIR%\jfiltra-server.jar;%LIB_DIR%\log4j-api-2.26.1.jar;%LIB_DIR%\log4j-core-2.26.1.jar"
set "CONFIG_FILE=%CONFIG_DIR%\server.properties"
set "LOG4J_CONFIG=%CONFIG_DIR%\log4j2.properties"

rem Display startup information
echo Classpath: %JFILTRA_CP%
echo Starting JFiltraServer with configuration: %CONFIG_FILE%
echo ------------------------
java -version
echo ------------------------

rem Create log directory if it doesn't exist
if not exist "%SERVER_DIR%\logs" mkdir "%SERVER_DIR%\logs"

rem Change to the server directory to make relative paths work
cd /d "%SERVER_DIR%"

rem Start the server
java -Dlog4j2.configurationFile="%LOG4J_CONFIG%" -cp "%JFILTRA_CP%" server.JFiltraServer "%CONFIG_FILE%"
