package ph.edu.eac.filedirectory.file;

import org.springframework.data.domain.Sort;

/**
 * The sort choices Browse exposes - see BrowseController's ?sort= param.
 * RELEVANCE only means something distinct from NEWEST when a search query
 * is present; the LIKE-based search in FileSpecifications has no real
 * relevance score to rank by; falls back to newest-first, which is the same
 * "best guess at what's relevant" a simple keyword search can honestly
 * offer without a proper full-text ranking engine, and is stated as such
 * rather than pretending a scored ranking exists.
 */
public enum FileSortOption {
    RELEVANCE("Most Relevant"),
    NEWEST("Newest"),
    OLDEST("Oldest"),
    ALPHABETICAL("A to Z"),
    MOST_DOWNLOADED("Most Downloaded");

    private final String label;

    FileSortOption(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public Sort toSort() {
        return switch (this) {
            case NEWEST, RELEVANCE -> Sort.by(Sort.Direction.DESC, "createdAt");
            case OLDEST -> Sort.by(Sort.Direction.ASC, "createdAt");
            case ALPHABETICAL -> Sort.by(Sort.Direction.ASC, "title");
            case MOST_DOWNLOADED -> Sort.by(Sort.Direction.DESC, "downloadCount");
        };
    }

    public static FileSortOption fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return RELEVANCE;
        }
        try {
            return FileSortOption.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return RELEVANCE;
        }
    }
}
