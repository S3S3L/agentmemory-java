package com.agentmemory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * MCP Server configuration using Spring MVC SSE transport.
 * Exposes MCP protocol endpoints at /mcp/message (POST) and /mcp/sse (GET).
 */
@Configuration
public class McpConfig {

    @Bean
    public WebMvcSseServerTransportProvider webMvcTransport(ObjectMapper objectMapper) {
        return new WebMvcSseServerTransportProvider(
            objectMapper,
            "/mcp/message",
            "/mcp/sse"
        );
    }

    @Bean
    public RouterFunction<ServerResponse> mcpRouterFunction(WebMvcSseServerTransportProvider transport) {
        return transport.getRouterFunction();
    }

    @Bean
    public McpSyncServer mcpServer(WebMvcSseServerTransportProvider transport,
                                   com.agentmemory.mcp.McpToolRegistrar registrar) {
        return McpServer.sync(transport)
            .serverInfo("agentmemory", "0.1.0")
            .instructions("Persistent memory for coding agents. Search, save, and manage coding knowledge across sessions.")
            .tools(registrar.registerAll())
            .build();
    }
}
