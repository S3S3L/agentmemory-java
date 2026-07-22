package com.agentmemory.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TokenBudgetTest {

    private static SearchResult make(String content, String file, String tool, String tier, double score, double rerank) {
        return new SearchResult("id-" + content, content, MemoryTier.valueOf(tier), "sess-1", tool, file, score, 0.5, 0.3, rerank);
    }

    @Test
    void format_emptyList_returnsNotFound() {
        assertEquals("No matching memories found.", TokenBudget.format(List.of(), 1000));
    }

    @Test
    void format_singleResult_containsAllFields() {
        var result = make("test content", "src/main.rs", "Read", "WORKING", 0.5, 0.0);
        String text = TokenBudget.format(List.of(result), 1000);

        assertTrue(text.contains("Found 1 relevant"));
        assertTrue(text.contains("WORKING"));
        assertTrue(text.contains("Read"));
        assertTrue(text.contains("src/main.rs"));
        assertTrue(text.contains("test content"));
        assertTrue(text.contains("ID: id-test content"));
    }

    @Test
    void format_multipleResults_numbered() {
        var results = List.of(
            make("first", "a.txt", "Read", "WORKING", 0.5, 0.0),
            make("second", "b.txt", "Write", "SEMANTIC", 0.3, 0.0)
        );
        String text = TokenBudget.format(results, 1000);

        assertTrue(text.contains("Found 2 relevant"));
        assertTrue(text.contains("## Memory 1"));
        assertTrue(text.contains("## Memory 2"));
    }

    @Test
    void format_truncatesWithinBudget() {
        var results = List.of(
            make("A".repeat(500), "a.txt", "Read", "WORKING", 0.5, 0.0),
            make("B".repeat(500), "b.txt", "Write", "WORKING", 0.3, 0.0)
        );
        String text = TokenBudget.format(results, 200);
        int maxChars = 200 * 3;

        assertTrue(text.length() <= maxChars + 50, "Should not exceed budget significantly");
        assertTrue(text.contains("truncated to fit token budget"));
    }

    @Test
    void formatWithRerank_showsRerankScore() {
        var result = make("test", "a.txt", "Read", "WORKING", 0.5, 0.92);
        String text = TokenBudget.formatWithRerank(List.of(result), 1000);

        assertTrue(text.contains("0.92"));
        assertTrue(text.contains("ID: id-test"));
    }

    @Test
    void formatWithRerank_showsRawScoreWhenRerankZero() {
        var result = make("test", "a.txt", "Read", "WORKING", 0.35, 0.0);
        String text = TokenBudget.formatWithRerank(List.of(result), 1000);

        assertTrue(text.contains("0.00"));
        assertTrue(text.contains("raw: 0.35"));
    }

    @Test
    void formatWithRerank_emptyList() {
        assertEquals("No matching memories found.", TokenBudget.formatWithRerank(List.of(), 1000));
    }

    @Test
    void format_handlesNullContent() {
        var result = new SearchResult("id-1", null, MemoryTier.WORKING, "sess-1", null, null, 0.5, 0.5, 0.3, 0.0);
        String text = TokenBudget.format(List.of(result), 1000);

        assertTrue(text.contains("Found 1 relevant"));
    }
}
