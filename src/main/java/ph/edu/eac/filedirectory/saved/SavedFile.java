package ph.edu.eac.filedirectory.saved;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.user.AppUser;

import java.time.LocalDateTime;

@Entity
@Table(name = "saved_files",
        uniqueConstraints = @UniqueConstraint(name = "uk_saved_files_user_file", columnNames = {"user_id", "file_id"}))
public class SavedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private FileEntity file;

    private LocalDateTime createdAt = LocalDateTime.now();

    protected SavedFile() {
    }

    public SavedFile(AppUser user, FileEntity file) {
        this.user = user;
        this.file = file;
    }

    public Long getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public FileEntity getFile() {
        return file;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
