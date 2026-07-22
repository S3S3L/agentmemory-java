package com.agentmemory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

class StdioMcpServerTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void parsesElasticsearchTimeoutsFromApplicationConfiguration() throws Exception {
        var config = yaml.readTree("""
            elasticsearch:
              connection-request-timeout-ms: 1200
              connect-timeout-ms: 2300
              response-timeout-ms: 3400
            """);

        var timeouts = StdioMcpServer.elasticsearchTimeouts(config);

        assertEquals(1200, timeouts.connectionRequestTimeoutMillis());
        assertEquals(2300, timeouts.connectTimeoutMillis());
        assertEquals(3400, timeouts.responseTimeoutMillis());
    }

    @Test
    void defaultResponseTimeoutIsFinite() throws Exception {
        var timeouts = StdioMcpServer.elasticsearchTimeouts(yaml.readTree("elasticsearch: {}"));

        assertTrue(timeouts.responseTimeoutMillis() > 0);
        assertEquals(StdioMcpServer.DEFAULT_RESPONSE_TIMEOUT_MILLIS,
            timeouts.responseTimeoutMillis());
    }

    @Test
    void rejectsInfiniteResponseTimeout() throws Exception {
        var config = yaml.readTree("""
            elasticsearch:
              response-timeout-ms: 0
            """);

        assertThrows(IllegalArgumentException.class,
            () -> StdioMcpServer.elasticsearchTimeouts(config));
    }
}
