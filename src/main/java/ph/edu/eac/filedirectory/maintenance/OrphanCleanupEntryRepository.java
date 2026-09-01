package ph.edu.eac.filedirectory.maintenance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrphanCleanupEntryRepository extends JpaRepository<OrphanCleanupEntry, Long> {
    Optional<OrphanCleanupEntry> findByStoredPath(String storedPath);
    List<OrphanCleanupEntry> findAllByOrderByScheduledAtDesc();
}
