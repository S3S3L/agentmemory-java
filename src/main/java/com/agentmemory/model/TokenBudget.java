package com.agentmemory.model;

/**
 * Utility for formatting search results within a token budget.
 */
public class TokenBudget {

    // Rough estimate: ~4 chars per token for English, ~2 chars for Chinese
    private static final int CHARS_PER_TOKEN = 3;

    /**
     * Format results as Markdown, truncating if total exceeds the token budget.
     */
    public static String format(java.util.List<SearchResult> results, int tokenBudget) {
        if (results.isEmpty()) return "No matching memories found.";

        int maxChars = tokenBudget * CHARS_PER_TOKEN;
        var sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" relevant memories:\n\n");
        int headerLen = sb.length();

        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            sb.append("## Memory ").append(i + 1).append(" [").append(r.tier()).append("]\n");
            if (r.id() != null) sb.append("- ID: ").append(r.id()).append("\n");
            if (r.sessionId() != null) sb.append("- Session: ").append(r.sessionId()).append("\n");
            if (r.toolName() != null) sb.append("- Tool: ").append(r.toolName()).append("\n");
            if (r.filePath() != null) sb.append("- File: ").append(r.filePath()).append("\n");
            if (r.content() != null) sb.append("- ").append(r.content()).append("\n");
            sb.append("\n");

            if (sb.length() > maxChars) {
                sb.setLength(maxChars);
                sb.append("\n... (truncated to fit token budget)");
                break;
            }
        }
        return sb.toString();
    }

    /**
     * Format results with rerank scores highlighted.
     */
    public static String formatWithRerank(java.util.List<SearchResult> results, int tokenBudget) {
        if (results.isEmpty()) return "No matching memories found.";

        int maxChars = tokenBudget * CHARS_PER_TOKEN;
        var sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" relevant memories:\n\n");

        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            sb.append("## Memory ").append(i + 1).append(" [").append(r.tier()).append("]\n");
            if (r.id() != null) sb.append("- ID: ").append(r.id()).append("\n");
            sb.append("- Relevance: ").append(String.format("%.2f", r.rerankScore()));
            if (r.score() > 0 && r.rerankScore() == 0) {
                sb.append(" (raw: ").append(String.format("%.2f", r.score())).append(")");
            }
            sb.append("\n");
            if (r.filePath() != null) sb.append("- File: `").append(r.filePath()).append("`\n");
            if (r.toolName() != null) sb.append("- Tool: ").append(r.toolName()).append("\n");
            if (r.content() != null) sb.append("- ").append(r.content()).append("\n");
            sb.append("\n");

            if (sb.length() > maxChars) {
                sb.setLength(maxChars);
                sb.append("\n... (truncated to fit token budget)");
                break;
            }
        }
        return sb.toString();
    }
}
