package ph.edu.eac.filedirectory.onlyoffice;

import java.util.Locale;
import java.util.Set;

/**
 * ONLYOFFICE's three document editor families - the config's top-level
 * "documentType" field. Every extension this app lets ONLYOFFICE preview
 * (see FileEntity.isOfficePreviewable) maps to exactly one of these; nothing
 * else is accepted; see OnlyOfficeService.determineDocumentType.
 */
public enum OnlyOfficeDocumentType {
    WORD("word", Set.of("doc", "docx")),
    CELL("cell", Set.of("xls", "xlsx")),
    SLIDE("slide", Set.of("ppt", "pptx"));

    private final String apiValue;
    private final Set<String> extensions;

    OnlyOfficeDocumentType(String apiValue, Set<String> extensions) {
        this.apiValue = apiValue;
        this.extensions = extensions;
    }

    /** The literal string ONLYOFFICE's JS API expects for config.documentType. */
    public String apiValue() {
        return apiValue;
    }

    /** Returns the matching type for a file extension (case-insensitive, no leading dot), or null if unsupported. */
    public static OnlyOfficeDocumentType forExtension(String extension) {
        if (extension == null) {
            return null;
        }
        String normalized = extension.toLowerCase(Locale.ROOT);
        for (OnlyOfficeDocumentType type : values()) {
            if (type.extensions.contains(normalized)) {
                return type;
            }
        }
        return null;
    }
}
