package ph.edu.eac.filedirectory.file;

/** Moderation state of an uploaded file. Only APPROVED files are publicly browsable. */
public enum FileStatus {
    PENDING,
    APPROVED,
    REJECTED
}
