package ph.edu.eac.filedirectory.search;

import jakarta.persistence.*;
import ph.edu.eac.filedirectory.user.AppUser;

import java.time.Instant;

/**
 * One logged text search submitted on /browse?q=... (see BrowseController).
 * Powers the "Popular searches" pills on the home page - aggregated across
 * everyone, not per-user - see SearchQueryRepository.topTerms. Only the
 * free-text search box is logged; clicking a department/category filter
 * without typing a query does not create a row here.
 */
@Entity
@Table(name = "search_queries", indexes = {
        @Index(name = "idx_search_queries_normalized_created", columnList = "normalizedTerm,createdAt")
})
public class SearchQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Exactly what the user typed, for reference/debugging. */
    @Column(nullable = false, length = 255)
    private String rawTerm;

    /** Trimmed + lowercased, used for grouping so "Thesis" and "thesis" count together. */
    @Column(nullable = false, length = 255)
    private String normalizedTerm;

    /** Null for anonymous/unauthenticated searches, if the app ever allows those. */
    @ManyToOne
    @JoinColumn(name = "searcher_id")
    private AppUser searcher;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected SearchQuery() {
        // JPA
    }

    public SearchQuery(String rawTerm, String normalizedTerm, AppUser searcher) {
        this.rawTerm = rawTerm;
        this.normalizedTerm = normalizedTerm;
        this.searcher = searcher;
    }

    public Long getId() {
        return id;
    }

    public String getRawTerm() {
        return rawTerm;
    }

    public String getNormalizedTerm() {
        return normalizedTerm;
    }

    public AppUser getSearcher() {
        return searcher;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
