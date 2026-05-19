#/bin/bash

set -e
cd "$(dirname "$0")/.."

echo "Building agentmemory (stdio profile)..."
mvn package -P stdio -DskipTests -q
echo "Build success."

echo "Installing to /var/mcp ..."
cp target/agentmemory-0.1.0-SNAPSHOT.jar /var/mcp/agentmemory.jar
echo "Installed."
