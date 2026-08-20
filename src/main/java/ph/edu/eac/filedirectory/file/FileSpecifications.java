package ph.edu.eac.filedirectory.file;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds one combined (AND) Specification<FileEntity> from a
 * FileSearchCriteria, so Browse's filters actually combine instead of the
 * old precedence chain (only the most specific filter used to win - see
 * BrowseController's prior implementation history). Every criterion is
 * independently optional; each private helper below contributes nothing
 * (returns a conjunction.isTrue() predicate) when its field is null/blank,
 * so any subset of filters composes correctly.
 */
public final class FileSpecifications {

    private FileSpecifications() {
    }

    public static Specification<FileEntity> matching(FileStatus status, FileSearchCriteria criteria) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), status));

            if (criteria.query() != null && !criteria.query().isBlank()) {
                String needle = "%" + criteria.query().trim().toLowerCase(Locale.ROOT) + "%";
                var tagJoin = root.join("tags", jakarta.persistence.criteria.JoinType.LEFT);
                Predicate titleMatch = cb.like(cb.lower(root.get("title")), needle);
                Predicate descriptionMatch = cb.like(cb.lower(cb.coalesce(root.get("description"), "")), needle);
                Predicate tagMatch = cb.like(cb.lower(tagJoin.get("name")), needle);
                predicates.add(cb.or(titleMatch, descriptionMatch, tagMatch));
                // A file can have multiple matching tags - without distinct,
                // the tag join above would multiply result rows.
                query.distinct(true);
            }

            if (criteria.departmentId() != null) {
                predicates.add(cb.equal(root.get("department").get("id"), criteria.departmentId()));
            }
            if (criteria.programId() != null) {
                predicates.add(cb.equal(root.get("program").get("id"), criteria.programId()));
            }
            if (criteria.courseId() != null) {
                predicates.add(cb.equal(root.get("course").get("id"), criteria.courseId()));
            }
            if (criteria.categoryId() != null) {
                predicates.add(cb.equal(root.get("category").get("id"), criteria.categoryId()));
            }
            if (criteria.tag() != null && !criteria.tag().isBlank()) {
                var tagJoin = root.join("tags", jakarta.persistence.criteria.JoinType.INNER);
                predicates.add(cb.equal(cb.lower(tagJoin.get("name")), criteria.tag().trim().toLowerCase(Locale.ROOT)));
                query.distinct(true);
            }
            if (criteria.year() != null) {
                ZoneId zone = ZoneId.systemDefault();
                ZonedDateTime start = ZonedDateTime.of(criteria.year(), 1, 1, 0, 0, 0, 0, zone);
                ZonedDateTime end = start.plusYears(1);
                predicates.add(cb.between(root.get("createdAt"), start.toInstant(), end.toInstant().minusNanos(1)));
            }
            if (criteria.fileExtension() != null && !criteria.fileExtension().isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("originalFilename")),
                        "%." + criteria.fileExtension().trim().toLowerCase(Locale.ROOT)));
            }
            if (criteria.uploaderId() != null) {
                predicates.add(cb.equal(root.get("uploader").get("id"), criteria.uploaderId()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
