package com.agentmemory.mcp;

import org.springframework.context.annotation.Configuration;

/**
 * MCP Server will be initialized after Spring context is ready.
 * Tools are exposed via REST endpoints under /mcp/* for now.
 * Full MCP protocol integration can be added via mcp-spring-webmvc.
 */
@Configuration
public class McpServer {
    // TODO: integrate io.modelcontextprotocol.sdk:mcp for proper MCP protocol
    // For Phase 1, tools are accessible via the REST /memory/* endpoints
}
