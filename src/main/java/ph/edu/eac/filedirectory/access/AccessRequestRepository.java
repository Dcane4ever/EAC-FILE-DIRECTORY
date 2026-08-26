package ph.edu.eac.filedirectory.access;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.user.AppUser;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AccessRequestRepository extends JpaRepository<AccessRequest, Long> {

    /** Used to block a second PENDING request for the same file by the same person - see AccessRequestService.request(). */
    Optional<AccessRequest> findFirstByFileAndRequesterAndStatus(FileEntity file, AppUser requester, AccessRequestStatus status);

    /** "My Requests" page - AccessRequestController. */
    Page<AccessRequest> findByRequesterOrderByCreatedAtDesc(AppUser requester, Pageable pageable);

    Page<AccessRequest> findByRequesterAndStatusOrderByCreatedAtDesc(AppUser requester, AccessRequestStatus status, Pageable pageable);

    /** "Access Requests" section under My Uploads - scoped by ownership via the join, an uploader only ever sees requests on their own files. */
    Page<AccessRequest> findByFile_UploaderOrderByCreatedAtDesc(AppUser uploader, Pageable pageable);

    Page<AccessRequest> findByFile_UploaderAndStatusOrderByCreatedAtDesc(AppUser uploader, AccessRequestStatus status, Pageable pageable);

    long countByFile_UploaderAndStatus(AppUser uploader, AccessRequestStatus status);

    /** Sweep target for the scheduled cleanup job (see AccessRequestService) - PENDING requests old enough for the moderator fallback window to potentially apply, or APPROVED-but-unused-and-past-expiry requests that need to flip to EXPIRED. */
    List<AccessRequest> findByStatusAndCreatedAtBefore(AccessRequestStatus status, Instant cutoff);
}
