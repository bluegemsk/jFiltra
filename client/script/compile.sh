#!/bin/bash
echo "Compiling JFiltraClient..."
mkdir -p bin
javac -d bin -cp "../lib/*" ../src/main/java/client/JFiltraClient.java
if [ $? -eq 0 ]; then
  echo "Creating JAR file..."
  jar cf ../lib/jfiltra-client.jar -C bin .
  echo "JFiltraClient compiled successfully."
else
  echo "Compilation failed."
  exit 1
fi
