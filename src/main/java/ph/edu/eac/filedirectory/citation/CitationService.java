package ph.edu.eac.filedirectory.citation;

import org.springframework.stereotype.Service;
import ph.edu.eac.filedirectory.file.FileEntity;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class CitationService {

    private static final String DEFAULT_AUTHOR = "Unknown author";
    private static final String REPOSITORY_NAME = "EAC File Directory";

    public CitationSet citationsFor(FileEntity file, String fileUrl) {
        String authors = citationAuthors(file.getAuthors());
        String title = sentenceEnd(clean(file.getTitle(), "Untitled file"));
        String year = file.getAcademicYear() == null ? "n.d." : file.getAcademicYear().toString();
        String department = clean(file.getDepartment().getName(), "");
        String category = clean(file.getCategory().getName(), "File");
        String program = file.getProgram() == null ? "" : clean(file.getProgram().getName(), "");
        String course = file.getCourse() == null ? "" : clean(file.getCourse().getTitle(), "");

        String apa = authors + " (" + year + "). " + title + " " + REPOSITORY_NAME + ". " + fileUrl;
        String mla = mlaAuthors(file.getAuthors()) + ". \"" + clean(file.getTitle(), "Untitled file") + ".\" "
                + REPOSITORY_NAME + ", " + year + ", " + fileUrl + ".";
        String eac = authors + ". " + clean(file.getTitle(), "Untitled file") + ". "
                + joinNonBlank(department, program, course, category) + ". " + REPOSITORY_NAME + ", " + year + ". "
                + fileUrl;

        return new CitationSet(apa, mla, eac);
    }

    private String citationAuthors(String rawAuthors) {
        String cleaned = clean(rawAuthors, DEFAULT_AUTHOR);
        if (DEFAULT_AUTHOR.equals(cleaned)) {
            return cleaned;
        }
        return Arrays.stream(cleaned.split("\\s*(,|;| and )\\s*"))
                .filter(author -> !author.isBlank())
                .map(this::apaName)
                .collect(Collectors.joining(", "));
    }

    private String mlaAuthors(String rawAuthors) {
        String cleaned = clean(rawAuthors, DEFAULT_AUTHOR);
        if (DEFAULT_AUTHOR.equals(cleaned)) {
            return cleaned;
        }
        String[] authors = Arrays.stream(cleaned.split("\\s*(,|;| and )\\s*"))
                .filter(author -> !author.isBlank())
                .toArray(String[]::new);
        if (authors.length == 0) {
            return DEFAULT_AUTHOR;
        }
        return apaName(authors[0]) + (authors.length > 1 ? ", et al" : "");
    }

    private String apaName(String author) {
        String trimmed = author.trim().replaceAll("\\s+", " ");
        String[] parts = trimmed.split(" ");
        if (parts.length < 2) {
            return trimmed;
        }
        String familyName = parts[parts.length - 1];
        String initials = Arrays.stream(parts, 0, parts.length - 1)
                .map(part -> part.isBlank() ? "" : part.substring(0, 1).toUpperCase(Locale.ROOT) + ".")
                .collect(Collectors.joining(" "));
        return familyName + ", " + initials;
    }

    private String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private String sentenceEnd(String value) {
        if (value.endsWith(".") || value.endsWith("?") || value.endsWith("!")) {
            return value;
        }
        return value + ".";
    }

    private String joinNonBlank(String... values) {
        return Arrays.stream(values)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining(", "));
    }

    public record CitationSet(String apa, String mla, String eac) {
    }
}
