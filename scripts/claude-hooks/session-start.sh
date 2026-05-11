#!/bin/bash
# SessionStart hook - called when a new Claude Code session begins
PROJECT_PATH="${1:-$(pwd)}"
curl -s -X POST "http://localhost:40080/memory/session/start" \
  -H "Content-Type: application/json" \
  -d "{\"path\": \"$PROJECT_PATH\"}" 2>/dev/null
