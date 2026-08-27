package ph.edu.eac.filedirectory.file;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FileVersionRepository extends JpaRepository<FileVersion, Long> {
    List<FileVersion> findByFileOrderByVersionNumberDesc(FileEntity file);

    Optional<FileVersion> findByFileAndVersionNumber(FileEntity file, int versionNumber);

    boolean existsByFileAndVersionNumber(FileEntity file, int versionNumber);
}
