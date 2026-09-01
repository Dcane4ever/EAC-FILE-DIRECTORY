package ph.edu.eac.filedirectory.maintenance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ph.edu.eac.filedirectory.file.FileStorageService;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StorageOverviewServiceTest {

    @TempDir
    Path storageRoot;

    @Test
    void reportsActualRepositoryBytesAndContainingVolumeCapacity() throws Exception {
        Path storedFile = storageRoot.resolve("SET/2026/report.txt");
        Files.createDirectories(storedFile.getParent());
        Files.writeString(storedFile, "1234567890");

        StorageOverview overview = new StorageOverviewService(new FileStorageService(storageRoot.toString())).currentOverview();

        assertThat(overview.repositoryBytes()).isEqualTo(10L);
        assertThat(overview.volumeTotalBytes()).isPositive();
        assertThat(overview.volumeUsedBytes()).isBetween(0L, overview.volumeTotalBytes());
        assertThat(overview.volumeUsedPercent()).isBetween(0, 100);
    }
}
