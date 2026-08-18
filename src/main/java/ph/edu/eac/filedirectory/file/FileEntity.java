package ph.edu.eac.filedirectory.file;

import jakarta.persistence.*;
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
 */
@Entity
@Table(name = "files")
public class FileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 2000)
    private String description;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
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

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @ManyToOne
    @JoinColumn(name = "approved_by")
    private AppUser approvedBy;

    private Instant approvedAt;

    /** Reason given when status is REJECTED; null otherwise. */
    @Column(length = 500)
    private String rejectionReason;

    @ManyToMany
    @JoinTable(
            name = "file_tags",
            joinColumns = @JoinColumn(name = "file_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<Tag> tags = new HashSet<>();

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

    public String getOriginalFilename() {
        return originalFilename;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getChecksum() {
        return checksum;
    }

    public long getDownloadCount() {
        return downloadCount;
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

    public Set<Tag> getTags() {
        return tags;
    }

    public void setTags(Set<Tag> tags) {
        this.tags = tags;
    }
}
