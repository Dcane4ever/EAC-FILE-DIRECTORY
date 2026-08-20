package ph.edu.eac.filedirectory.web;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ph.edu.eac.filedirectory.audit.AuditAction;
import ph.edu.eac.filedirectory.audit.AuditEventRepository;
import ph.edu.eac.filedirectory.audit.AuditTargetType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Admin-only audit log review page - see SecurityConfig, /admin/users/**
 * (this controller's mapping falls under that prefix) requires ROLE_ADMIN
 * specifically, matching Manage Users' gating: reviewing the institutional
 * audit trail is an admin-level responsibility, not something a moderator
 * needs day to day. Read-only - see AuditEventRepository/AuditService for
 * why nothing here can edit or delete a row.
 */
@Controller
@RequestMapping("/admin/users/audit-log")
public class AuditLogController {

    private final AuditEventRepository auditEventRepository;

    public AuditLogController(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String actorEmail,
                        @RequestParam(required = false) AuditAction action,
                        @RequestParam(required = false) AuditTargetType targetType,
                        @RequestParam(required = false) String from,
                        @RequestParam(required = false) String to,
                        @RequestParam(defaultValue = "0") int page,
                        Model model) {
        Instant fromInstant = parseStartOfDay(from);
        Instant toInstant = parseEndOfDay(to);

        Pageable pageable = PageRequest.of(page, 30, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ph.edu.eac.filedirectory.audit.AuditEvent> events = auditEventRepository.search(
                blankToNull(actorEmail), action, targetType, fromInstant, toInstant, pageable);

        model.addAttribute("events", events);
        model.addAttribute("actions", AuditAction.values());
        model.addAttribute("targetTypes", AuditTargetType.values());
        model.addAttribute("actorEmail", actorEmail);
        model.addAttribute("selectedAction", action);
        model.addAttribute("selectedTargetType", targetType);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        return "admin/audit-log";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Instant parseStartOfDay(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        return LocalDate.parse(dateStr).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private Instant parseEndOfDay(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        return LocalDate.parse(dateStr).plusDays(1).atStartOfDay(ZoneId.systemDefault()).minusNanos(1).toInstant();
    }
}
