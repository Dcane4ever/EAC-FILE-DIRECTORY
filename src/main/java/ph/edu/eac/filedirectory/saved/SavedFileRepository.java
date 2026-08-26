package ph.edu.eac.filedirectory.saved;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.user.AppUser;

import java.util.Optional;

public interface SavedFileRepository extends JpaRepository<SavedFile, Long> {

    Page<SavedFile> findByUserOrderByCreatedAtDesc(AppUser user, Pageable pageable);

    Optional<SavedFile> findByUserAndFile(AppUser user, FileEntity file);

    boolean existsByUserAndFile(AppUser user, FileEntity file);

    long countByUser(AppUser user);
}
