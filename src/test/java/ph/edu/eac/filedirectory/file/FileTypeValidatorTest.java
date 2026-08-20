package ph.edu.eac.filedirectory.file;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;

class FileTypeValidatorTest {

    private final FileTypeValidator validator = new FileTypeValidator();

    private static final byte[] PDF_SIGNATURE = "%PDF-1.7\n...rest of a real pdf...".getBytes();
    private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] NOT_A_REAL_FILE = "MZ this is actually an EXE header".getBytes();

    @Test
    void acceptsAGenuinePdf() {
        var file = new MockMultipartFile("file", "thesis.pdf", "application/pdf", PDF_SIGNATURE);
        assertThat(validator.validate(file).valid()).isTrue();
    }

    @Test
    void rejectsAFileWithNoExtension() {
        var file = new MockMultipartFile("file", "thesis", "application/pdf", PDF_SIGNATURE);
        var result = validator.validate(file);
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("no extension");
    }

    @Test
    void rejectsAFileTypeNotOnTheAllowlist() {
        var file = new MockMultipartFile("file", "virus.exe", "application/x-msdownload", NOT_A_REAL_FILE);
        var result = validator.validate(file);
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("not allowed");
    }

    @Test
    void rejectsAnExecutableRenamedWithAPdfExtension() {
        // The actual attack this exists to catch: extension says .pdf, but
        // the bytes are not a PDF at all.
        var file = new MockMultipartFile("file", "totally-a-thesis.pdf", "application/pdf", NOT_A_REAL_FILE);
        var result = validator.validate(file);
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("doesn't look like a real PDF");
    }

    @Test
    void rejectsWhenDeclaredMimeTypeDoesNotMatchExtension() {
        var file = new MockMultipartFile("file", "thesis.pdf", "image/png", PDF_SIGNATURE);
        var result = validator.validate(file);
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("declared type");
    }

    @Test
    void acceptsWhenDeclaredMimeTypeIsMissing() {
        // Some clients/browsers omit Content-Type - shouldn't be treated as
        // an automatic rejection, the extension + magic bytes still apply.
        var file = new MockMultipartFile("file", "thesis.pdf", null, PDF_SIGNATURE);
        assertThat(validator.validate(file).valid()).isTrue();
    }

    @Test
    void acceptsAGenuineDocx() {
        var file = new MockMultipartFile("file", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ZIP_SIGNATURE);
        assertThat(validator.validate(file).valid()).isTrue();
    }

    @Test
    void rejectsADocxThatIsNotActuallyAZipContainer() {
        var file = new MockMultipartFile("file", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", NOT_A_REAL_FILE);
        var result = validator.validate(file);
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("not a ZIP container");
    }

    @Test
    void acceptsAGenuineZip() {
        var file = new MockMultipartFile("file", "source-code.zip", "application/zip", ZIP_SIGNATURE);
        assertThat(validator.validate(file).valid()).isTrue();
    }

    @Test
    void acceptsAGenuineJpeg() {
        var file = new MockMultipartFile("file", "figure1.jpg", "image/jpeg", JPEG_SIGNATURE);
        assertThat(validator.validate(file).valid()).isTrue();
    }

    @Test
    void acceptsAGenuinePng() {
        var file = new MockMultipartFile("file", "diagram.png", "image/png", PNG_SIGNATURE);
        assertThat(validator.validate(file).valid()).isTrue();
    }

    @Test
    void acceptsPlainTextWithoutMagicByteChecking() {
        // .txt/.csv have no reliable signature - extension + MIME type is
        // the whole check for these, and that's intentional (see
        // FileTypeValidator's class comment).
        var file = new MockMultipartFile("file", "data.txt", "text/plain", "just plain text content".getBytes());
        assertThat(validator.validate(file).valid()).isTrue();
    }

    @Test
    void acceptsLegacyBinaryOfficeFormatsWithoutMagicByteChecking() {
        var file = new MockMultipartFile("file", "old-report.doc", "application/msword", "pretend OLE2 bytes".getBytes());
        assertThat(validator.validate(file).valid()).isTrue();
    }

    @Test
    void extensionMatchingIsCaseInsensitive() {
        var file = new MockMultipartFile("file", "THESIS.PDF", "application/pdf", PDF_SIGNATURE);
        assertThat(validator.validate(file).valid()).isTrue();
    }
}
