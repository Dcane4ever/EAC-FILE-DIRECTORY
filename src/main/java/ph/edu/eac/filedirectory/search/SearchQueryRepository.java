package ph.edu.eac.filedirectory.search;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SearchQueryRepository extends JpaRepository<SearchQuery, Long> {

    /** One ranked term: the normalized grouping key, a display form, and how many times it was searched in the window. */
    interface TrendingTerm {
        String getNormalizedTerm();
        /** Most recent raw (as-typed) spelling for this term, so "ICT" displays as typed rather than forced lowercase. */
        String getDisplayTerm();
        long getSearchCount();
    }

    /**
     * Site-wide trending search terms, grouped and counted across all users
     * (not per-user), restricted to the given window (e.g. last 30 days) -
     * see HomeController.POPULAR_SEARCH_WINDOW_DAYS. Requires at least
     * minCount occurrences so one-off/rare terms don't clutter the list.
     */
    @Query("""
            select q.normalizedTerm as normalizedTerm,
                   max(q.rawTerm) as displayTerm,
                   count(q) as searchCount
            from SearchQuery q
            where q.createdAt >= :since
            group by q.normalizedTerm
            having count(q) >= :minCount
            order by count(q) desc, max(q.createdAt) desc
            """)
    List<TrendingTerm> topTerms(@Param("since") Instant since, @Param("minCount") long minCount, Pageable pageable);
}
