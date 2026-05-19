#!/bin/bash
# Setup Kibana Dashboard for agentmemory project-dimension metrics.
# Usage: ./scripts/setup-kibana.sh [KIBANA_URL]

set -e

KIBANA_URL="${1:-http://localhost:5601}"
XSRF_HEADER="kbn-xsrf: true"
CONTENT_TYPE="Content-Type: application/json"

echo "=== AgentMemory Kibana Dashboard Setup ==="
echo "Kibana URL: $KIBANA_URL"

# Check Kibana connectivity
echo "Checking Kibana connectivity..."
if ! curl -sf "$KIBANA_URL/api/status" > /dev/null 2>&1; then
    echo "ERROR: Cannot reach Kibana at $KIBANA_URL"
    exit 1
fi

# 1. Create Index Pattern (Data View)
echo ""
echo "Creating Index Pattern: memory-observations..."
curl -s -X POST "$KIBANA_URL/api/data_views/data_view" \
    -H "$XSRF_HEADER" -H "$CONTENT_TYPE" \
    -d '{
        "data_view": {
            "id": "memory-observations",
            "name": "memory-observations",
            "title": "memory-observations",
            "timeFieldName": "timestamp"
        }
    }' > /dev/null

# Helper function to create saved objects
create_vis() {
    local id="$1" title="$2" visState="$3" desc="$4"
    echo "  Creating visualization: $title"
    curl -s -X POST "$KIBANA_URL/api/saved_objects/visualization/$id" \
        -H "$XSRF_HEADER" -H "$CONTENT_TYPE" \
        -d "{
            \"attributes\": {
                \"title\": \"$title\",
                \"description\": \"$desc\",
                \"kibanaSavedObjectMeta\": {
                    \"searchSourceJSON\": \"{\\\"index\\\":\\\"memory-observations\\\"}\"
                },
                \"uiStateJSON\": \"{}\",
                \"version\": 1,
                \"visState\": $(echo "$visState" | python3 -c 'import sys,json; print(json.dumps(sys.stdin.read()))')
            },
            \"references\": [
                {\"name\": \"kibanaSavedObjectMeta.searchSourceJSON.index\", \"type\": \"index-pattern\", \"id\": \"memory-observations\"}
            ]
        }" > /dev/null
}

echo ""
echo "Creating Visualizations..."

# 1. Total Memories (metric)
create_vis "vis-memory-count-metric" "Total Memories" \
    '{"title":"Total Memories","type":"metric","aggs":[{"id":"1","type":"count","schema":"metric","params":{}}]}' \
    "Total observation count"

# 2. Tier Distribution (pie)
create_vis "vis-tier-distribution" "Memory Tier Distribution" \
    '{"title":"Memory Tier Distribution","type":"pie","aggs":[{"id":"1","type":"count","schema":"metric","params":{}},{"id":"2","type":"terms","schema":"segment","params":{"field":"tier","size":10,"orderBy":"1"}}]}' \
    "Distribution of memories across 4 tiers"

# 3. Top Files (table)
create_vis "vis-top-files" "Top 20 Observed Files" \
    '{"title":"Top 20 Observed Files","type":"table","aggs":[{"id":"1","type":"count","schema":"metric","params":{}},{"id":"2","type":"terms","schema":"bucket","params":{"field":"filePath.keyword","size":20,"orderBy":"1"}}]}' \
    "Top 20 files by observation count"

# 4. Tool Usage (histogram)
create_vis "vis-tool-usage" "Tool Usage Distribution" \
    '{"title":"Tool Usage Distribution","type":"histogram","aggs":[{"id":"1","type":"count","schema":"metric","params":{}},{"id":"2","type":"terms","schema":"segment","params":{"field":"toolName.keyword","size":20,"orderBy":"1","order":"desc"}}]}' \
    "Distribution of tool calls"

# 5. Tag Trends (area)
create_vis "vis-tag-trends" "Tag Trends Over Time" \
    '{"title":"Tag Trends Over Time","type":"area","aggs":[{"id":"1","type":"count","schema":"metric","params":{}},{"id":"2","type":"date_histogram","schema":"segment","params":{"field":"timestamp","interval":"d"}},{"id":"3","type":"terms","schema":"group","params":{"field":"tags.keyword","size":10,"orderBy":"1"}}]}' \
    "Top tags trend over time"

