package ph.edu.eac.filedirectory.file;

/**
 * Every optional filter Browse can apply, all AND-combinable - see
 * FileSpecifications.matching(). Any field left null means "don't filter on
 * this". Deliberately a flat record rather than a builder with defaults
 * scattered across BrowseController - one place to see the whole shape of
 * what's filterable.
 */
public record FileSearchCriteria(
        String query,
        Long departmentId,
        Long programId,
        Long courseId,
        Long categoryId,
        String tag,
        Integer year,
        String fileExtension,
        Long uploaderId
) {
    public static FileSearchCriteria empty() {
        return new FileSearchCriteria(null, null, null, null, null, null, null, null, null);
    }
}
