package ph.edu.eac.filedirectory.reference;

import org.springframework.stereotype.Service;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileStorageService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class AuthorDetectionService {

    private static final long TEXT_MAX_BYTES = 500_000;
    private static final int MAX_SCAN_LINES = 90;
    private static final int MAX_AUTHORS = 12;
    private static final Pattern AUTHOR_CUE = Pattern.compile(
            "(?i).*\\b(a\\s+capstone\\s+project\\s+by|submitted\\s+by|prepared\\s+by|researchers?|proponents?|by)\\b.*");
    private static final Pattern STOP_CUE = Pattern.compile(
            "(?i).*\\b(submitted\\s+to|school\\s+of|college|university|in\\s+partial\\s+fulfillment|adviser|advisor|panel|chapter|abstract|introduction)\\b.*");
    private static final Pattern NAME_LINE = Pattern.compile(
            "^[A-Z][A-Za-z'.-]+(?:,\\s*|\\s+)[A-Z][A-Za-z'.-]+(?:\\s+[A-Z][A-Za-z'.-]+){0,4}\\.?$");

    private final FileStorageService storageService;

    public AuthorDetectionService(FileStorageService storageService) {
        this.storageService = storageService;
    }

    public DetectedAuthors detect(FileEntity file) {
        String extension = file.extension();
        if (!"txt".equals(extension) && !"docx".equals(extension)) {
            return new DetectedAuthors(DetectedAuthors.Status.UNSUPPORTED,
                    "Author detection currently supports TXT and DOCX files.", List.of());
        }

        Path path = storageService.resolve(file.getFilePath());
        if (!Files.isReadable(path)) {
            return new DetectedAuthors(DetectedAuthors.Status.UNAVAILABLE,
                    "The uploaded file is not available on disk.", List.of());
        }

        try {
            String text = "docx".equals(extension) ? readDocxText(path) : readText(path);
            List<String> names = detectNames(text);
            if (names.isEmpty()) {
                return new DetectedAuthors(DetectedAuthors.Status.NOT_FOUND,
                        "No likely author block was detected in the document title area.", List.of());
            }
            return new DetectedAuthors(DetectedAuthors.Status.FOUND,
                    "Detected from the document title area. Review before saving.", names);
        } catch (IOException e) {
            return new DetectedAuthors(DetectedAuthors.Status.UNAVAILABLE,
                    "The author block could not be read from this file.", List.of());
        }
    }

    private String readText(Path path) throws IOException {
        long size = Files.size(path);
        try (var in = Files.newInputStream(path)) {
            return new String(in.readNBytes((int) Math.min(size, TEXT_MAX_BYTES)), StandardCharsets.UTF_8);
        }
    }

    private String readDocxText(Path path) throws IOException {
        StringBuilder text = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    text.append(xmlToText(xml));
                    break;
                }
            }
        }
        return text.toString();
    }

    private String xmlToText(String xml) {
        return xml
                .replaceAll("</w:p>", "\n")
                .replaceAll("</w:tab>", "\t")
                .replaceAll("<[^>]+>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }

    private List<String> detectNames(String text) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\\n");
        int limit = Math.min(lines.length, MAX_SCAN_LINES);
        for (int i = 0; i < limit; i++) {
            if (!AUTHOR_CUE.matcher(clean(lines[i])).matches()) {
                continue;
            }
            List<String> names = collectNamesAfterCue(lines, i + 1, limit);
            if (!names.isEmpty()) {
                return names;
            }
        }
        return List.of();
    }

    private List<String> collectNamesAfterCue(String[] lines, int start, int limit) {
        List<String> names = new ArrayList<>();
        int blanksAfterNames = 0;
        for (int i = start; i < limit && names.size() < MAX_AUTHORS; i++) {
            String line = clean(lines[i]);
            if (line.isBlank()) {
                if (!names.isEmpty() && ++blanksAfterNames >= 2) {
                    break;
                }
                continue;
            }
            if (!names.isEmpty() && STOP_CUE.matcher(line).matches()) {
                break;
            }
            if (NAME_LINE.matcher(line).matches() && !STOP_CUE.matcher(line).matches()) {
                names.add(line);
                blanksAfterNames = 0;
            } else if (!names.isEmpty()) {
                break;
            }
        }
        return names;
    }

    private String clean(String line) {
        return line.replaceAll("\\s+", " ").strip();
    }
}
