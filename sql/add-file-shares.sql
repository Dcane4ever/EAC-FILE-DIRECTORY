-- Private EAC sharing. Required on Tomcat deployments using JPA_DDL_AUTO=none.
-- Run once against the eac_directory database before deploying the WAR.

CREATE TABLE IF NOT EXISTS file_shares (
    id BIGINT NOT NULL AUTO_INCREMENT,
    file_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    shared_by_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    revoked_by_id BIGINT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_file_shares_file_recipient UNIQUE (file_id, recipient_id),
    CONSTRAINT fk_file_shares_file FOREIGN KEY (file_id) REFERENCES files (id),
    CONSTRAINT fk_file_shares_recipient FOREIGN KEY (recipient_id) REFERENCES app_users (id),
    CONSTRAINT fk_file_shares_shared_by FOREIGN KEY (shared_by_id) REFERENCES app_users (id),
    CONSTRAINT fk_file_shares_revoked_by FOREIGN KEY (revoked_by_id) REFERENCES app_users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
