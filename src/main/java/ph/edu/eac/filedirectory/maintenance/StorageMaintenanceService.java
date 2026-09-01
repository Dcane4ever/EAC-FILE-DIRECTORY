package ph.edu.eac.filedirectory.maintenance;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStorageService;
import ph.edu.eac.filedirectory.file.FileVersion;
import ph.edu.eac.filedirectory.file.FileVersionRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Compares database references with the configured storage directory without
 * changing either one. Destructive maintenance belongs to a later phase.
 */
@Service
public class StorageMaintenanceService {

    private final Supplier<List<FileEntity>> files;
    private final Supplier<List<FileVersion>> versions;
    private final FileStorageService storageService;

    @Autowired
    public StorageMaintenanceService(FileRepository fileRepository,
                                     FileVersionRepository fileVersionRepository,
                                     FileStorageService storageService) {
        this(fileRepository::findAll, fileVersionRepository::findAll, storageService);
    }

    StorageMaintenanceService(Supplier<List<FileEntity>> files,
                              Supplier<List<FileVersion>> versions,
                              FileStorageService storageService) {
        this.files = files;
        this.versions = versions;
        this.storageService = storageService;
    }

    public StorageMaintenanceReport scan() {
        List<TrackedReference> references = trackedReferences();
        Map<String, List<TrackedReference>> referencesByPath = new LinkedHashMap<>();
        for (TrackedReference reference : references) {
            referencesByPath.computeIfAbsent(reference.relativePath(), ignored -> new ArrayList<>()).add(reference);
        }

        List<StorageMaintenanceReport.MissingStoredFile> missing = references.stream()
                .filter(reference -> !storedFileExists(reference.relativePath()))
                .map(TrackedReference::asMissingStoredFile)
                .sorted(Comparator.comparing(StorageMaintenanceReport.MissingStoredFile::relativePath)
                        .thenComparing(StorageMaintenanceReport.MissingStoredFile::recordType))
                .toList();

        Map<String, DiskFile> diskFiles = diskFiles();
        List<StorageMaintenanceReport.OrphanedStoredFile> orphaned = diskFiles.values().stream()
                .filter(diskFile -> !referencesByPath.containsKey(diskFile.relativePath()))
                .map(diskFile -> new StorageMaintenanceReport.OrphanedStoredFile(diskFile.relativePath(), diskFile.size()))
                .sorted(Comparator.comparing(StorageMaintenanceReport.OrphanedStoredFile::relativePath))
                .toList();

        List<StorageMaintenanceReport.DuplicateChecksum> duplicateChecksums = duplicateChecksums(references);
        List<StorageMaintenanceReport.LargestStoredFile> largestStoredFiles = diskFiles.values().stream()
                .sorted(Comparator.comparingLong(DiskFile::size).reversed().thenComparing(DiskFile::relativePath))
                .limit(10)
                .map(diskFile -> new StorageMaintenanceReport.LargestStoredFile(diskFile.relativePath(), diskFile.size()))
                .toList();
        List<StorageMaintenanceReport.DepartmentStorage> departmentStorage = departmentStorage(diskFiles.values());
        long diskBytes = diskFiles.values().stream().mapToLong(DiskFile::size).sum();

        return new StorageMaintenanceReport(Instant.now(), storageService.storageRoot().toString(), references.size(),
                diskFiles.size(), diskBytes, missing, orphaned, duplicateChecksums, largestStoredFiles, departmentStorage);
    }

    private List<TrackedReference> trackedReferences() {
        List<TrackedReference> references = new ArrayList<>();
        for (FileEntity file : files.get()) {
            references.add(new TrackedReference("Current file", file.getId(), null, file.getTitle(), file.getFilePath(), file.getChecksum()));
        }
        for (FileVersion version : versions.get()) {
            FileEntity file = version.getFile();
            references.add(new TrackedReference("Version", file.getId(), version.getVersionNumber(), file.getTitle(),
                    version.getFilePath(), version.getChecksum()));
        }
        return references;
    }

    public boolean storedFileExists(String relativePath) {
        return storageService.storedFileExists(relativePath);
    }

