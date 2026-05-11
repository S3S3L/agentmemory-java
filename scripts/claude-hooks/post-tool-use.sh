#!/bin/bash
# PostToolUse hook - called after each tool use
TOOL="${1:-unknown}"
INPUT="${2:-}"
OUTPUT="${3:-}"
FILE_PATH="${4:-}"
SESSION_ID="${5:-}"

curl -s -X POST "http://localhost:40080/memory/observe" \
  -H "Content-Type: application/json" \
  -d "{\"tool\": \"$TOOL\", \"input\": $(echo "$INPUT" | head -c 2000 | jq -Rs .), \"output\": $(echo "$OUTPUT" | head -c 4000 | jq -Rs .), \"filePath\": \"$FILE_PATH\", \"sessionId\": \"$SESSION_ID\"}" 2>/dev/null
