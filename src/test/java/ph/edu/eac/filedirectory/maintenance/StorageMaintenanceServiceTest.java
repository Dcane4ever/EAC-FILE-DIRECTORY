package ph.edu.eac.filedirectory.maintenance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileStorageService;
import ph.edu.eac.filedirectory.file.FileVersion;
import ph.edu.eac.filedirectory.taxonomy.Category;
import ph.edu.eac.filedirectory.taxonomy.Department;
import ph.edu.eac.filedirectory.user.AppUser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StorageMaintenanceServiceTest {

    @TempDir
    Path storageRoot;

    @Test
    void reportsMissingAndOrphanedFilesAndGroupsRealDuplicateContent() throws IOException {
        write("ENGR/2026/first.pdf", "same-content");
        write("BUS/2026/second.pdf", "same-content");
        write("ORPHAN/untracked.txt", "orphan-content");

        FileEntity first = file("First file", "ENGR/2026/first.pdf", "same-checksum");
        FileEntity second = file("Second file", "BUS/2026/second.pdf", "same-checksum");
        FileEntity missing = file("Missing file", "ENGR/2026/missing.pdf", "missing-checksum");
        FileVersion snapshot = version(first, 1, "ENGR/2026/first.pdf", "same-checksum");
        FileVersion missingVersion = version(missing, 2, "ENGR/2026/missing-v2.pdf", "missing-version-checksum");

        StorageMaintenanceReport report = service(List.of(first, second, missing), List.of(snapshot, missingVersion)).scan();

        assertThat(report.databaseReferenceCount()).isEqualTo(5);
        assertThat(report.diskFileCount()).isEqualTo(3);
        assertThat(report.missingStoredFiles()).extracting(StorageMaintenanceReport.MissingStoredFile::relativePath)
                .containsExactlyInAnyOrder("ENGR/2026/missing.pdf", "ENGR/2026/missing-v2.pdf");
        assertThat(report.orphanedStoredFiles()).extracting(StorageMaintenanceReport.OrphanedStoredFile::relativePath)
                .containsExactly("ORPHAN/untracked.txt");
        assertThat(report.largestStoredFiles()).first().extracting(StorageMaintenanceReport.LargestStoredFile::relativePath)
                .isEqualTo("ORPHAN/untracked.txt");
        assertThat(report.duplicateChecksums()).singleElement().satisfies(group -> {
            assertThat(group.checksum()).isEqualTo("same-checksum");
            assertThat(group.distinctPhysicalFileCount()).isEqualTo(2);
            assertThat(group.references()).hasSize(3);
        });
        assertThat(report.departmentStorage()).extracting(StorageMaintenanceReport.DepartmentStorage::departmentCode)
                .containsExactlyInAnyOrder("BUS", "ENGR", "ORPHAN");
    }

    @Test
    void treatsUnsafeDatabasePathsAsMissingWithoutLeavingStorageRoot() {
        FileEntity unsafe = file("Unsafe", "../outside.txt", "unsafe-checksum");

        StorageMaintenanceReport report = service(List.of(unsafe), List.of()).scan();

        assertThat(report.missingStoredFiles()).singleElement()
                .extracting(StorageMaintenanceReport.MissingStoredFile::relativePath)
                .isEqualTo("../outside.txt");
        assertThat(report.diskFileCount()).isZero();
    }

    private StorageMaintenanceService service(List<FileEntity> files, List<FileVersion> versions) {
        return new StorageMaintenanceService(() -> files, () -> versions, new FileStorageService(storageRoot.toString()));
    }

    private FileEntity file(String title, String path, String checksum) {
        return new FileEntity(title, "Description", AppUser.registerManually("student@eac.edu.ph", "Student", "hash"),
                new Department(1, "ENGR", "School of Engineering"), null, null,
                new Category("Capstone Project", "file"), path, path, 1L, "application/pdf", checksum);
    }

    private FileVersion version(FileEntity file, int number, String path, String checksum) {
        return new FileVersion(file, number, path, path, 1L, "application/pdf", checksum,
                file.getUploader(), "Version note");
    }

    private void write(String relativePath, String content) throws IOException {
        Path target = storageRoot.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
