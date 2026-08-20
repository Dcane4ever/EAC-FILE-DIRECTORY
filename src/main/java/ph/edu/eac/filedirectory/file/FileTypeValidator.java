package ph.edu.eac.filedirectory.file;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Whitelists what an upload is allowed to be, checked in three independent
 * ways so a mismatch in any one of them is caught:
 *  1. File extension (from the original filename)
 *  2. Declared Content-Type (from the multipart request - trivially spoofed
 *     by the client, so never trusted alone)
 *  3. Magic bytes actually read from the file content, where the format has
 *     a reliable signature to check
 *
 * DOC/PPT/XLS (legacy binary Office, OLE2 compound-file format) and TXT/CSV
 * (plain text) don't have a signature specific enough to usefully check, so
 * those rely on extension + declared MIME type only - still real
 * verification, just not a third independent layer.
 *
 * Rejects on the first mismatch rather than trying to be lenient - an
 * upload whose extension says .pdf but whose bytes don't start with the PDF
 * signature is exactly the kind of thing this exists to catch (a renamed
 * executable, for instance).
 */
@Service
public class FileTypeValidator {

    public record AllowedType(Set<String> extensions, Set<String> mimeTypes) {
    }

    private static final Map<String, AllowedType> ALLOWED_BY_LABEL = Map.ofEntries(
            Map.entry("pdf", new AllowedType(Set.of("pdf"), Set.of("application/pdf"))),
            Map.entry("doc", new AllowedType(Set.of("doc"), Set.of("application/msword"))),
            Map.entry("docx", new AllowedType(Set.of("docx"),
                    Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))),
            Map.entry("ppt", new AllowedType(Set.of("ppt"), Set.of("application/vnd.ms-powerpoint"))),
            Map.entry("pptx", new AllowedType(Set.of("pptx"),
                    Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation"))),
            Map.entry("xls", new AllowedType(Set.of("xls"), Set.of("application/vnd.ms-excel"))),
            Map.entry("xlsx", new AllowedType(Set.of("xlsx"),
                    Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))),
            Map.entry("csv", new AllowedType(Set.of("csv"), Set.of("text/csv", "application/vnd.ms-excel"))),
            Map.entry("txt", new AllowedType(Set.of("txt"), Set.of("text/plain"))),
            Map.entry("zip", new AllowedType(Set.of("zip"),
                    Set.of("application/zip", "application/x-zip-compressed"))),
            Map.entry("jpg", new AllowedType(Set.of("jpg", "jpeg"), Set.of("image/jpeg"))),
            Map.entry("png", new AllowedType(Set.of("png"), Set.of("image/png")))
    );

    /** Every extension accepted, for building the <input accept="..."> hint and quick lookup. */
    public static final Set<String> ALLOWED_EXTENSIONS = ALLOWED_BY_LABEL.values().stream()
            .flatMap(t -> t.extensions().stream())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    public record ValidationResult(boolean valid, String reason) {
        static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        static ValidationResult rejected(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    public ValidationResult validate(MultipartFile file) {
        String originalName = file.getOriginalFilename();
        String extension = extensionOf(originalName);
        if (extension.isEmpty()) {
            return ValidationResult.rejected("File has no extension - cannot determine its type.");
        }

        AllowedType allowed = ALLOWED_BY_LABEL.values().stream()
                .filter(t -> t.extensions().contains(extension))
                .findFirst()
                .orElse(null);
        if (allowed == null) {
            return ValidationResult.rejected("." + extension + " files are not allowed. Accepted types: "
                    + String.join(", ", ALLOWED_EXTENSIONS.stream().sorted().toList()) + ".");
        }

        String declaredType = file.getContentType();
        if (declaredType != null && !allowed.mimeTypes().contains(declaredType.toLowerCase(Locale.ROOT))) {
            return ValidationResult.rejected(
                    "The uploaded file's declared type (" + declaredType + ") doesn't match its ." + extension + " extension.");
        }

        String signatureError = checkMagicBytes(file, extension);
        if (signatureError != null) {
            return ValidationResult.rejected(signatureError);
        }

        return ValidationResult.ok();
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Reads just enough of the file's actual content to check a magic-byte
     * signature, for formats where one reliably exists. Returns a rejection
     * reason, or null if the check passed (or doesn't apply to this type).
     */
    private String checkMagicBytes(MultipartFile file, String extension) {
        byte[] header;
        try (InputStream in = file.getInputStream()) {
            header = in.readNBytes(8);
        } catch (IOException e) {
            return "Could not read the uploaded file to verify its type.";
        }

        switch (extension) {
            case "pdf" -> {
                if (!startsWith(header, "%PDF-".getBytes())) {
                    return "This file doesn't look like a real PDF (missing %PDF- signature).";
                }
            }
            // DOCX/PPTX/XLSX/ZIP all share the ZIP container signature
            // (PK\x03\x04) - OOXML Office formats are ZIP archives
            // internally, so this is the correct check for all four, not a
            // simplification.
            case "docx", "pptx", "xlsx", "zip" -> {
                if (!startsWith(header, new byte[]{0x50, 0x4B, 0x03, 0x04})
                        && !startsWith(header, new byte[]{0x50, 0x4B, 0x05, 0x06}) // empty archive
                        && !startsWith(header, new byte[]{0x50, 0x4B, 0x07, 0x08})) {
                    return "This file doesn't look like a valid ." + extension + " (not a ZIP container).";
                }
            }
            case "jpg", "jpeg" -> {
                if (!startsWith(header, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
                    return "This file doesn't look like a real JPEG image.";
                }
            }
            case "png" -> {
                if (!startsWith(header, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) {
                    return "This file doesn't look like a real PNG image.";
                }
            }
            // doc/ppt/xls (legacy OLE2 binary Office) all share one
            // compound-file signature, so it can't distinguish among them -
            // not a useful independent check beyond "is this some kind of
            // OLE2 file", so it's intentionally skipped; extension + MIME
            // type checks above still apply.
            default -> {
                return null;
            }
        }
        return null;
    }

    private boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
