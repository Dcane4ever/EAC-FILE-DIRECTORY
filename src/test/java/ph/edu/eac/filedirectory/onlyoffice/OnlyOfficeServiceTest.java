package ph.edu.eac.filedirectory.onlyoffice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.taxonomy.Category;
import ph.edu.eac.filedirectory.taxonomy.Department;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.Role;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain unit tests (no Spring context - OnlyOfficeService has no repository
 * dependencies, just OnlyOfficeProperties) covering: extension/type mapping,
 * supported/unsupported detection, document key stability/change-on-content-
 * change, config generation shape, and JWT generation/verification for the
 * document-access token. MockMvc-level authorization tests (unauthorized
 * config/content requests) live in OnlyOfficeControllerTest instead, since
 * those need the real HTTP layer + Spring Security to mean anything.
 */
class OnlyOfficeServiceTest {

    private static final String SECRET = "test-only-secret-value-not-used-anywhere-real";

    private OnlyOfficeService service;

    @BeforeEach
    void setUp() {
        OnlyOfficeProperties properties = new OnlyOfficeProperties(
                "http://localhost:8082",
                new OnlyOfficeProperties.Jwt(true, SECRET),
                "http://localhost:8084",
                5
        );
        service = new OnlyOfficeService(properties);
    }

    // --- Extension / documentType mapping ---

    @Test
    void wordExtensions_mapToWordType() {
        assertThat(OnlyOfficeDocumentType.forExtension("doc")).isEqualTo(OnlyOfficeDocumentType.WORD);
        assertThat(OnlyOfficeDocumentType.forExtension("docx")).isEqualTo(OnlyOfficeDocumentType.WORD);
        assertThat(OnlyOfficeDocumentType.WORD.apiValue()).isEqualTo("word");
    }

    @Test
    void spreadsheetExtensions_mapToCellType() {
        assertThat(OnlyOfficeDocumentType.forExtension("xls")).isEqualTo(OnlyOfficeDocumentType.CELL);
        assertThat(OnlyOfficeDocumentType.forExtension("xlsx")).isEqualTo(OnlyOfficeDocumentType.CELL);
        assertThat(OnlyOfficeDocumentType.CELL.apiValue()).isEqualTo("cell");
    }

    @Test
    void presentationExtensions_mapToSlideType() {
        assertThat(OnlyOfficeDocumentType.forExtension("ppt")).isEqualTo(OnlyOfficeDocumentType.SLIDE);
        assertThat(OnlyOfficeDocumentType.forExtension("pptx")).isEqualTo(OnlyOfficeDocumentType.SLIDE);
        assertThat(OnlyOfficeDocumentType.SLIDE.apiValue()).isEqualTo("slide");
    }

    @Test
    void unsupportedExtensions_mapToNull() {
        assertThat(OnlyOfficeDocumentType.forExtension("pdf")).isNull(); // PDF.js handles this instead, not ONLYOFFICE
        assertThat(OnlyOfficeDocumentType.forExtension("zip")).isNull();
        assertThat(OnlyOfficeDocumentType.forExtension(null)).isNull();
        assertThat(OnlyOfficeDocumentType.forExtension("")).isNull();
    }

    // --- Supported/unsupported file detection ---

    @Test
    void aPptxFile_isSupported() {
        FileEntity file = fileFixture("slides.pptx", "existing-checksum");
        assertThat(service.isSupported(file)).isTrue();
        assertThat(service.determineDocumentType(file)).isEqualTo(OnlyOfficeDocumentType.SLIDE);
    }

    @Test
    void aPdfFile_isNotSupportedByOnlyOffice() {
        // PDF.js already handles this natively - see FileEntity.isPreviewable
        // vs. isOfficePreviewable, deliberately mutually exclusive.
        FileEntity file = fileFixture("thesis.pdf", "existing-checksum");
        assertThat(service.isSupported(file)).isFalse();
    }

    @Test
    void aZipFile_isNotSupported() {
        FileEntity file = fileFixture("archive.zip", "existing-checksum");
        assertThat(service.isSupported(file)).isFalse();
    }

    // --- Document key ---

    @Test
    void documentKey_isStableForTheSameFile() {
        FileEntity file = fileFixture("report.docx", "checksum-a");
        String keyOne = service.generateDocumentKey(file);
        String keyTwo = service.generateDocumentKey(file);
        assertThat(keyOne).isEqualTo(keyTwo).isNotBlank();
    }

    @Test
    void documentKey_changesWhenTheChecksumChanges() {
        FileEntity fileA = fileFixture("report.docx", "checksum-a");
        FileEntity fileB = fileFixture("report.docx", "checksum-b");
        assertThat(service.generateDocumentKey(fileA)).isNotEqualTo(service.generateDocumentKey(fileB));
    }

