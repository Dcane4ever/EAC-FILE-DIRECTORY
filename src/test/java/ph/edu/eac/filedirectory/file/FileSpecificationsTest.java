package ph.edu.eac.filedirectory.file;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import ph.edu.eac.filedirectory.taxonomy.*;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.AppUserRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers FileSpecifications.matching() - the combined-filter query that
 * replaced BrowseController's old precedence chain (only the most specific
 * filter used to apply; now every non-null criterion ANDs together). Each
 * test isolates one filter dimension, plus a couple of tests that combine
 * several at once, since that combining behavior is the actual point of
 * this phase.
 */
@SpringBootTest
@ActiveProfiles("junit")
class FileSpecificationsTest {

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private ProgramRepository programRepository;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Department engineering;
    private Department business;
    private Program bsit;
    private Course capstone;
    private Category thesisCategory;
    private Category reportCategory;
    private AppUser uploaderA;
    private AppUser uploaderB;
    private Tag mlTag;
    private Tag aiTag;

    @BeforeEach
    void setUp() {
        fileRepository.deleteAll();
        tagRepository.deleteAll();
        courseRepository.deleteAll();
        programRepository.deleteAll();
        categoryRepository.deleteAll();
        departmentRepository.deleteAll();
        userRepository.deleteAll();

        engineering = departmentRepository.save(new Department(1, "ENGR", "School of Engineering"));
        business = departmentRepository.save(new Department(2, "BUS", "School of Business"));
        bsit = programRepository.save(new Program(1, "BSIT", "BS Information Technology", engineering, "Undergraduate", 4));
        capstone = courseRepository.save(new Course(1, "CS401", "Capstone Project", engineering, null));
        thesisCategory = categoryRepository.save(new Category("Thesis", "fa-graduation-cap"));
        reportCategory = categoryRepository.save(new Category("Report", "fa-file-contract"));
        uploaderA = userRepository.save(verifiedUser("uploader.a@eac.edu.ph", "Uploader A"));
        uploaderB = userRepository.save(verifiedUser("uploader.b@eac.edu.ph", "Uploader B"));
        mlTag = tagRepository.save(new Tag("machine-learning"));
        aiTag = tagRepository.save(new Tag("ai"));
    }

    private AppUser verifiedUser(String email, String name) {
        AppUser user = AppUser.registerManually(email, name, passwordEncoder.encode("SomePassword1"));
        user.setEmailVerified(true);
        return user;
    }

