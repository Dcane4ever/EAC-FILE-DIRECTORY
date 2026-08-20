package ph.edu.eac.filedirectory.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ph.edu.eac.filedirectory.user.AppUser;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipientOrderByCreatedAtDesc(AppUser recipient, Pageable pageable);

    long countByRecipientAndReadFalse(AppUser recipient);

    /** Bulk update rather than loading every row into memory just to flip one field - see roadmap's "don't load thousands of records unnecessarily" note. */
    @Modifying
    @Query("update Notification n set n.read = true where n.recipient = :recipient and n.read = false")
    void markAllAsRead(@Param("recipient") AppUser recipient);
}
