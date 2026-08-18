package ph.edu.eac.filedirectory.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ph.edu.eac.filedirectory.taxonomy.Category;
import ph.edu.eac.filedirectory.taxonomy.CategoryRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Seeds the departments/programs/courses taxonomy from the bundled SQL files
 * under resources/seed/ (generated from the real eacdb registrar dump - see
 * tools/eacdb-extract/README.md) and a starter category list, on first boot
 * only. Never overwrites existing rows, so it's safe to leave enabled
 * permanently and re-run on every startup.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    /** table name -> seed script, run in order (programs/courses depend on departments existing). */
    private static final java.util.LinkedHashMap<String, String> TAXONOMY_SCRIPTS = new java.util.LinkedHashMap<>();
    static {
        TAXONOMY_SCRIPTS.put("departments", "seed/01-departments.sql");
        TAXONOMY_SCRIPTS.put("programs", "seed/02-programs.sql");
        TAXONOMY_SCRIPTS.put("courses", "seed/03-courses.sql");
    }

    private static final List<String> DEFAULT_CATEGORIES = List.of(
            "Research Paper|fa-file-lines",
            "Thesis|fa-graduation-cap",
            "Capstone Project|fa-diagram-project",
            "Book|fa-book",
            "Code / Software Project|fa-code",
            "Presentation (PPTX)|fa-display",
            "Dataset|fa-database",
            "Report|fa-file-contract",
            "Other|fa-file"
    );

    private final JdbcTemplate jdbcTemplate;
    private final CategoryRepository categoryRepository;

    public DataSeeder(JdbcTemplate jdbcTemplate, CategoryRepository categoryRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.categoryRepository = categoryRepository;
    }

    @Override
    public void run(String... args) {
        seedTaxonomy();
        seedCategories();
    }

    @Transactional
    void seedTaxonomy() {
        for (var entry : TAXONOMY_SCRIPTS.entrySet()) {
            String table = entry.getKey();
            Integer count = jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
            if (count != null && count > 0) {
                log.info("{} already seeded ({} rows) - skipping.", table, count);
                continue;
            }
            log.info("Seeding {} from bundled eacdb extraction...", table);
            runSqlScript(entry.getValue());
        }
    }

    void seedCategories() {
        for (String entry : DEFAULT_CATEGORIES) {
            String[] parts = entry.split("\\|", 2);
            String name = parts[0];
            String icon = parts[1];
            categoryRepository.findByName(name).orElseGet(() -> categoryRepository.save(new Category(name, icon)));
        }
    }

    private void runSqlScript(String classpathLocation) {
        String sql;
        try {
            sql = Files.readString(new ClassPathResource(classpathLocation).getFile().toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Packaged as a WAR (not exploded on disk) - fall back to stream reading.
            try (var in = new ClassPathResource(classpathLocation).getInputStream()) {
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException inner) {
                throw new IllegalStateException("Could not read seed script " + classpathLocation, inner);
            }
        }

        // Each file is a single "INSERT INTO ... VALUES (...), (...);" statement
        // with '--' comment lines above it; strip comments, then execute as one.
        String statement = sql.lines()
                .filter(line -> !line.startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b)
                .trim();

        if (!statement.isEmpty()) {
            jdbcTemplate.execute(statement);
        }
    }
}
