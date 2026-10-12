#!/bin/bash

# Run from the script directory, wherever the script is called from
cd "$(dirname "$0")" || exit 1

echo "Compiling JFiltraClient..."

# Build Java 8 compatible classes, so the JAR runs on Java 8 or newer whatever JDK builds it.
# --release exists from JDK 9; JDK 8 builds Java 8 classes anyway.
RELEASE=""
if javac --release 8 -version >/dev/null 2>&1; then
  RELEASE="--release 8 -Xlint:-options"
fi

rm -rf bin
mkdir -p bin
javac $RELEASE -proc:none -encoding UTF-8 -d bin -cp "../lib/*" ../src/main/java/client/JFiltraClient.java
if [ $? -eq 0 ]; then
  echo "Creating JAR file..."
  jar cf ../lib/jfiltra-client.jar -C bin .
  echo "JFiltraClient compiled successfully."
else
  echo "Compilation failed."
  exit 1
fi
