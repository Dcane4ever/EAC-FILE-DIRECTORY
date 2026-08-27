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

    @Query("""
            select coalesce(sum(f.fileSize), 0)
            from FileEntity f
            """)
    long sumFileSize();

    @Query("""
            select coalesce(sum(f.downloadCount), 0)
            from FileEntity f
            """)
    long sumDownloadCount();

    @Query("""
            select f.department.name as name, count(f) as fileCount
            from FileEntity f
            group by f.department.name
            order by count(f) desc, f.department.name asc
            """)
    List<NameCount> countFilesByDepartment(Pageable pageable);

    @Query("""
            select f.category.name as name, count(f) as fileCount
            from FileEntity f
            group by f.category.name
            order by count(f) desc, f.category.name asc
            """)
    List<NameCount> countFilesByCategory(Pageable pageable);

    @Query("""
            select f.status as status, count(f) as fileCount
            from FileEntity f
            group by f.status
            """)
    List<StatusCount> countFilesByStatus();

    List<FileEntity> findByStatusOrderByDownloadCountDescCreatedAtDesc(FileStatus status, Pageable pageable);

    long countByStatusAndCategoryId(FileStatus status, Long categoryId);

    long countByStatusAndCategoryIdIn(FileStatus status, java.util.Collection<Long> categoryIds);

    long countByStatusAndDepartmentId(FileStatus status, Long departmentId);

    Page<FileEntity> findByUploaderOrderByCreatedAtDesc(AppUser uploader, Pageable pageable);

    Page<FileEntity> findByUploaderAndStatusOrderByCreatedAtDesc(AppUser uploader, FileStatus status, Pageable pageable);

    long countByUploaderAndStatus(AppUser uploader, FileStatus status);

    @Query("""
            select coalesce(sum(f.downloadCount), 0)
            from FileEntity f
            where f.uploader = :uploader
              and f.status = :status
            """)
    long sumDownloadCountByUploaderAndStatus(@Param("uploader") AppUser uploader, @Param("status") FileStatus status);

    @Query("""
            select f.category.name as name, count(f) as fileCount
            from FileEntity f
            where f.uploader = :uploader
              and f.status = :status
            group by f.category.name
            order by count(f) desc, f.category.name asc
            """)
    List<CategoryContribution> topCategoriesByUploaderAndStatus(@Param("uploader") AppUser uploader,
                                                                 @Param("status") FileStatus status,
                                                                 Pageable pageable);

    Page<FileEntity> findByStatusAndDepartmentId(FileStatus status, Long departmentId, Pageable pageable);

    Page<FileEntity> findByStatusAndCategoryId(FileStatus status, Long categoryId, Pageable pageable);

    Page<FileEntity> findByStatusAndProgramId(FileStatus status, Long programId, Pageable pageable);

    Page<FileEntity> findByStatusAndCourseId(FileStatus status, Long courseId, Pageable pageable);

    /**
     * A small, intentionally explainable recommendation set for the detail
     * page. Shared tags carry the most weight, followed by course, program,
     * category, and department. Only approved records are candidates.
     */
    @Query("""
            select candidate from FileEntity candidate
            where candidate.status = :status
              and candidate.id <> :fileId
              and (
                   candidate.department.id = :departmentId
                   or candidate.category.id = :categoryId
                   or (:programId is not null and candidate.program.id = :programId)
                   or (:courseId is not null and candidate.course.id = :courseId)
                   or exists (select tag from candidate.tags tag where tag.id in :tagIds)
              )
            order by
              (case when exists (select tag from candidate.tags tag where tag.id in :tagIds) then 16 else 0 end
               + case when :courseId is not null and candidate.course.id = :courseId then 8 else 0 end
               + case when :programId is not null and candidate.program.id = :programId then 5 else 0 end
               + case when candidate.category.id = :categoryId then 3 else 0 end
               + case when candidate.department.id = :departmentId then 1 else 0 end) desc,
              candidate.createdAt desc
            """)
    List<FileEntity> findSimilarApproved(@Param("status") FileStatus status,
                                         @Param("fileId") Long fileId,
                                         @Param("departmentId") Long departmentId,
                                         @Param("categoryId") Long categoryId,
                                         @Param("programId") Long programId,
                                         @Param("courseId") Long courseId,
                                         @Param("tagIds") java.util.Collection<Long> tagIds,
                                         Pageable pageable);

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

    interface CategoryContribution {
        String getName();
        long getFileCount();
    }

    interface NameCount {
        String getName();
        long getFileCount();
    }

    interface StatusCount {
        FileStatus getStatus();
        long getFileCount();
    }
}