    @Test
    void documentKey_staysWithinOnlyOfficesLengthLimit() {
        FileEntity file = fileFixture("report.docx", "checksum-a");
        // ONLYOFFICE caps document.key at 128 chars.
        assertThat(service.generateDocumentKey(file).length()).isLessThanOrEqualTo(128);
    }

    // --- Config generation ---

    @Test
    @SuppressWarnings("unchecked")
    void createPreviewConfig_isViewOnlyWithNoDownloadPermission() {
        FileEntity file = fileFixture("slides.pptx", "checksum-a");
        Map<String, Object> config = service.createPreviewConfig(file);

        assertThat(config.get("documentType")).isEqualTo("slide");
        Map<String, Object> document = (Map<String, Object>) config.get("document");
        assertThat(document.get("title")).isEqualTo("slides.pptx");
        assertThat(document.get("url")).asString().contains("/files/").contains("/onlyoffice-content").contains("token=");

        Map<String, Object> permissions = (Map<String, Object>) document.get("permissions");
        assertThat(permissions.get("edit")).isEqualTo(false);
        assertThat(permissions.get("download")).isEqualTo(false);

        Map<String, Object> editorConfig = (Map<String, Object>) config.get("editorConfig");
        assertThat(editorConfig.get("mode")).isEqualTo("view");

        // JWT enabled in this test's properties, so the config must be signed.
        assertThat(config.get("token")).asString().isNotBlank();
    }

    @Test
    void createPreviewConfig_rejectsAnUnsupportedFile() {
        FileEntity file = fileFixture("thesis.pdf", "checksum-a");
        assertThatThrownBy(() -> service.createPreviewConfig(file))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Document access token (the server-to-server content fetch token) ---

    @Test
    void aFreshlyIssuedDocumentUrl_verifiesSuccessfullyForItsOwnFile() {
        FileEntity file = fileFixture("slides.pptx", "checksum-a");
        setId(file, 42L);

        String url = service.generateSecureDocumentUrl(file);
        String token = url.substring(url.indexOf("token=") + "token=".length());

        OnlyOfficeService.DocumentAccessTokenResult result = service.verifyDocumentAccessToken(token, 42L);
        assertThat(result.valid()).isTrue();
        assertThat(result.fileId()).isEqualTo(42L);
    }

    @Test
    void aDocumentAccessToken_isRejectedForADifferentFileId() {
        FileEntity file = fileFixture("slides.pptx", "checksum-a");
        setId(file, 42L);

        String url = service.generateSecureDocumentUrl(file);
        String token = url.substring(url.indexOf("token=") + "token=".length());

        OnlyOfficeService.DocumentAccessTokenResult result = service.verifyDocumentAccessToken(token, 99L);
        assertThat(result.valid()).isFalse();
    }

    @Test
    void aGarbageToken_isRejected() {
        OnlyOfficeService.DocumentAccessTokenResult result = service.verifyDocumentAccessToken("not-a-real-jwt", 1L);
        assertThat(result.valid()).isFalse();
    }

    @Test
    void aTokenSignedWithADifferentSecret_isRejected() {
        OnlyOfficeProperties otherProperties = new OnlyOfficeProperties(
                "http://localhost:8082",
                new OnlyOfficeProperties.Jwt(true, "a-completely-different-secret-value"),
                "http://localhost:8084",
                5
        );
        OnlyOfficeService otherService = new OnlyOfficeService(otherProperties);

        FileEntity file = fileFixture("slides.pptx", "checksum-a");
        setId(file, 42L);
        String url = otherService.generateSecureDocumentUrl(file);
        String token = url.substring(url.indexOf("token=") + "token=".length());

        // Verified against THIS test's service (different secret) - must fail.
        assertThat(service.verifyDocumentAccessToken(token, 42L).valid()).isFalse();
    }

    // --- Fixtures ---

    private FileEntity fileFixture(String originalFilename, String checksum) {
        AppUser uploader = AppUser.registerManually("uploader@eac.edu.ph", "Uploader One", "hash");
        uploader.setRole(Role.USER);
        Department department = new Department(1, "ENGR", "School of Engineering");
        Category category = new Category("Thesis", "fa-graduation-cap");
        FileEntity file = new FileEntity("Title", "Description", uploader, department, null, null, category,
                "ENGR/2026/some-path_" + originalFilename, originalFilename, 1024L, "application/octet-stream", checksum);
        setId(file, 1L);
        return file;
    }

    /** FileEntity's id is JPA-generated (no public setter) - reflection is the least invasive way to give a test fixture a stable id without opening up the entity's API just for tests. */
    private void setId(FileEntity file, Long id) {
        try {
            Field idField = FileEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(file, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
