package ph.edu.eac.filedirectory.access;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccessGrantTokenRepository extends JpaRepository<AccessGrantToken, Long> {
    Optional<AccessGrantToken> findByToken(String token);

    Optional<AccessGrantToken> findByAccessRequest(AccessRequest accessRequest);
}
