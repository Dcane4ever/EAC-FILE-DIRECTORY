package ph.edu.eac.filedirectory.access;

/** Lifecycle of one AccessRequest - see that entity and AccessRequestService. */
public enum AccessRequestStatus {
    PENDING,
    APPROVED,
    DENIED,
    EXPIRED
}
