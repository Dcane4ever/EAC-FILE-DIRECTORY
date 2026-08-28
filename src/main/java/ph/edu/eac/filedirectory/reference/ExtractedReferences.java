package ph.edu.eac.filedirectory.reference;

import java.util.List;

public record ExtractedReferences(Status status, String message, List<String> entries) {

    public enum Status {
        FOUND,
        NOT_FOUND,
        UNSUPPORTED,
        UNAVAILABLE
    }

    public boolean hasEntries() {
        return entries != null && !entries.isEmpty();
    }
}
