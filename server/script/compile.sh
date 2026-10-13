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

# Compiling needs a JDK (javac and jar), not just a JRE
JAVAC="$(dirname "$JAVA")/javac"
JAR="$(dirname "$JAVA")/jar"
if [ ! -x "$JAVAC" ] || [ ! -x "$JAR" ]; then
  echo "Error: javac or jar not found next to $JAVA. Install the OpenJDK JDK package, not only the JRE (see README)." >&2
  exit 1
fi

echo "Compiling JFiltraServer..."

# Build Java 8 compatible classes, so the JAR runs on Java 8 or newer whatever JDK builds it.
# --release exists from JDK 9; JDK 8 builds Java 8 classes anyway.
RELEASE=""
if "$JAVAC" --release 8 -version >/dev/null 2>&1; then
  RELEASE="--release 8 -Xlint:-options"
fi

rm -rf bin
mkdir -p bin
"$JAVAC" $RELEASE -proc:none -encoding UTF-8 -d bin -cp "../lib/*" ../src/main/java/server/JFiltraServer.java
if [ $? -eq 0 ]; then
  echo "Creating JAR file..."
  "$JAR" cf ../lib/jfiltra-server.jar -C bin .
  echo "JFiltraServer compiled successfully."
else
  echo "Compilation failed."
  exit 1
fi
