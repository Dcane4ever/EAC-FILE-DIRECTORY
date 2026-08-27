package ph.edu.eac.filedirectory.file;

import jakarta.persistence.*;
import ph.edu.eac.filedirectory.user.AppUser;

import java.time.Instant;

@Entity
@Table(name = "file_versions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_file_versions_file_number", columnNames = {"file_id", "version_number"})
})
public class FileVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private FileEntity file;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(nullable = false, length = 500)
    private String filePath;

    @Column(nullable = false, length = 255)
    private String originalFilename;

    @Column(nullable = false)
    private long fileSize;

    @Column(nullable = false, length = 150)
    private String mimeType;

    @Column(length = 128)
    private String checksum;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private FileStatus status = FileStatus.APPROVED;

    @ManyToOne(optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private AppUser uploadedBy;

    @ManyToOne
    @JoinColumn(name = "approved_by")
    private AppUser approvedBy;

    private Instant approvedAt;

    @Column(length = 500)
    private String rejectionReason;

    @Column(length = 500)
    private String note;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected FileVersion() {
        // JPA
    }

    public FileVersion(FileEntity file, int versionNumber, String filePath, String originalFilename,
                       long fileSize, String mimeType, String checksum, AppUser uploadedBy, String note) {
        this.file = file;
        this.versionNumber = versionNumber;
        this.filePath = filePath;
        this.originalFilename = originalFilename;
        this.fileSize = fileSize;
        this.mimeType = mimeType;
        this.checksum = checksum;
        this.uploadedBy = uploadedBy;
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public FileEntity getFile() {
        return file;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

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

    public boolean isPreviewable() {
        return "pdf".equals(extension());
    }

    public boolean isTextPreviewable() {
        return "txt".equals(extension());
    }

    public boolean isOfficePreviewable() {
        return switch (extension()) {
            case "doc", "docx", "ppt", "pptx", "xls", "xlsx" -> true;
            default -> false;
        };
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

    public AppUser getUploadedBy() {
        return uploadedBy;
    }

    public FileStatus getStatus() {
        return status == null ? FileStatus.APPROVED : status;
    }

    public void setStatus(FileStatus status) {
        this.status = status;
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

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
