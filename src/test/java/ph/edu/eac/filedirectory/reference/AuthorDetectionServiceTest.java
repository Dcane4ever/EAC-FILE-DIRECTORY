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

class AuthorDetectionServiceTest {

    @TempDir
    Path storageRoot;

    @Test
    void detectsCapstoneAuthorsFromTextTitlePage() throws IOException {
        Files.writeString(storageRoot.resolve("paper.txt"), """
                Extracting Texts on Low Image Quality
                Resume by Optimizing Gaussian Blur Parameters

                A Capstone Project by

                Alonzo, Rafael Christian
                Buenavista, Michael Angelo
                Pimentel, Vincent Gabrielle N.
                Podiotan, Luis Fernando E.

                Submitted to the School of Engineering and Technology
                Emilio Aguinaldo College - Manila
                """);

        DetectedAuthors result = service().detect(file("paper.txt"));

        assertThat(result.status()).isEqualTo(DetectedAuthors.Status.FOUND);
        assertThat(result.names()).containsExactly(
                "Alonzo, Rafael Christian",
                "Buenavista, Michael Angelo",
                "Pimentel, Vincent Gabrielle N.",
                "Podiotan, Luis Fernando E.");
    }

    @Test
    void detectsCapstoneAuthorsFromDocxTitlePage() throws IOException {
        writeDocx(storageRoot.resolve("paper.docx"), """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
                <w:p><w:r><w:t>A Capstone Project by</w:t></w:r></w:p>
                <w:p><w:r><w:t>Alonzo, Rafael Christian</w:t></w:r></w:p>
                <w:p><w:r><w:t>Buenavista, Michael Angelo</w:t></w:r></w:p>
                <w:p><w:r><w:t>Pimentel, Vincent Gabrielle N.</w:t></w:r></w:p>
                <w:p><w:r><w:t>Podiotan, Luis Fernando E.</w:t></w:r></w:p>
                <w:p><w:r><w:t>Submitted to the School of Engineering and Technology</w:t></w:r></w:p>
                </w:body></w:document>
                """);

        DetectedAuthors result = service().detect(file("paper.docx"));

        assertThat(result.status()).isEqualTo(DetectedAuthors.Status.FOUND);
        assertThat(result.joinedNames()).isEqualTo("Alonzo, Rafael Christian, Buenavista, Michael Angelo, Pimentel, Vincent Gabrielle N., Podiotan, Luis Fernando E.");
    }

    @Test
    void doesNotGuessWhenNoAuthorCueExists() throws IOException {
        Files.writeString(storageRoot.resolve("paper.txt"), """
                Extracting Texts on Low Image Quality
                Submitted to the School of Engineering and Technology
                """);

        DetectedAuthors result = service().detect(file("paper.txt"));

        assertThat(result.status()).isEqualTo(DetectedAuthors.Status.NOT_FOUND);
        assertThat(result.names()).isEmpty();
    }

    private AuthorDetectionService service() {
        return new AuthorDetectionService(new FileStorageService(storageRoot.toString()));
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
