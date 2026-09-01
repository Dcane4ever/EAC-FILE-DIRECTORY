package ph.edu.eac.filedirectory.maintenance;

import org.springframework.stereotype.Service;
import ph.edu.eac.filedirectory.file.FileStorageService;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class StorageOverviewService {

    private final FileStorageService storageService;

    public StorageOverviewService(FileStorageService storageService) {
        this.storageService = storageService;
    }

    public StorageOverview currentOverview() {
        Path root = storageService.storageRoot();
        try {
            long repositoryBytes;
            try (var paths = Files.walk(root)) {
                repositoryBytes = paths.filter(Files::isRegularFile)
                        .mapToLong(this::fileSize)
                        .sum();
            }
            FileStore fileStore = Files.getFileStore(root);
            long total = fileStore.getTotalSpace();
            long used = Math.max(0, total - fileStore.getUsableSpace());
            return new StorageOverview(repositoryBytes, used, total);
        } catch (IOException e) {
            throw new IllegalStateException("Could not inspect storage capacity for " + root, e);
        }
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new IllegalStateException("Could not inspect stored file size: " + path, e);
        }
    }
}
