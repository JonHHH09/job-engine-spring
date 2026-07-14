package org.instruct.jobenginespring.application.search;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Canonical Unicode normalization and tokenization for scoring and persisted search terms. */
public final class SearchTextNormalizer {
    public static final int MAX_QUERY_CHARACTERS = 256;
    public static final int MAX_QUERY_TOKENS = 16;
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9+#.]+", Pattern.CASE_INSENSITIVE);

    private SearchTextNormalizer() {
    }

    public static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return MARKS.matcher(Normalizer.normalize(text.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFKD))
                .replaceAll("");
    }

    public static List<String> tokens(String text) {
        var normalized = normalize(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        var tokens = new LinkedHashSet<String>();
        for (var token : TOKEN_SPLIT.split(normalized)) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    /** Returns bounded query-token prefixes together with the token each prefix belongs to. */
    public static QueryPrefixes prefixes(List<String> tokens) {
        var values = new ArrayList<String>();
        var owners = new ArrayList<String>();
        for (var token : tokens) {
            int codePointCount = token.codePointCount(0, token.length());
            for (int length = 1; length <= codePointCount; length++) {
                values.add(token.substring(0, token.offsetByCodePoints(0, length)));
                owners.add(token);
            }
        }
        return new QueryPrefixes(values, owners);
    }

    public record QueryPrefixes(List<String> values, List<String> owners) {
        public QueryPrefixes {
            values = List.copyOf(values);
            owners = List.copyOf(owners);
            if (values.size() != owners.size()) {
                throw new IllegalArgumentException("prefix values and owners must have equal size");
            }
        }
    }
}
