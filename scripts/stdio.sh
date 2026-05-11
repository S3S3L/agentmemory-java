#!/usr/bin/env bash
# Start AgentMemory MCP server in stdio mode.
# Usage: ./scripts/stdio.sh
#
# This script builds and runs the MCP server over stdin/stdout.
# Claude Code connects via stdio transport.

set -e
cd "$(dirname "$0")/.."

# Build with stdio profile (sets main class to StdioMcpServer)
if [ ! -f target/agentmemory-0.1.0-SNAPSHOT.jar ]; then
    echo "Building agentmemory (stdio profile)..."
    mvn package -P stdio -DskipTests -q
fi

# Run stdio MCP server via spring-boot fat jar
# --logging.config ensures logs go to stderr, not stdout (which carries JSON-RPC)
exec java -jar target/agentmemory-0.1.0-SNAPSHOT.jar \
    --logging.config=classpath:logback-stdio.xml
