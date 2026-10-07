#!/bin/bash
echo "Compiling JFiltraServer..."
mkdir -p bin
javac -d bin -cp "../lib/*" ../src/main/java/server/JFiltraServer.java
if [ $? -eq 0 ]; then
  echo "Creating JAR file..."
  jar cf ../lib/jfiltra-server.jar -C bin .
  echo "JFiltraServer compiled successfully."
else
  echo "Compilation failed."
  exit 1
fi
