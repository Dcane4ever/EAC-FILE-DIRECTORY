package ph.edu.eac.filedirectory.user;

/**
 * Application-level roles. Every @eac.edu.ph account gets USER on first
 * login; MODERATOR/ADMIN is granted manually by an existing admin.
 */
public enum Role {
    USER,
    MODERATOR,
    ADMIN
}