# 6. Session Activity (table)
create_vis "vis-session-activity" "Session Activity" \
    '{"title":"Session Activity","type":"table","aggs":[{"id":"1","type":"count","schema":"metric","params":{}},{"id":"2","type":"terms","schema":"bucket","params":{"field":"sessionId","size":20,"orderBy":"1"}}]}' \
    "Observation count per session"

# 7. Memory Aging (line)
create_vis "vis-memory-aging" "Memory Aging by Tier" \
    '{"title":"Memory Aging by Tier","type":"line","aggs":[{"id":"1","type":"count","schema":"metric","params":{}},{"id":"2","type":"date_histogram","schema":"segment","params":{"field":"timestamp","interval":"d"}},{"id":"3","type":"terms","schema":"group","params":{"field":"tier","size":10,"orderBy":"1"}}]}' \
    "Memory creation trend split by tier"

# Create Dashboard
echo "  Creating dashboard: Memory Usage Dashboard"
curl -s -X POST "$KIBANA_URL/api/saved_objects/dashboard/dashboard-memory-usage" \
    -H "$XSRF_HEADER" -H "$CONTENT_TYPE" \
    -d '{
        "attributes": {
            "title": "Memory Usage Dashboard",
            "description": "Project-dimension memory usage metrics — tier distribution, tool usage, tag trends, and memory aging.",
            "hits": 0,
            "timeRestore": false,
            "kibanaSavedObjectMeta": {
                "searchSourceJSON": "{\"query\":{\"match_all\":{}},\"filter\":[],\"index\":\"memory-observations\"}"
            },
            "panelsJSON": "[{\"gridData\":{\"h\":3,\"w\":6,\"x\":0,\"y\":0,\"i\":\"panel-memory-count\"},\"version\":\"8.15.2\",\"panelRefName\":\"panel_0\"},{\"gridData\":{\"h\":10,\"w\":8,\"x\":0,\"y\":3,\"i\":\"panel-tier-dist\"},\"version\":\"8.15.2\",\"panelRefName\":\"panel_1\"},{\"gridData\":{\"h\":10,\"w\":8,\"x\":8,\"y\":3,\"i\":\"panel-top-files\"},\"version\":\"8.15.2\",\"panelRefName\":\"panel_2\"},{\"gridData\":{\"h\":10,\"w\":8,\"x\":16,\"y\":3,\"i\":\"panel-tool-usage\"},\"version\":\"8.15.2\",\"panelRefName\":\"panel_3\"},{\"gridData\":{\"h\":10,\"w\":12,\"x\":0,\"y\":13,\"i\":\"panel-tag-trends\"},\"version\":\"8.15.2\",\"panelRefName\":\"panel_4\"},{\"gridData\":{\"h\":10,\"w\":6,\"x\":12,\"y\":13,\"i\":\"panel-session-activity\"},\"version\":\"8.15.2\",\"panelRefName\":\"panel_5\"},{\"gridData\":{\"h\":10,\"w\":6,\"x\":18,\"y\":13,\"i\":\"panel-memory-aging\"},\"version\":\"8.15.2\",\"panelRefName\":\"panel_6\"}]",
            "optionsJSON": "{\"hidePanelTitles\":false,\"useMargins\":true}"
        },
        "references": [
            {"name":"panel_0","type":"visualization","id":"vis-memory-count-metric"},
            {"name":"panel_1","type":"visualization","id":"vis-tier-distribution"},
            {"name":"panel_2","type":"visualization","id":"vis-top-files"},
            {"name":"panel_3","type":"visualization","id":"vis-tool-usage"},
            {"name":"panel_4","type":"visualization","id":"vis-tag-trends"},
            {"name":"panel_5","type":"visualization","id":"vis-session-activity"},
            {"name":"panel_6","type":"visualization","id":"vis-memory-aging"}
        ]
    }' > /dev/null

echo ""
echo "=== Setup Complete ==="
echo ""
echo "Dashboard URL: $KIBANA_URL/app/dashboards#/view/dashboard-memory-usage"
echo ""
echo "Next steps:"
echo "  1. Open Kibana: $KIBANA_URL"
echo "  2. Go to Dashboard → 'Memory Usage Dashboard'"
echo "  3. Add a filter on 'projectId' to view project-specific metrics"
