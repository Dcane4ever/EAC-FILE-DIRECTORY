package ph.edu.eac.filedirectory.maintenance;

import java.time.Instant;
import java.util.List;

/** Immutable result of a read-only comparison between repository rows and disk storage. */
public record StorageMaintenanceReport(
        Instant scannedAt,
        String storageRoot,
        long databaseReferenceCount,
        long diskFileCount,
        long diskStorageBytes,
        List<MissingStoredFile> missingStoredFiles,
        List<OrphanedStoredFile> orphanedStoredFiles,
        List<DuplicateChecksum> duplicateChecksums,
        List<LargestStoredFile> largestStoredFiles,
        List<DepartmentStorage> departmentStorage) {

    public String diskStorageLabel() {
        return formatBytes(diskStorageBytes);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(java.util.Locale.ROOT, value >= 10 ? "%.1f %s" : "%.2f %s", value, units[unit]);
    }

    public record MissingStoredFile(String recordType, Long fileId, Integer versionNumber,
                                    String title, String relativePath) {
    }

    public record OrphanedStoredFile(String relativePath, long size) {
        public String sizeLabel() {
            return formatBytes(size);
        }
    }

    public record ChecksumReference(String recordType, Long fileId, Integer versionNumber,
                                    String title, String relativePath) {
    }

    public record DuplicateChecksum(String checksum, long distinctPhysicalFileCount,
                                    List<ChecksumReference> references) {
    }

    public record LargestStoredFile(String relativePath, long size) {
        public String sizeLabel() {
            return formatBytes(size);
        }
    }

    public record DepartmentStorage(String departmentCode, long fileCount, long storageBytes) {
        public String storageLabel() {
            return formatBytes(storageBytes);
        }
    }
}