    private FileEntity approvedFile(String title, Department dept, Program program, Course course,
                                     Category category, AppUser uploader, String filename, Instant createdAt, Set<Tag> tags) {
        // Description deliberately doesn't repeat the title/query words used
        // in these tests (e.g. "neural") - the search matches title OR
        // description OR tags, so a shared boilerplate description would
        // make unrelated fixture rows match the query too and break the
        // "query narrows results" tests below.
        FileEntity file = new FileEntity(title, "A generic academic file for testing.", uploader, dept, program, course,
                category, "some/fake/path", filename, 1024L, "application/octet-stream", "deadbeef");
        file.setStatus(FileStatus.APPROVED);
        file.setTags(tags);
        FileEntity saved = fileRepository.save(file);
        setCreatedAt(saved.getId(), createdAt);
        return saved;
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** createdAt is updatable=false (see FileEntity) - go straight to JDBC to backdate a row for the year-filter tests, same approach as AuditTrailTest. */
    private void setCreatedAt(Long fileId, Instant createdAt) {
        jdbcTemplate.update("update files set created_at = ? where id = ?",
                java.sql.Timestamp.from(createdAt), fileId);
    }

    @Test
    void combinesDepartmentAndCategory_bothMustMatch() {
        approvedFile("ENGR Thesis", engineering, null, null, thesisCategory, uploaderA, "a.pdf", Instant.now(), Set.of());
        approvedFile("ENGR Report", engineering, null, null, reportCategory, uploaderA, "b.pdf", Instant.now(), Set.of());
        approvedFile("BUS Thesis", business, null, null, thesisCategory, uploaderA, "c.pdf", Instant.now(), Set.of());

        FileSearchCriteria criteria = new FileSearchCriteria(
                null, engineering.getId(), null, null, thesisCategory.getId(), null, null, null, null);
        var results = fileRepository.findAll(FileSpecifications.matching(FileStatus.APPROVED, criteria),
                PageRequest.of(0, 10));

        assertThat(results.getContent()).extracting(FileEntity::getTitle).containsExactly("ENGR Thesis");
    }

    @Test
    void combinesQueryAndYear_bothMustMatch() {
        approvedFile("Neural Networks 2024", engineering, null, null, thesisCategory, uploaderA, "a.pdf",
                Instant.parse("2024-06-01T00:00:00Z"), Set.of());
        approvedFile("Neural Networks 2025", engineering, null, null, thesisCategory, uploaderA, "b.pdf",
                Instant.parse("2025-06-01T00:00:00Z"), Set.of());
        approvedFile("Unrelated Topic 2024", engineering, null, null, thesisCategory, uploaderA, "c.pdf",
                Instant.parse("2024-06-01T00:00:00Z"), Set.of());

        FileSearchCriteria criteria = new FileSearchCriteria(
                "neural", null, null, null, null, null, 2024, null, null);
        var results = fileRepository.findAll(FileSpecifications.matching(FileStatus.APPROVED, criteria),
                PageRequest.of(0, 10));

        assertThat(results.getContent()).extracting(FileEntity::getTitle).containsExactly("Neural Networks 2024");
    }

    @Test
    void filtersByProgram() {
        approvedFile("With Program", engineering, bsit, null, thesisCategory, uploaderA, "a.pdf", Instant.now(), Set.of());
        approvedFile("Without Program", engineering, null, null, thesisCategory, uploaderA, "b.pdf", Instant.now(), Set.of());

        FileSearchCriteria criteria = new FileSearchCriteria(
                null, null, bsit.getId(), null, null, null, null, null, null);
        var results = fileRepository.findAll(FileSpecifications.matching(FileStatus.APPROVED, criteria),
                PageRequest.of(0, 10));

        assertThat(results.getContent()).extracting(FileEntity::getTitle).containsExactly("With Program");
    }

    @Test
    void filtersByCourse() {
        approvedFile("With Course", engineering, null, capstone, thesisCategory, uploaderA, "a.pdf", Instant.now(), Set.of());
        approvedFile("Without Course", engineering, null, null, thesisCategory, uploaderA, "b.pdf", Instant.now(), Set.of());

        FileSearchCriteria criteria = new FileSearchCriteria(
                null, null, null, capstone.getId(), null, null, null, null, null);
        var results = fileRepository.findAll(FileSpecifications.matching(FileStatus.APPROVED, criteria),
                PageRequest.of(0, 10));

        assertThat(results.getContent()).extracting(FileEntity::getTitle).containsExactly("With Course");
    }

    @Test
    void filtersByTag_caseInsensitive() {
        approvedFile("Tagged ML", engineering, null, null, thesisCategory, uploaderA, "a.pdf", Instant.now(), Set.of(mlTag));
        approvedFile("Tagged AI", engineering, null, null, thesisCategory, uploaderA, "b.pdf", Instant.now(), Set.of(aiTag));
        approvedFile("Untagged", engineering, null, null, thesisCategory, uploaderA, "c.pdf", Instant.now(), Set.of());

        FileSearchCriteria criteria = new FileSearchCriteria(
                null, null, null, null, null, "MACHINE-LEARNING", null, null, null);
        var results = fileRepository.findAll(FileSpecifications.matching(FileStatus.APPROVED, criteria),
                PageRequest.of(0, 10));

        assertThat(results.getContent()).extracting(FileEntity::getTitle).containsExactly("Tagged ML");
    }

    @Test
    void filesWithMultipleMatchingTags_areNotDuplicatedInResults() {
        // A file matching the search query via both its title/description
        // AND multiple tags must still appear exactly once - the join in
        // FileSpecifications needs query.distinct(true) or this would
        // silently multiply rows.
        approvedFile("Neural Networks Study", engineering, null, null, thesisCategory, uploaderA, "a.pdf",
                Instant.now(), Set.of(mlTag, aiTag));

        FileSearchCriteria criteria = new FileSearchCriteria(
                "neural", null, null, null, null, null, null, null, null);
        var results = fileRepository.findAll(FileSpecifications.matching(FileStatus.APPROVED, criteria),
                PageRequest.of(0, 10));

        assertThat(results.getContent()).hasSize(1);
    }

    @Test
    void filtersByYear_derivedFromCreatedAt() {
        approvedFile("From 2023", engineering, null, null, thesisCategory, uploaderA, "a.pdf",
                Instant.parse("2023-03-15T00:00:00Z"), Set.of());
        approvedFile("From 2024", engineering, null, null, thesisCategory, uploaderA, "b.pdf",
                Instant.parse("2024-03-15T00:00:00Z"), Set.of());

        FileSearchCriteria criteria = new FileSearchCriteria(
                null, null, null, null, null, null, 2023, null, null);
        var results = fileRepository.findAll(FileSpecifications.matching(FileStatus.APPROVED, criteria),
                PageRequest.of(0, 10));

        assertThat(results.getContent()).extracting(FileEntity::getTitle).containsExactly("From 2023");
    }

    @Test
    void filtersByFileExtension_derivedFromOriginalFilename() {
        approvedFile("A PDF", engineering, null, null, thesisCategory, uploaderA, "report.pdf", Instant.now(), Set.of());
        approvedFile("A DOCX", engineering, null, null, thesisCategory, uploaderA, "report.docx", Instant.now(), Set.of());

        FileSearchCriteria criteria = new FileSearchCriteria(
                null, null, null, null, null, null, null, "pdf", null);
        var results = fileRepository.findAll(FileSpecifications.matching(FileStatus.APPROVED, criteria),
                PageRequest.of(0, 10));

        assertThat(results.getContent()).extracting(FileEntity::getTitle).containsExactly("A PDF");
    }

    @Test
    void filtersByUploader() {
        approvedFile("By A", engineering, null, null, thesisCategory, uploaderA, "a.pdf", Instant.now(), Set.of());
        approvedFile("By B", engineering, null, null, thesisCategory, uploaderB, "b.pdf", Instant.now(), Set.of());

        FileSearchCriteria criteria = new FileSearchCriteria(
                null, null, null, null, null, null, null, null, uploaderA.getId());
        var results = fileRepository.findAll(FileSpecifications.matching(FileStatus.APPROVED, criteria),
                PageRequest.of(0, 10));

        assertThat(results.getContent()).extracting(FileEntity::getTitle).containsExactly("By A");
    }

    @Test
    void onlyReturnsFilesMatchingTheRequestedStatus() {
        FileEntity pending = new FileEntity("Pending File", "desc", uploaderA, engineering, null, null,
                thesisCategory, "path", "a.pdf", 1024L, "application/pdf", "deadbeef");
        fileRepository.save(pending);
        approvedFile("Approved File", engineering, null, null, thesisCategory, uploaderA, "b.pdf", Instant.now(), Set.of());

        var results = fileRepository.findAll(
                FileSpecifications.matching(FileStatus.APPROVED, FileSearchCriteria.empty()), PageRequest.of(0, 10));

        assertThat(results.getContent()).extracting(FileEntity::getTitle).containsExactly("Approved File");
    }

    @Test
    void emptyCriteria_returnsEverythingWithTheGivenStatus() {
        approvedFile("File One", engineering, null, null, thesisCategory, uploaderA, "a.pdf", Instant.now(), Set.of());
        approvedFile("File Two", business, null, null, reportCategory, uploaderB, "b.pdf", Instant.now(), Set.of());

        var results = fileRepository.findAll(
                FileSpecifications.matching(FileStatus.APPROVED, FileSearchCriteria.empty()), PageRequest.of(0, 10));

        assertThat(results.getTotalElements()).isEqualTo(2);
    }

    @Test
    void sortOptions_produceExpectedOrdering() {
        FileEntity older = approvedFile("Zebra", engineering, null, null, thesisCategory, uploaderA, "a.pdf",
                Instant.now().minus(2, ChronoUnit.DAYS), Set.of());
        FileEntity newer = approvedFile("Apple", engineering, null, null, thesisCategory, uploaderA, "b.pdf",
                Instant.now(), Set.of());
        newer.setStatus(FileStatus.APPROVED);

        var newestFirst = fileRepository.findAll(
                FileSpecifications.matching(FileStatus.APPROVED, FileSearchCriteria.empty()),
                PageRequest.of(0, 10, FileSortOption.NEWEST.toSort()));
        assertThat(newestFirst.getContent()).extracting(FileEntity::getTitle).containsExactly("Apple", "Zebra");

        var oldestFirst = fileRepository.findAll(
                FileSpecifications.matching(FileStatus.APPROVED, FileSearchCriteria.empty()),
                PageRequest.of(0, 10, FileSortOption.OLDEST.toSort()));
        assertThat(oldestFirst.getContent()).extracting(FileEntity::getTitle).containsExactly("Zebra", "Apple");

        var alphabetical = fileRepository.findAll(
                FileSpecifications.matching(FileStatus.APPROVED, FileSearchCriteria.empty()),
                PageRequest.of(0, 10, FileSortOption.ALPHABETICAL.toSort()));
        assertThat(alphabetical.getContent()).extracting(FileEntity::getTitle).containsExactly("Apple", "Zebra");
    }

    @Test
    void mostDownloadedSort_ordersByDownloadCountDescending() {
        FileEntity popular = approvedFile("Popular", engineering, null, null, thesisCategory, uploaderA, "a.pdf", Instant.now(), Set.of());
        FileEntity obscure = approvedFile("Obscure", engineering, null, null, thesisCategory, uploaderA, "b.pdf", Instant.now(), Set.of());
        for (int i = 0; i < 5; i++) popular.incrementDownloadCount();
        fileRepository.save(popular);

        var results = fileRepository.findAll(
                FileSpecifications.matching(FileStatus.APPROVED, FileSearchCriteria.empty()),
                PageRequest.of(0, 10, FileSortOption.MOST_DOWNLOADED.toSort()));

        assertThat(results.getContent()).extracting(FileEntity::getTitle).containsExactly("Popular", "Obscure");
    }
}
