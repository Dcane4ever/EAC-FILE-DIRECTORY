package ph.edu.eac.filedirectory.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * Read-only in practice - see AuditEvent's class comment on "append-only".
 * This interface deliberately declares no delete/update query methods
 * beyond what JpaRepository provides by default, so nothing in this
 * codebase can accidentally mutate or remove a written audit row.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /**
     * Backs the admin-facing audit log page's filters (actor email
     * substring, action, target type, date range) - every parameter is
     * optional (pass null to mean "don't filter on this"), so one query
     * covers "show everything" through "show only ROLE_CHANGED events by
     * this actor in this date range".
     */
    @Query("""
            select e from AuditEvent e
            where (:actorEmail is null or lower(e.actorEmail) like lower(concat('%', :actorEmail, '%')))
              and (:action is null or e.action = :action)
              and (:targetType is null or e.targetType = :targetType)
              and (:from is null or e.createdAt >= :from)
              and (:to is null or e.createdAt <= :to)
            order by e.createdAt desc
            """)
    Page<AuditEvent> search(@Param("actorEmail") String actorEmail,
                             @Param("action") AuditAction action,
                             @Param("targetType") AuditTargetType targetType,
                             @Param("from") Instant from,
                             @Param("to") Instant to,
                             Pageable pageable);
}
