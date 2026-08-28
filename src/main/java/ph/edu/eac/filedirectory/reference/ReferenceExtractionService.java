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
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ReferenceExtractionService {

    private static final long TEXT_MAX_BYTES = 2_000_000;
    private static final int MAX_ENTRIES = 40;
    private static final Pattern SECTION_HEADING = Pattern.compile(
            "(?im)^\\s*(?:chapter\\s+\\d+\\s*[:.-]?\\s*)?(?:\\d+(?:\\.\\d+)*\\s*[:.)-]?\\s*)?(references|bibliography|works\\s+cited|literature\\s+cited)\\s*$");
    private static final Pattern NEXT_SECTION_HEADING = Pattern.compile(
            "(?im)^\\s*(appendix|appendices|acknowledg(e)?ments?|annex|curriculum vitae|vita)\\b.*$");
    private static final Pattern REFERENCE_ENTRY_START = Pattern.compile(
            "^(\\[\\d+]|\\d+\\.|[A-Z][A-Za-z'`-]+,\\s+.+|[A-Z][A-Za-z'`& .-]+\\.\\s+\\(\\d{4}\\).+).*");
    private static final Pattern REFERENCE_ENTRY_SIGNAL = Pattern.compile(
            "(?i).*(\\(\\d{4}\\)|\\bdoi\\b|https?://|\\bvol\\.?\\b|\\bjournal\\b|\\bproceedings\\b|\\bet\\s+al\\.).*");

    private final FileStorageService storageService;

    public ReferenceExtractionService(FileStorageService storageService) {
        this.storageService = storageService;
    }

    public ExtractedReferences extract(FileEntity file) {
        String extension = file.extension();
        if (!"txt".equals(extension) && !"docx".equals(extension)) {
            return new ExtractedReferences(ExtractedReferences.Status.UNSUPPORTED,
                    "Reference extraction currently supports TXT and DOCX files.", List.of());
        }

        Path path = storageService.resolve(file.getFilePath());
        if (!Files.isReadable(path)) {
            return new ExtractedReferences(ExtractedReferences.Status.UNAVAILABLE,
                    "The uploaded file is not available on disk.", List.of());
        }

        try {
            String text = "docx".equals(extension) ? readDocxText(path) : readText(path);
            List<String> entries = extractEntries(text);
            if (entries.isEmpty()) {
                return new ExtractedReferences(ExtractedReferences.Status.NOT_FOUND,
                        "No References, Bibliography, Works Cited, or Literature Cited section was detected.", List.of());
            }
            return new ExtractedReferences(ExtractedReferences.Status.FOUND,
                    "Extracted from the document's reference section.", entries);
        } catch (IOException e) {
            return new ExtractedReferences(ExtractedReferences.Status.UNAVAILABLE,
                    "The reference section could not be read from this file.", List.of());
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

    private List<String> extractEntries(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        var headingMatcher = SECTION_HEADING.matcher(normalized);
        if (!headingMatcher.find()) {
            return extractLikelyReferencesFromTail(normalized);
        }

        String section = normalized.substring(headingMatcher.end()).strip();
        var nextSectionMatcher = NEXT_SECTION_HEADING.matcher(section);
        if (nextSectionMatcher.find()) {
            section = section.substring(0, nextSectionMatcher.start()).strip();
        }

        return splitEntries(section);
    }

    private List<String> splitEntries(String section) {
        List<String> entries = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : section.split("\\n")) {
            String cleaned = line.strip();
            if (cleaned.isEmpty()) {
                addEntry(entries, current);
                current.setLength(0);
                continue;
            }
            boolean looksLikeNewEntry = REFERENCE_ENTRY_START.matcher(cleaned).matches();
            if (looksLikeNewEntry && current.length() > 0) {
                addEntry(entries, current);
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(cleaned);
        }
        addEntry(entries, current);
        return entries.stream()
                .filter(entry -> entry.length() >= 12)
                .filter(entry -> !entry.toLowerCase(Locale.ROOT).startsWith("references"))
                .limit(MAX_ENTRIES)
                .toList();
    }

    private List<String> extractLikelyReferencesFromTail(String text) {
        String[] lines = text.split("\\n");
        int start = Math.max(0, lines.length - 160);
        StringBuilder tail = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            tail.append(lines[i]).append('\n');
        }
        return splitEntries(tail.toString()).stream()
                .filter(entry -> REFERENCE_ENTRY_SIGNAL.matcher(entry).matches())
                .limit(MAX_ENTRIES)
                .toList();
    }

    private void addEntry(List<String> entries, StringBuilder current) {
        String entry = current.toString().replaceAll("\\s+", " ").strip();
        if (!entry.isEmpty()) {
            entries.add(entry);
        }
    }
}
