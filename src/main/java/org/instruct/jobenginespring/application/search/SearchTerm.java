package org.instruct.jobenginespring.application.search;

import java.util.List;
import java.util.UUID;

/** One weighted, normalized posting in the database-backed inverted search index. */
public record SearchTerm(UUID entityId, String fieldKey, String term, int weight) {
    public static List<SearchTerm> from(UUID entityId, String fieldKey, String text, int weight) {
        return SearchTextNormalizer.tokens(text).stream()
                .map(term -> new SearchTerm(entityId, fieldKey, term, weight))
                .toList();
    }
}
