package ph.edu.eac.filedirectory.share;

import org.springframework.data.jpa.repository.JpaRepository;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.user.AppUser;

import java.util.List;
import java.util.Optional;

public interface FileShareRepository extends JpaRepository<FileShare, Long> {
    boolean existsByFileAndRecipientAndRevokedAtIsNull(FileEntity file, AppUser recipient);
    Optional<FileShare> findByFileAndRecipient(FileEntity file, AppUser recipient);
    List<FileShare> findByFileAndRevokedAtIsNullOrderByCreatedAtDesc(FileEntity file);
}