    private Map<String, DiskFile> diskFiles() {
        Path root = storageService.storageRoot();
        if (!Files.isDirectory(root)) {
            return Map.of();
        }
        try (var paths = Files.walk(root)) {
            Map<String, DiskFile> filesByPath = new LinkedHashMap<>();
            paths.filter(Files::isRegularFile).forEach(path -> {
                String relativePath = normalizedRelativePath(root, path);
                try {
                    filesByPath.put(relativePath, new DiskFile(relativePath, Files.size(path)));
                } catch (IOException e) {
                    throw new StorageScanException("Could not inspect stored file: " + relativePath, e);
                }
            });
            return filesByPath;
        } catch (IOException e) {
            throw new StorageScanException("Could not scan storage root: " + root, e);
        }
    }

    private List<StorageMaintenanceReport.DuplicateChecksum> duplicateChecksums(List<TrackedReference> references) {
        Map<String, List<TrackedReference>> byChecksum = new LinkedHashMap<>();
        for (TrackedReference reference : references) {
            if (reference.checksum() != null && !reference.checksum().isBlank()) {
                byChecksum.computeIfAbsent(reference.checksum(), ignored -> new ArrayList<>()).add(reference);
            }
        }

        return byChecksum.entrySet().stream()
                .map(entry -> duplicateChecksum(entry.getKey(), entry.getValue()))
                .filter(duplicate -> duplicate.distinctPhysicalFileCount() > 1)
                .sorted(Comparator.comparingLong(StorageMaintenanceReport.DuplicateChecksum::distinctPhysicalFileCount).reversed()
                        .thenComparing(StorageMaintenanceReport.DuplicateChecksum::checksum))
                .toList();
    }

    private StorageMaintenanceReport.DuplicateChecksum duplicateChecksum(String checksum, List<TrackedReference> references) {
        Set<String> paths = new LinkedHashSet<>();
        List<StorageMaintenanceReport.ChecksumReference> items = new ArrayList<>();
        for (TrackedReference reference : references) {
            paths.add(reference.relativePath());
            items.add(reference.asChecksumReference());
        }
        items.sort(Comparator.comparing(StorageMaintenanceReport.ChecksumReference::relativePath)
                .thenComparing(StorageMaintenanceReport.ChecksumReference::recordType));
        return new StorageMaintenanceReport.DuplicateChecksum(checksum, paths.size(), List.copyOf(items));
    }

    private List<StorageMaintenanceReport.DepartmentStorage> departmentStorage(Iterable<DiskFile> diskFiles) {
        Map<String, DepartmentTotals> totals = new LinkedHashMap<>();
        for (DiskFile diskFile : diskFiles) {
            String department = departmentFromPath(diskFile.relativePath());
            totals.computeIfAbsent(department, ignored -> new DepartmentTotals()).add(diskFile.size());
        }
        return totals.entrySet().stream()
                .map(entry -> new StorageMaintenanceReport.DepartmentStorage(entry.getKey(), entry.getValue().fileCount, entry.getValue().storageBytes))
                .sorted(Comparator.comparingLong(StorageMaintenanceReport.DepartmentStorage::storageBytes).reversed()
                        .thenComparing(StorageMaintenanceReport.DepartmentStorage::departmentCode))
                .toList();
    }

    private String normalizedRelativePath(Path root, Path path) {
        return root.relativize(path).normalize().toString().replace('\\', '/');
    }

    private String departmentFromPath(String relativePath) {
        int separator = relativePath.indexOf('/');
        return separator > 0 ? relativePath.substring(0, separator) : "Unclassified";
    }

    private record DiskFile(String relativePath, long size) {
    }

    private record TrackedReference(String recordType, Long fileId, Integer versionNumber, String title,
                                    String relativePath, String checksum) {
        StorageMaintenanceReport.MissingStoredFile asMissingStoredFile() {
            return new StorageMaintenanceReport.MissingStoredFile(recordType, fileId, versionNumber, title, relativePath);
        }

        StorageMaintenanceReport.ChecksumReference asChecksumReference() {
            return new StorageMaintenanceReport.ChecksumReference(recordType, fileId, versionNumber, title, relativePath);
        }
    }

    private static final class DepartmentTotals {
        private long fileCount;
        private long storageBytes;

        void add(long size) {
            fileCount++;
            storageBytes += size;
        }
    }

    public static final class StorageScanException extends RuntimeException {
        StorageScanException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
