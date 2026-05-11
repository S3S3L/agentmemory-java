package com.agentmemory.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

public record SessionRecord(
    String id,
    String projectPath,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    Instant startTime,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    Instant endTime,
    int observationCount,
    String summary
) {}
