# EAC File Directory Checklist

Updated: September 2, 2026

## Done

- [x] Spring Boot WAR project setup
  - Java/Spring Boot application is structured for Tomcat WAR deployment.
  - App name/build output was configured for `eacmnl#filedirectory.war`.

- [x] EAC branding and Thymeleaf/Tailwind UI foundation
  - Real EAC logo/assets and EAC red/maroon visual system are used.
  - Login, landing, user pages, and admin pages were redesigned around the current EAC look.

- [x] Authentication and verified EAC-only access
  - Manual registration/login is restricted to `@eac.edu.ph` users.
  - Email verification, password reset, logout flow, and role-based redirects are in place.

- [x] Core taxonomy
  - Departments, programs, courses, categories, and tags are modeled.
  - Registrar-derived department/program/course seed structure is used.

- [x] Upload workflow
  - Users can upload files with academic metadata.
  - Uploads enter moderation unless department auto-approval is enabled.
  - Duplicate detection, file type validation, checksums, and local disk storage are implemented.

- [x] Browse and search
  - Users can browse approved files.
  - Filters support department, program, course, category, tags, file type, uploader, year, and text search.

- [x] File detail and preview
  - File detail page shows metadata, uploader, tags, stats, and actions.
  - PDF, TXT, and OnlyOffice-supported Office previews are available where supported.
  - Inline OnlyOffice preview remains embedded in the EAC preview card.

- [x] Download/request-copy permissions
  - Uploaders and staff can directly download.
  - Other users request a copy instead of direct download.
  - Version-specific request access is supported.

- [x] Admin dashboard and moderation
  - Admin dashboard was redesigned.
  - Approval queue, manage files, archived files, manage users, audit log, settings, reports, and taxonomy pages use the improved admin visual language.
  - Admin can view/manage all files, not only pending approvals.

- [x] Metadata editing
  - Uploaders/staff can edit title, description, department, program, course, category, tags, authors, adviser, and academic year.
  - Return navigation was fixed for admin queue/manage-files contexts.

- [x] Archive/unpublish files
  - Staff can archive files with a reason and restore them later.
  - Archived files are hidden from regular browsing.

- [x] Better request inbox
  - My Requests and uploader access request views were improved.
  - Request status, version labels, and decision history are clearer.

- [x] Saved files/bookmarks
  - Users can save and unsave files.
  - Saved Files is available under My Workspace.

- [x] User profiles/uploader pages
  - Uploader profile pages show profile info, upload stats, contribution mix, and approved work.
  - Admin/staff browsing gaps were addressed.

- [x] File versioning
  - Uploaders can upload replacement versions.
  - Current approved version remains live while a new version waits for staff approval.
  - Users can preview eligible approved versions inline; higher version-history access remains controlled.

- [x] Similar files
  - File detail page recommends related approved files by tags, course, program, category, and department.

- [x] Follow departments/tags/uploaders
  - Users can follow departments, tags, and uploaders.
  - New approved matching files generate notifications.
  - Following page lists followed items.
  - Departments discovery page lets users browse and follow departments.

- [x] User navigation cleanup
  - Top navigation now prioritizes Browse, Departments, Upload, and My Workspace.
  - Saved Files, Following, My Uploads, and My Requests are grouped under My Workspace.
  - Notifications, profile, and Sign Out remain on the right.

- [x] Citation export
  - File detail page now generates APA, MLA, and EAC Basic citations.
  - Citations can be copied from the Citations section.
  - Citations are generated from current metadata and are not stored separately.

- [x] Extract references from uploaded papers
  - File detail page now reads supported uploaded files and detects sections like References, Bibliography, Works Cited, or Literature Cited.
  - Extracted reference entries are shown separately from Citation Export.
  - Phase 1 supports TXT and DOCX without changing the original uploaded files or adding preview infrastructure.

- [x] Detect authors from uploaded papers
  - Edit Metadata now suggests likely authors from supported TXT/DOCX title pages.
  - Uploaders/admins can review and apply detected authors manually.
  - Citation Export continues to use saved metadata only.

## Next

- [x] Production SMTP account
  - Uses the Aguinaldo College email service for verification and password-reset emails.
  - Sender credentials are treated as production environment configuration.

