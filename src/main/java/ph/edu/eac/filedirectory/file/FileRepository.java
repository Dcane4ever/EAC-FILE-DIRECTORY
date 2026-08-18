package ph.edu.eac.filedirectory.file;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ph.edu.eac.filedirectory.user.AppUser;

import java.util.List;

public interface FileRepository extends JpaRepository<FileEntity, Long> {

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
}
