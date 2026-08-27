package ph.edu.eac.filedirectory.file;

import jakarta.persistence.*;
import ph.edu.eac.filedirectory.access.AccessDecisionPolicy;
import ph.edu.eac.filedirectory.taxonomy.Category;
import ph.edu.eac.filedirectory.taxonomy.Course;
import ph.edu.eac.filedirectory.taxonomy.Department;
import ph.edu.eac.filedirectory.taxonomy.Program;
import ph.edu.eac.filedirectory.user.AppUser;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * An uploaded academic file (research paper, thesis, book, code, slides, etc.)
 * and its metadata. The actual bytes live on local disk under
 * {@code eac.storage.root}; this row stores the path, not the content.
 * Named FileEntity (not File) to avoid clashing with java.io.File.
 *
 * Indexed on (status, created_at) since that's the base shape of nearly
 * every Browse/queue query (see FileSpecifications, AdminController) -
 * status=APPROVED/PENDING plus a createdAt-based sort or year filter. The
 * foreign-key columns (department_id, category_id, program_id, course_id,
 * uploader_id) aren't indexed explicitly here because MySQL/MariaDB (the
 * production database - see application.properties) auto-indexes FK
 * columns; H2 (used in tests) does too. A standalone status index is kept
 * as well for queries that filter by status with a different sort/no sort.
 */
