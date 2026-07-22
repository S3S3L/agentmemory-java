#!/usr/bin/env bash

set -euo pipefail
cd "$(dirname "$0")/.."

JAR="target/agentmemory-0.1.0-SNAPSHOT.jar"
STDIO_MAIN_CLASS="com.agentmemory.StdioMcpServer"

echo "Building agentmemory (stdio profile)..."
mvn clean package -Pstdio -DskipTests -q

if ! unzip -p "$JAR" META-INF/MANIFEST.MF | grep -q "^Start-Class: ${STDIO_MAIN_CLASS}"; then
    echo "Build failed: $JAR is not a stdio-mode executable jar." >&2
    exit 1
fi

echo "Build success."

echo "Installing to /var/mcp ..."
mkdir -p /var/mcp
cp "$JAR" /var/mcp/agentmemory.jar
echo "Installed."
