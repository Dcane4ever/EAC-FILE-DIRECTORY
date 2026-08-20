package ph.edu.eac.filedirectory.audit;

/** What kind of thing AuditEvent.targetId refers to, so the id alone (a bare Long) is unambiguous. */
public enum AuditTargetType {
    USER,
    FILE,
    DEPARTMENT,
    NONE
}
