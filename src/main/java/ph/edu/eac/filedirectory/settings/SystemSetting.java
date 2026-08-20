package ph.edu.eac.filedirectory.settings;

import jakarta.persistence.*;

/**
 * A single-row table of system-wide, admin-configurable toggles - the
 * system-scoped counterpart to Department.autoApprove (which is per-row).
 * Always exactly one row, id fixed at SINGLETON_ID - see
 * SystemSettingService, which creates that row on first read if it doesn't
 * exist yet, so callers never have to think about "what if settings were
 * never initialized". New system-wide toggles get a new column here rather
 * than a new table, since there is and only ever will be one row.
 */
@Entity
@Table(name = "system_settings")
public class SystemSetting {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    /** Whether an upload whose checksum exactly matches an existing file is allowed without the confirm-anyway step - see UploadController, default false (duplicates are flagged). */
    @Column(nullable = false)
    private boolean allowDuplicateUploads = false;

    /** Whether a moderator/admin may approve a file-access request the uploader hasn't answered within the fallback window - see AccessRequestService, default false (only the uploader can ever approve). */
    @Column(nullable = false)
    private boolean allowModeratorAccessRequestFallback = false;

    protected SystemSetting() {
        // JPA
    }

    public static SystemSetting defaults() {
        SystemSetting settings = new SystemSetting();
        settings.id = SINGLETON_ID;
        return settings;
    }

    public Long getId() {
        return id;
    }

    public boolean isAllowDuplicateUploads() {
        return allowDuplicateUploads;
    }

    public void setAllowDuplicateUploads(boolean allowDuplicateUploads) {
        this.allowDuplicateUploads = allowDuplicateUploads;
    }

    public boolean isAllowModeratorAccessRequestFallback() {
        return allowModeratorAccessRequestFallback;
    }

    public void setAllowModeratorAccessRequestFallback(boolean allowModeratorAccessRequestFallback) {
        this.allowModeratorAccessRequestFallback = allowModeratorAccessRequestFallback;
    }
}
