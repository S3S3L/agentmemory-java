package com.agentmemory.model;

import java.time.Instant;

public record SessionRecord(
    String id,
    String projectPath,
    Instant startTime,
    Instant endTime,
    int observationCount,
    String summary
) {}
