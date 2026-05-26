package com.agentmemory.model;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands and normalizes search queries for better retrieval.
 * Removes stop words, extracts phrases, tokenizes, applies simple stemming,
 * and expands with coding-agent domain synonyms.
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
        "most", "other", "some", "such", "only", "own", "same", "as",
        "did", "set", "get", "use", "used", "using"
    );

    private static final Pattern PHRASE_PATTERN = Pattern.compile("\"([^\"]+)\"");

    // Ordered by length descending so longer suffixes are stripped first
    private static final String[] STEM_SUFFIXES = {"ation", "ing", "ise", "ize", "ion", "ed", "er"};

    private static final Map<String, List<String>> SYNONYMS = new LinkedHashMap<>();

    static {
        SYNONYMS.put("auth",           List.of("authentication", "oauth", "jwt", "login", "token", "credentials", "session"));
        SYNONYMS.put("authentication", List.of("auth", "oauth", "jwt", "login", "token", "credentials"));
        SYNONYMS.put("oauth",          List.of("auth", "authentication", "callback", "token", "provider"));
        SYNONYMS.put("jwt",            List.of("auth", "token", "bearer", "middleware", "validation"));
        SYNONYMS.put("token",          List.of("jwt", "auth", "bearer", "session"));
        SYNONYMS.put("database",       List.of("db", "sql", "postgres", "postgresql", "mysql", "prisma", "orm", "query"));
        SYNONYMS.put("db",             List.of("database", "sql", "postgres", "prisma", "orm"));
        SYNONYMS.put("sql",            List.of("database", "db", "query", "postgres", "orm", "prisma"));
        SYNONYMS.put("postgres",       List.of("database", "db", "sql", "postgresql", "prisma"));
        SYNONYMS.put("postgresql",     List.of("postgres", "database", "db", "sql"));
        SYNONYMS.put("prisma",         List.of("orm", "database", "db", "migration", "schema", "postgres"));
        SYNONYMS.put("orm",            List.of("prisma", "database", "model", "schema", "migration"));
        SYNONYMS.put("test",           List.of("testing", "jest", "vitest", "spec", "unit", "integration"));
        SYNONYMS.put("testing",        List.of("test", "jest", "vitest", "spec", "unit"));
        SYNONYMS.put("jest",           List.of("test", "testing", "vitest", "spec", "mock"));
        SYNONYMS.put("vitest",         List.of("test", "testing", "jest", "spec", "mock"));
        SYNONYMS.put("ci",             List.of("ci/cd", "pipeline", "github-actions", "workflow", "build", "deploy"));
        SYNONYMS.put("cd",             List.of("ci/cd", "pipeline", "deploy", "github-actions", "workflow"));
        SYNONYMS.put("pipeline",       List.of("ci", "cd", "github-actions", "workflow", "build", "deploy"));
        SYNONYMS.put("deploy",         List.of("deployment", "pipeline", "kubernetes", "k8s", "docker", "helm", "release"));
        SYNONYMS.put("deployment",     List.of("deploy", "kubernetes", "k8s", "docker", "helm", "pipeline", "release"));
        SYNONYMS.put("kubernetes",     List.of("k8s", "pod", "helm", "deploy", "cluster", "container"));
        SYNONYMS.put("k8s",            List.of("kubernetes", "pod", "helm", "deploy", "cluster"));
        SYNONYMS.put("docker",         List.of("container", "dockerfile", "image", "kubernetes", "k8s"));
        SYNONYMS.put("container",      List.of("docker", "kubernetes", "k8s", "image"));
        SYNONYMS.put("cache",          List.of("caching", "redis", "memcached", "performance", "ttl"));
        SYNONYMS.put("caching",        List.of("cache", "redis", "performance", "ttl"));
        SYNONYMS.put("redis",          List.of("cache", "caching", "session", "queue", "pubsub"));
        SYNONYMS.put("api",            List.of("endpoint", "rest", "route", "http", "request", "response"));
        SYNONYMS.put("endpoint",       List.of("api", "route", "rest", "http", "handler"));
        SYNONYMS.put("route",          List.of("api", "endpoint", "handler", "controller", "middleware"));
        SYNONYMS.put("error",          List.of("exception", "bug", "failure", "crash", "issue", "problem"));
        SYNONYMS.put("exception",      List.of("error", "bug", "failure", "throw", "catch"));
        SYNONYMS.put("debug",          List.of("debugging", "error", "issue", "trace", "log"));
        SYNONYMS.put("debugging",      List.of("debug", "error", "issue", "trace", "log"));
        SYNONYMS.put("security",       List.of("auth", "authentication", "authorization", "permission", "rbac", "csrf", "xss", "injection"));
        SYNONYMS.put("config",         List.of("configuration", "settings", "env", "environment", "properties"));
        SYNONYMS.put("configuration",  List.of("config", "settings", "env", "environment"));
        SYNONYMS.put("migration",      List.of("database", "db", "schema", "prisma", "orm", "flyway", "liquibase"));
        SYNONYMS.put("performance",    List.of("optimization", "speed", "latency", "cache", "index", "query"));
        SYNONYMS.put("monitoring",     List.of("observability", "metrics", "logging", "tracing", "prometheus", "grafana"));
        SYNONYMS.put("observability",  List.of("monitoring", "metrics", "logging", "tracing"));
    }

    /**
     * Apply simple suffix-based stemming. Returns the stem if a suffix is stripped
     * and the result is at least 4 chars long; otherwise returns the original word.
     * The original word is always preserved alongside the stem in the keyword set.
     */
    static String stem(String word) {
        if (word.length() <= 5) return word;
        for (String suffix : STEM_SUFFIXES) {
            if (word.endsWith(suffix) && word.length() - suffix.length() >= 4) {
                return word.substring(0, word.length() - suffix.length());
            }
        }
        return word;
    }

    /**
     * Expand a query into searchable components.
     */
    public static ExpandedQuery expand(String query) {
        if (query == null || query.isBlank()) {
            return new ExpandedQuery(query, List.of(), query, List.of(), List.of());
        }

        // Extract phrases in quotes
        List<String> phrases = new ArrayList<>();
        Matcher matcher = PHRASE_PATTERN.matcher(query);
        while (matcher.find()) {
            phrases.add(matcher.group(1).trim());
        }
        String textWithoutQuotes = query.replaceAll("\"[^\"]+\"", "").trim();

        // Tokenize: split on whitespace and punctuation
        String[] rawTokens = textWithoutQuotes.split("[\\s,;:!?()\\[\\]{}]+");
        List<String> tokens = new ArrayList<>();
        // Use LinkedHashSet to preserve insertion order and de-duplicate
        Set<String> keywords = new LinkedHashSet<>();

        for (String token : rawTokens) {
            String cleaned = token.toLowerCase().trim();
            if (cleaned.length() > 1) {
                tokens.add(cleaned);
                if (!STOP_WORDS.contains(cleaned)) {
                    keywords.add(cleaned);
                    // Also add stem (keep original too)
                    String stemmed = stem(cleaned);
                    if (!stemmed.equals(cleaned)) {
                        keywords.add(stemmed);
                    }
                }
            }
        }

        // Collect synonyms for each keyword (and its stem)
        Set<String> synonymsAdded = new LinkedHashSet<>();
        for (String kw : new ArrayList<>(keywords)) {
            List<String> syns = SYNONYMS.get(kw);
            if (syns != null) {
                for (String s : syns) {
                    if (!keywords.contains(s) && !STOP_WORDS.contains(s)) {
                        synonymsAdded.add(s);
                    }
                }
            }
        }

        // normalizedQuery = keywords + synonyms (all unique)
        Set<String> allTerms = new LinkedHashSet<>(keywords);
        allTerms.addAll(synonymsAdded);
        String normalizedQuery = String.join(" ", allTerms);
        if (normalizedQuery.isBlank()) {
            normalizedQuery = query;
        }

        return new ExpandedQuery(query, tokens, normalizedQuery, phrases, new ArrayList<>(synonymsAdded));
    }

    public record ExpandedQuery(
        String originalQuery,
        List<String> tokens,
        String normalizedQuery,
        List<String> phrases,
        List<String> synonymsAdded
    ) {}
}
