-- Phase 2 storage maintenance queue.
-- Required on deployments that use JPA_DDL_AUTO=none, such as the Tomcat server.
-- Run once against the eac_directory database before deploying the WAR.

CREATE TABLE IF NOT EXISTS orphan_cleanup_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    stored_path VARCHAR(512) NOT NULL,
    size_bytes BIGINT NOT NULL,
    scheduled_by_id BIGINT NOT NULL,
    scheduled_at DATETIME(6) NOT NULL,
    eligible_at DATETIME(6) NOT NULL,
    reason VARCHAR(400) NOT NULL,
    backup_reference VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    completed_at DATETIME(6) NULL,
    completed_by_id BIGINT NULL,
    completion_note VARCHAR(400) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_orphan_cleanup_entries_path UNIQUE (stored_path),
    CONSTRAINT fk_orphan_cleanup_entries_scheduled_by
        FOREIGN KEY (scheduled_by_id) REFERENCES app_users (id),
    CONSTRAINT fk_orphan_cleanup_entries_completed_by
        FOREIGN KEY (completed_by_id) REFERENCES app_users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
