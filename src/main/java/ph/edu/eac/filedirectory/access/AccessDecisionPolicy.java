package ph.edu.eac.filedirectory.access;

/**
 * How access requests for one particular file should be decided - set by
 * the uploader at upload time (see UploadController, upload.html) and
 * checked by AccessRequestService.request() before a new AccessRequest
 * would otherwise go PENDING. MANUAL (the default) is today's Phase 9
 * behavior unchanged: the uploader (or, later, a moderator/admin fallback)
 * reviews and decides each request by hand. AUTO_APPROVE/AUTO_REJECT skip
 * that wait entirely - see AccessRequestService for how the auto-decided
 * request still gets a real AccessGrantToken (if approved) and the same
 * notification/email/audit trail as a manual decision, just without a human
 * having clicked anything.
 */
public enum AccessDecisionPolicy {
    MANUAL,
    AUTO_APPROVE,
    AUTO_REJECT
}
