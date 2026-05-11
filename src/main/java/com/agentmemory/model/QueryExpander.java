package com.agentmemory.model;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands and normalizes search queries for better retrieval.
 * Removes stop words, extracts phrases, tokenizes.
 */
public class QueryExpander {

    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
        "of", "with", "by", "from", "is", "are", "was", "were", "be", "been",
        "being", "have", "has", "had", "do", "does", "did", "will", "would",
        "could", "should", "may", "might", "can", "how", "what", "when",
        "where", "why", "who", "which", "that", "this", "these", "those",
        "it", "its", "we", "our", "you", "your", "he", "she", "they", "them",
        "his", "her", "their", "my", "mine", "me", "us", "not", "no", "so",
        "if", "then", "than", "too", "very", "just", "about", "up", "out",
        "into", "over", "after", "before", "between", "under", "again",
        "further", "once", "here", "there", "all", "each", "few", "more",
        "most", "other", "some", "such", "only", "own", "same", "as"
    );

    private static final Pattern PHRASE_PATTERN = Pattern.compile("\"([^\"]+)\"");

    /**
     * Expand a query into searchable components.
     */
    public static ExpandedQuery expand(String query) {
        if (query == null || query.isBlank()) {
            return new ExpandedQuery(query, List.of(), query, List.of());
        }

        // Extract phrases in quotes
        List<String> phrases = new ArrayList<>();
        String textWithoutQuotes = query;
        Matcher matcher = PHRASE_PATTERN.matcher(query);
        while (matcher.find()) {
            phrases.add(matcher.group(1).trim());
        }
        textWithoutQuotes = query.replaceAll("\"[^\"]+\"", "").trim();

        // Tokenize: split on whitespace and punctuation
        String[] rawTokens = textWithoutQuotes.split("[\\s,;:!?()\\[\\]{}]+");
        List<String> tokens = new ArrayList<>();
        Set<String> keywords = new LinkedHashSet<>();

        for (String token : rawTokens) {
            String cleaned = token.toLowerCase().trim();
            if (cleaned.length() > 1) {
                tokens.add(cleaned);
                if (!STOP_WORDS.contains(cleaned)) {
                    keywords.add(cleaned);
                }
            }
        }

        // Build normalized query from keywords
        String normalizedQuery = String.join(" ", keywords);

        return new ExpandedQuery(query, tokens, normalizedQuery, phrases);
    }

    public record ExpandedQuery(
        String originalQuery,
        List<String> tokens,
        String normalizedQuery,
        List<String> phrases
    ) {}
}
