package ph.edu.eac.filedirectory.reference;

import java.util.List;

public record DetectedAuthors(Status status, String message, List<String> names) {

    public enum Status {
        FOUND,
        NOT_FOUND,
        UNSUPPORTED,
        UNAVAILABLE
    }

    public boolean hasNames() {
        return names != null && !names.isEmpty();
    }

    public String joinedNames() {
        return hasNames() ? String.join(", ", names) : "";
    }
}
