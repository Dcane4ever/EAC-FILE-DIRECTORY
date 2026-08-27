package ph.edu.eac.filedirectory.file;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FileVersionRepository extends JpaRepository<FileVersion, Long> {
    List<FileVersion> findByFileOrderByVersionNumberDesc(FileEntity file);

    List<FileVersion> findByStatusOrderByCreatedAtAsc(FileStatus status);

    List<FileVersion> findByFileAndStatusOrderByVersionNumberDesc(FileEntity file, FileStatus status);

    Optional<FileVersion> findByFileAndVersionNumber(FileEntity file, int versionNumber);

    Optional<FileVersion> findFirstByFileAndStatusOrderByVersionNumberDesc(FileEntity file, FileStatus status);

    boolean existsByFileAndVersionNumber(FileEntity file, int versionNumber);
}
