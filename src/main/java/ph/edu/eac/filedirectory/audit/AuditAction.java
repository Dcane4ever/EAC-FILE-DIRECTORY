package ph.edu.eac.filedirectory.audit;

/**
 * What happened, for one AuditEvent row. Grouped in comments by area to
 * match the roadmap's own event list - add new values here as new trigger
 * points are wired (see AuditService), never repurpose an existing one for
 * something it wasn't named for, since old rows keep whatever value they
 * were written with.
 */
public enum AuditAction {
    // --- Authentication ---
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGOUT,
    PASSWORD_RESET_REQUESTED,
    PASSWORD_RESET_COMPLETED,
    ACCOUNT_VERIFIED,

    // --- Files ---
    FILE_UPLOADED,
    FILE_APPROVED,
    FILE_REJECTED,
    FILE_ARCHIVED,
    FILE_RESTORED,
    FILE_DOWNLOADED,
    FILE_ACCESS_POLICY_CHANGED,

    // --- Access requests ---
    ACCESS_REQUESTED,
    ACCESS_APPROVED,
    ACCESS_DENIED,
    ACCESS_GRANT_DOWNLOADED,

    // --- Administration ---
    ROLE_CHANGED,
    ACCOUNT_CREATED_BY_ADMIN,
    DEPARTMENT_AUTO_APPROVE_TOGGLED,
    SYSTEM_SETTING_CHANGED
}
