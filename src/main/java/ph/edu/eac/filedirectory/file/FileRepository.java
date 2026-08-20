package ph.edu.eac.filedirectory.file;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ph.edu.eac.filedirectory.user.AppUser;

import java.util.List;
import java.util.Optional;

/**
 * JpaSpecificationExecutor backs BrowseController's combined-filter search
 * (see FileSpecifications/FileSearchCriteria) - the old single-filter
 * findByStatusAndXxx methods below are kept as-is for other callers
 * (my-uploads counts, etc.) that only ever need one condition and don't
 * need the flexibility of a Specification.
 */
public interface FileRepository extends JpaRepository<FileEntity, Long>, JpaSpecificationExecutor<FileEntity> {

    Page<FileEntity> findByStatus(FileStatus status, Pageable pageable);

    List<FileEntity> findByStatusOrderByCreatedAtAsc(FileStatus status);

    long countByStatus(FileStatus status);

    long countByStatusAndCategoryId(FileStatus status, Long categoryId);

    long countByStatusAndCategoryIdIn(FileStatus status, java.util.Collection<Long> categoryIds);

    long countByStatusAndDepartmentId(FileStatus status, Long departmentId);

    Page<FileEntity> findByUploaderOrderByCreatedAtDesc(AppUser uploader, Pageable pageable);

    Page<FileEntity> findByStatusAndDepartmentId(FileStatus status, Long departmentId, Pageable pageable);

    Page<FileEntity> findByStatusAndCategoryId(FileStatus status, Long categoryId, Pageable pageable);

    Page<FileEntity> findByStatusAndProgramId(FileStatus status, Long programId, Pageable pageable);

    Page<FileEntity> findByStatusAndCourseId(FileStatus status, Long courseId, Pageable pageable);

    @Query("""
            select f from FileEntity f
            where f.status = :status
              and (lower(f.title) like lower(concat('%', :q, '%'))
                   or lower(f.description) like lower(concat('%', :q, '%'))
                   or exists (select 1 from f.tags t where lower(t.name) like lower(concat('%', :q, '%'))))
            """)
    Page<FileEntity> search(@Param("status") FileStatus status, @Param("q") String query, Pageable pageable);

    /**
     * Duplicate detection (see UploadController, SystemSetting.allowDuplicateUploads)
     * - checked against every file regardless of status, since a PENDING or
     * even REJECTED row still means this exact content already exists in
     * the system. APPROVED matches are preferred first (most useful thing
     * to show/link an uploader to - the "case when" avoids ordering by
     * FileStatus's string value directly, which would happen to work today
     * only because "APPROVED" is alphabetically first among the three
     * values, not because that's actually what's being expressed), then
     * most recent, in case of multiple matches.
     */
    @Query("""
            select f from FileEntity f
            where f.checksum = :checksum
            order by case when f.status = ph.edu.eac.filedirectory.file.FileStatus.APPROVED then 0 else 1 end,
                     f.createdAt desc
            """)
    List<FileEntity> findByChecksumOrderByRelevance(@Param("checksum") String checksum);

    default Optional<FileEntity> findFirstByChecksum(String checksum) {
        List<FileEntity> matches = findByChecksumOrderByRelevance(checksum);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }
}