@Entity
@Table(name = "files", indexes = {
        @Index(name = "idx_files_status", columnList = "status"),
        @Index(name = "idx_files_status_created_at", columnList = "status, created_at")
})
public class FileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 2000)
    private String description;

    @Column(length = 1000)
    private String authors;

    @Column(length = 255)
    private String adviser;

    private Integer academicYear;

    @ManyToOne(optional = false)
    @JoinColumn(name = "uploader_id", nullable = false)
    private AppUser uploader;

    @ManyToOne(optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @ManyToOne
    @JoinColumn(name = "program_id")
    private Program program;

    @ManyToOne
    @JoinColumn(name = "course_id")
    private Course course;

    @ManyToOne(optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    // length=32 so this maps as VARCHAR, not a native MySQL ENUM whose
    // allowed-value list gets locked in at table-creation time and never
    // widens on ddl-auto=update when a new FileStatus constant is added
    // later - see AuditEvent.action's comment for the exact failure this
    // caused for a different enum column.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FileStatus status = FileStatus.PENDING;

    /** Path relative to eac.storage.root, e.g. "ENGR/thesis/2026/uuid_report.pdf". */
    @Column(nullable = false, length = 500)
    private String filePath;

    /** Original filename as uploaded, shown to users (filePath uses a generated name on disk). */
    @Column(nullable = false, length = 255)
    private String originalFilename;

    @Column(nullable = false)
    private long fileSize;

    @Column(nullable = false, length = 150)
    private String mimeType;

    @Column(length = 128)
    private String checksum;

    @Column(nullable = false)
    private long downloadCount = 0;

    private Integer versionNumber = 1;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @ManyToOne
    @JoinColumn(name = "approved_by")
    private AppUser approvedBy;

    private Instant approvedAt;

    /** Reason given when status is REJECTED; null otherwise. */
    @Column(length = 500)
    private String rejectionReason;

    @ManyToOne
    @JoinColumn(name = "archived_by")
    private AppUser archivedBy;

    private Instant archivedAt;

    @Column(length = 500)
    private String archiveReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private FileStatus statusBeforeArchive;

    @ManyToMany
    @JoinTable(
            name = "file_tags",
            joinColumns = @JoinColumn(name = "file_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<Tag> tags = new HashSet<>();

    /**
     * How an access request for THIS file should be decided - set by the
     * uploader at upload time (see UploadController, upload.html) and
     * checked by AccessRequestService.request(). MANUAL is the default:
     * today's Phase 9 behavior, unchanged. Deliberately per-file rather than
     * a single account-wide default - an uploader may want, e.g., a public
     * dataset auto-approved but a thesis draft reviewed by hand.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccessDecisionPolicy accessDecisionPolicy = AccessDecisionPolicy.MANUAL;

    protected FileEntity() {
        // JPA
    }

    public FileEntity(String title, String description, AppUser uploader, Department department,
                       Program program, Course course, Category category, String filePath,
                       String originalFilename, long fileSize, String mimeType, String checksum) {
        this.title = title;
        this.description = description;
        this.uploader = uploader;
        this.department = department;
        this.program = program;
        this.course = course;
        this.category = category;
        this.filePath = filePath;
        this.originalFilename = originalFilename;
        this.fileSize = fileSize;
        this.mimeType = mimeType;
        this.checksum = checksum;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAuthors() {
        return authors;
    }

    public void setAuthors(String authors) {
        this.authors = authors;
    }

    public String getAdviser() {
        return adviser;
    }

    public void setAdviser(String adviser) {
        this.adviser = adviser;
    }

    public Integer getAcademicYear() {
        return academicYear;
    }

    public void setAcademicYear(Integer academicYear) {
        this.academicYear = academicYear;
    }

    public AppUser getUploader() {
        return uploader;
    }

    public Department getDepartment() {
        return department;
    }

    public void setDepartment(Department department) {
        this.department = department;
    }

    public Program getProgram() {
        return program;
    }

    public void setProgram(Program program) {
        this.program = program;
    }

    public Course getCourse() {
        return course;
    }

    public void setCourse(Course course) {
        this.course = course;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public FileStatus getStatus() {
        return status;
    }

    public void setStatus(FileStatus status) {
        this.status = status;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    /**
     * Whether this file can currently be rendered in the in-browser preview
     * (see FileDetailController's preview-content endpoint / PreviewPane in
     * file-detail.html). Checked by extension rather than trusting the
     * client-declared mimeType alone - FileTypeValidator already verified
     * the extension matches the actual file signature at upload time (see
     * its class comment), so ".pdf" here means "confirmed PDF bytes", not
     * just "the browser said so". DOCX/PPTX preview support (via server-side
     * conversion) is a later phase - this intentionally returns false for
     * everything except PDF today rather than pretending to support it.
     */
    public boolean isPreviewable() {
        return originalFilename != null && originalFilename.toLowerCase().endsWith(".pdf");
    }

    /** File extension (no dot, lowercase), or "" if there isn't one - shared by isPreviewable-style checks and OnlyOfficeService's type mapping so both read the same thing off the same field. */
    public String extension() {
        if (originalFilename == null) {
            return "";
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return "";
        }
        return originalFilename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Whether this file can be previewed through ONLYOFFICE (see
     * ph.edu.eac.filedirectory.onlyoffice) - the Office formats PDF.js can't
     * render natively. Deliberately excludes .pdf: isPreviewable() above
     * already covers that through the existing PDF.js viewer, and there's
     * no reason to route a format that already has a working in-house
     * preview through a separate external service instead.
     */
    /**
     * Whether this file can be previewed as plain text - just ".txt" today.
     * Deliberately its own check rather than folded into isPreviewable()/
     * isOfficePreviewable(): this doesn't go through PDF.js or ONLYOFFICE
     * at all, just a raw read-and-display (see FileDetailController's
     * text-content endpoint) - the simplest of the three preview paths,
     * for the simplest format.
     */
    public boolean isTextPreviewable() {
        return "txt".equals(extension());
    }

    public boolean isOfficePreviewable() {
        return switch (extension()) {
            case "doc", "docx", "ppt", "pptx", "xls", "xlsx" -> true;
            default -> false;
        };
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public long getDownloadCount() {
        return downloadCount;
    }

    public int getVersionNumber() {
        return versionNumber == null || versionNumber < 1 ? 1 : versionNumber;
    }

    public void setVersionNumber(int versionNumber) {
        this.versionNumber = Math.max(1, versionNumber);
    }

    public void incrementDownloadCount() {
        this.downloadCount++;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public AppUser getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(AppUser approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public AppUser getArchivedBy() {
        return archivedBy;
    }

    public void setArchivedBy(AppUser archivedBy) {
        this.archivedBy = archivedBy;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    public String getArchiveReason() {
        return archiveReason;
    }

    public void setArchiveReason(String archiveReason) {
        this.archiveReason = archiveReason;
    }

    public FileStatus getStatusBeforeArchive() {
        return statusBeforeArchive;
    }

    public void setStatusBeforeArchive(FileStatus statusBeforeArchive) {
        this.statusBeforeArchive = statusBeforeArchive;
    }

    public Set<Tag> getTags() {
        return tags;
    }

    public void setTags(Set<Tag> tags) {
        this.tags = tags;
    }

    public AccessDecisionPolicy getAccessDecisionPolicy() {
        return accessDecisionPolicy;
    }

    public void setAccessDecisionPolicy(AccessDecisionPolicy accessDecisionPolicy) {
        this.accessDecisionPolicy = accessDecisionPolicy;
    }
}