- [x] Local WAR build verification
  - Built the project locally as `target/eacmnl#filedirectory.war`.
  - Verified local test reports: 157 tests, 0 failures, 0 errors, 0 skipped.

- [ ] Server deployment dry run
  - Deploy `eacmnl#filedirectory.war` to the target Tomcat server.
  - Test the real server URL, login, upload, preview, download/request-copy, admin queue, and logout.

- [ ] Docker/OnlyOffice production check
  - Confirm Docker Desktop or Docker Engine is installed correctly on the server.
  - Confirm OnlyOffice container starts automatically and is reachable by both browser and Spring Boot.
  - Confirm JWT secret/base URL settings match production.

- [ ] Storage maintenance tools
  - [x] Phase 1: Admin-only, read-only maintenance report detects missing stored files, orphaned disk files, duplicate checksums, and physical storage usage.
  - [x] Phase 1: CSV exports are available for every report category.
  - [x] Phase 2: Admin-only archive action for missing current-file records, with a required reason and audit event.
  - [x] Phase 2: Archived records cannot be restored while their stored file remains missing.
  - [ ] Phase 2: Orphaned-disk cleanup remains disabled until the retention policy and backups are approved.

- [ ] Backup and restore procedure
  - Document MySQL backup.
  - Document uploaded-files backup.
  - Document restore steps and test them once.

- [ ] Production security hardening
  - Confirm HTTPS.
  - Confirm secure cookies.
  - Confirm upload type restrictions.
  - Add or verify rate limits.
  - Review admin audit logs.
  - Review access-token expiry behavior.

## Storage and Recovery Implementation Plan

### Phase 1: Read-only storage maintenance report (Complete)

- [x] Define an admin-only maintenance route and navigation entry.
- [x] Scan every database file record and report records whose stored disk file is missing.
- [x] Scan the storage root and report disk files not referenced by a database record.
- [x] Report duplicate SHA-256 checksums, including the linked file records and versions.
- [x] Calculate total storage usage, file counts, and storage use by department.
- [x] Show summary counts and detailed results in an EAC-styled admin view.
- [x] Add CSV export for each report category.
- [x] Add tests covering normal files, missing files, orphaned files, duplicate checksums, and path-safety checks.
- [x] Keep Phase 1 report-only: no automatic deletion, restoration, or database modification.

### Phase 2: Controlled maintenance actions

- [ ] Decide and document the retention policy for archived files and orphaned disk files.
- [x] Add an explicit, admin-confirmed action to archive current records with missing disk files.
- [ ] Add a reviewed cleanup workflow for confirmed orphaned disk files.
- [x] Require a reason and create an audit-log event for every implemented maintenance action.
- [x] Block restoring an archived record until its current stored file exists again.
- [ ] Add a downloadable maintenance activity report.

### Phase 3: Backup and restore procedure

- [ ] Choose a backup location separate from the Tomcat server disk when possible.
- [ ] Create a MySQL backup command for the `eac_directory` database.
- [ ] Create an uploaded-files backup command for `C:\\eac-filedirectory\\data`.
- [ ] Create a dated backup folder structure that stores the database dump and uploaded files together.
- [ ] Define retention: daily, weekly, and monthly backup copies.
- [ ] Write `BACKUP-RESTORE.md` with prerequisites, backup commands, restore commands, and validation steps.
- [ ] Configure a scheduled Windows task to run the backup after the procedure is manually verified.
- [ ] Test a restore into a separate MySQL database and separate temporary storage directory.
- [ ] Verify restored login, metadata, version history, permissions, preview, and download behavior.
- [ ] Record the restore test date, result, operator, and any follow-up action.

## Later Improvements

- [x] In-document preview search
  - OnlyOffice already provides search inside supported Office documents during preview.
  - PDF/TXT preview search remains handled by the browser/viewer experience where available.

- [ ] Private EAC sharing
  - Allow a file to be shared internally with a specific verified `@eac.edu.ph` user.

- [ ] Comments or private notes
  - Allow notes or discussion around files.
  - Needs moderation rules before enabling broadly.

- [ ] Advanced recommendation feed
  - Personalized suggestions based on department, program, tags, saved files, and followed items.

- [ ] Multi-format citation expansion
  - Improve citation formatting accuracy.
  - Add more styles if needed, such as Chicago or BibTeX.
