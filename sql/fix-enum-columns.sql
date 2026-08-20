-- Converts every native MySQL ENUM column backing a Java enum field to a
-- plain VARCHAR, matching the @Column(length=...) changes just made in the
-- entity classes. Run this once against the real eac_directory schema.
--
-- WHY: Hibernate's ddl-auto=update maps @Enumerated(EnumType.STRING) with
-- no explicit @Column(length=...) as a native MySQL ENUM('A','B',...) -
-- and the allowed-value list gets locked in at CREATE TABLE time from
-- whatever Java enum constants existed THEN. ddl-auto=update never widens
-- an existing column's ENUM value list when the Java enum later grows new
-- constants - it only adds missing tables/columns. That's exactly what
-- broke audit_events.action just now ("Data truncated for column 'action'")
-- when ACCESS_REQUESTED/ACCESS_APPROVED/etc. were added after the table
-- already existed. VARCHAR has no such fixed value list, so it can't
-- recur. Existing ENUM row values convert cleanly to VARCHAR (MySQL just
-- writes out the enum label as a string) - no data is lost.
--
-- Run this BEFORE restarting the app with the updated entity classes, or
-- any INSERT touching one of the not-yet-widened columns can still fail
-- the same way in between.

USE eac_directory;

ALTER TABLE audit_events
    MODIFY COLUMN action VARCHAR(64) NOT NULL,
    MODIFY COLUMN target_type VARCHAR(64) NOT NULL;

ALTER TABLE notifications
    MODIFY COLUMN type VARCHAR(32) NOT NULL;

ALTER TABLE files
    MODIFY COLUMN status VARCHAR(32) NOT NULL;

ALTER TABLE app_users
    MODIFY COLUMN role VARCHAR(32) NOT NULL;

ALTER TABLE access_requests
    MODIFY COLUMN status VARCHAR(32) NOT NULL;

-- Sanity check: confirm none of these are ENUM anymore.
SELECT table_name, column_name, column_type
FROM information_schema.columns
WHERE table_schema = 'eac_directory'
  AND ((table_name = 'audit_events' AND column_name IN ('action', 'target_type'))
    OR (table_name = 'notifications' AND column_name = 'type')
    OR (table_name = 'files' AND column_name = 'status')
    OR (table_name = 'app_users' AND column_name = 'role')
    OR (table_name = 'access_requests' AND column_name = 'status'));
