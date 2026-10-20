#!/bin/bash

# Run from the script directory, wherever the script is called from
cd "$(dirname "$0")" || exit 1

# Find Java: $JAVA_HOME/bin/java if JAVA_HOME is set, otherwise java on the PATH
if [ -n "$JAVA_HOME" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="$(command -v java)"
fi
if [ -z "$JAVA" ] || [ ! -x "$JAVA" ]; then
  echo "Error: Java not found. Install OpenJDK 8 or newer (see README) or set JAVA_HOME." >&2
  exit 1
fi

# Check the version: jFiltra needs Java 8 or newer ("1.8.0_x" is Java 8, "11.0.x" is Java 11)
# jFiltra needs a UTF-8 locale for non-ASCII file names. If the current one isn't
# UTF-8 (e.g. POSIX/C, common under systemd), use C.UTF-8 or en_US.UTF-8 if installed.
if [ "$(locale charmap 2>/dev/null)" != "UTF-8" ]; then
  for loc in C.UTF-8 C.utf8 en_US.UTF-8 en_US.utf8; do
    if locale -a 2>/dev/null | grep -qx "$loc"; then
      export LC_ALL="$loc"
      break
    fi
  done
fi

JAVA_VERSION="$("$JAVA" -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
JAVA_MAJOR="${JAVA_VERSION%%.*}"
[ "$JAVA_MAJOR" = "1" ] && JAVA_MAJOR="$(echo "$JAVA_VERSION" | cut -d. -f2)"
case "$JAVA_MAJOR" in
  ''|*[!0-9]*) echo "Error: Could not read the Java version of $JAVA." >&2; exit 1 ;;
esac
if [ "$JAVA_MAJOR" -lt 8 ]; then
  echo "Error: Java $JAVA_VERSION found at $JAVA, but jFiltra needs Java 8 or newer." >&2
  exit 1
fi

# Set variables
CLIENT_DIR="$(cd .. && pwd)"
LIB_DIR="$CLIENT_DIR/lib"
CONFIG_DIR="$CLIENT_DIR/config"

# Set classpath with absolute paths
CLASSPATH="$LIB_DIR/jfiltra-client.jar:$LIB_DIR/log4j-api-2.26.1.jar:$LIB_DIR/log4j-core-2.26.1.jar"
CONFIG_FILE="$CONFIG_DIR/client.properties"
LOG4J_CONFIG="$CONFIG_DIR/log4j2.properties"

# Display startup information
echo "Classpath: $CLASSPATH"
echo "Starting JFiltraClient with configuration: $CONFIG_FILE"
echo "Environment Information:"
echo "------------------------"
echo "Java: $JAVA (version $JAVA_VERSION)"
echo "Working Directory: $(pwd)"
echo "------------------------"

# Create log directory if it doesn't exist
mkdir -p "$CLIENT_DIR/logs"

# Change to the client directory to make relative paths work
cd "$CLIENT_DIR"

# Start the client
exec "$JAVA" -Dlog4j2.configurationFile="$LOG4J_CONFIG" -cp "$CLASSPATH" client.JFiltraClient "$CONFIG_DIR/client.properties"
