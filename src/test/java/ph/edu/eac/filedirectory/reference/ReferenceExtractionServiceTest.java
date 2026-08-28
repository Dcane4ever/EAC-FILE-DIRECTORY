package ph.edu.eac.filedirectory.reference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileStorageService;
import ph.edu.eac.filedirectory.taxonomy.Category;
import ph.edu.eac.filedirectory.taxonomy.Department;
import ph.edu.eac.filedirectory.user.AppUser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceExtractionServiceTest {

    @TempDir
    Path storageRoot;

    @Test
    void extractsReferencesFromTextFile() throws IOException {
        Files.writeString(storageRoot.resolve("paper.txt"), """
                Introduction
                Body text.

                References
                Cruz, J. (2024). Cooling systems in schools. Manila Press.
                Santos, M. (2025). Maintenance planning. EAC Journal.

                Appendix
                Survey form.
                """);

        ExtractedReferences result = service().extract(file("paper.txt"));

        assertThat(result.status()).isEqualTo(ExtractedReferences.Status.FOUND);
        assertThat(result.entries()).containsExactly(
                "Cruz, J. (2024). Cooling systems in schools. Manila Press.",
                "Santos, M. (2025). Maintenance planning. EAC Journal.");
    }

    @Test
    void extractsReferencesFromDocxFile() throws IOException {
        writeDocx(storageRoot.resolve("paper.docx"), """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
                <w:p><w:r><w:t>Works Cited</w:t></w:r></w:p>
                <w:p><w:r><w:t>Garcia, L. (2023). Campus archives. Library Review.</w:t></w:r></w:p>
                <w:p><w:r><w:t>Reyes, P. (2022). Digital repositories. Research Notes.</w:t></w:r></w:p>
                </w:body></w:document>
                """);

        ExtractedReferences result = service().extract(file("paper.docx"));

        assertThat(result.status()).isEqualTo(ExtractedReferences.Status.FOUND);
        assertThat(result.entries()).containsExactly(
                "Garcia, L. (2023). Campus archives. Library Review.",
                "Reyes, P. (2022). Digital repositories. Research Notes.");
    }

    @Test
    void reportsUnsupportedFormatsWithoutReadingAsText() throws IOException {
        Files.writeString(storageRoot.resolve("slides.pptx"), "not used");

        ExtractedReferences result = service().extract(file("slides.pptx"));

        assertThat(result.status()).isEqualTo(ExtractedReferences.Status.UNSUPPORTED);
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void acceptsNumberedReferenceHeadings() throws IOException {
        Files.writeString(storageRoot.resolve("numbered.txt"), """
                Chapter 5

                5. References
                Mohsenzadegan, K., Tavakkoli, V., & Kyamakya, K. (2022). Deep neural network concept for document images.
                Mokahr. (2024). The best resume parsing for PDF and images. https://www.mokahr.com/articles/example
                """);

        ExtractedReferences result = service().extract(file("numbered.txt"));

        assertThat(result.status()).isEqualTo(ExtractedReferences.Status.FOUND);
        assertThat(result.entries()).hasSize(2);
    }

    @Test
    void fallsBackToCitationLookingEntriesNearDocumentEnd() throws IOException {
        Files.writeString(storageRoot.resolve("tail.txt"), """
                Main body
                Conclusion

                Mohsenzadegan, K., Tavakkoli, V., & Kyamakya, K. (2022). Deep neural network concept for document images.
                Nazri, N. (2024). Recent developments in denoising documents. https://doi.org/10.1016/example
                Reeves, S. I., Lee, D., Singh, A., & Verma, K. (2020). A Gaussian process upsampling method.
                """);

        ExtractedReferences result = service().extract(file("tail.txt"));

        assertThat(result.status()).isEqualTo(ExtractedReferences.Status.FOUND);
        assertThat(result.entries()).hasSize(3);
    }

    private ReferenceExtractionService service() {
        return new ReferenceExtractionService(new FileStorageService(storageRoot.toString()));
    }

    private FileEntity file(String filename) {
        return new FileEntity("Paper", "Description", AppUser.registerManually("student@eac.edu.ph", "Student", "hash"),
                new Department(1, "SET", "School of Engineering and Technology"),
                null, null, new Category("Capstone Project", "file"),
                filename, filename, 1L, "application/octet-stream", "checksum");
    }

    private void writeDocx(Path path, String documentXml) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(documentXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
