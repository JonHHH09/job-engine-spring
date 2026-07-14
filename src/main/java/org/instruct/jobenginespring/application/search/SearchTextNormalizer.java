package org.instruct.jobenginespring.application.search;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Canonical Unicode normalization and tokenization for scoring and persisted search terms. */
public final class SearchTextNormalizer {
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
}
