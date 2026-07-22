#!/usr/bin/env bash
# Start AgentMemory MCP server in stdio mode.
# Usage: ./scripts/stdio.sh
#
# This script builds and runs the MCP server over stdin/stdout.
# Claude Code connects via stdio transport.

set -euo pipefail
cd "$(dirname "$0")/.."

JAR="target/agentmemory-0.1.0-SNAPSHOT.jar"
STDIO_MAIN_CLASS="com.agentmemory.StdioMcpServer"

is_stdio_jar() {
    [ -f "$JAR" ] &&
        unzip -p "$JAR" META-INF/MANIFEST.MF |
        grep -q "^Start-Class: ${STDIO_MAIN_CLASS}"
}

# Rebuild if the jar is missing or was last packaged with the HTTP main class.
if ! is_stdio_jar; then
    echo "Building agentmemory (stdio profile)..."
    mvn clean package -Pstdio -DskipTests -q
fi

if ! is_stdio_jar; then
    echo "Build failed: $JAR is not a stdio-mode executable jar." >&2
    exit 1
fi

# Run stdio MCP server via spring-boot fat jar
# --logging.config ensures logs go to stderr, not stdout (which carries JSON-RPC)
exec java -jar "$JAR" \
    --logging.config=classpath:logback-stdio.xml
