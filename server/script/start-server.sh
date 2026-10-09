#!/bin/bash

# Run from the script directory, wherever the script is called from
cd "$(dirname "$0")" || exit 1

# Set variables
SERVER_DIR="$(cd .. && pwd)"
LIB_DIR="$SERVER_DIR/lib"
CONFIG_DIR="$SERVER_DIR/config"

# Set classpath with absolute paths
CLASSPATH="$LIB_DIR/jfiltra-server.jar:$LIB_DIR/log4j-api-2.26.1.jar:$LIB_DIR/log4j-core-2.26.1.jar"
CONFIG_FILE="$CONFIG_DIR/server.properties"
LOG4J_CONFIG="$CONFIG_DIR/log4j2.properties"

# Display startup information
echo "Classpath: $CLASSPATH"
echo "Starting JFiltraServer with configuration: $CONFIG_FILE"
echo "Environment Information:"
echo "------------------------"
echo "Java Version: $(java -version 2>&1 | head -n 1)"
echo "Working Directory: $(pwd)"
echo "------------------------"

# Create log directory if it doesn't exist
mkdir -p "$SERVER_DIR/logs"

# Change to the server directory to make relative paths work
cd "$SERVER_DIR"

# Start the server
java -Dlog4j2.configurationFile="$LOG4J_CONFIG" -cp "$CLASSPATH" server.JFiltraServer "$CONFIG_FILE"
