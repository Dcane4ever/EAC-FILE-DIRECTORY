package ph.edu.eac.filedirectory.maintenance;

import java.util.Locale;

/** Live filesystem capacity plus the space used by the configured repository folder. */
public record StorageOverview(long repositoryBytes, long volumeUsedBytes, long volumeTotalBytes) {

    public int volumeUsedPercent() {
        if (volumeTotalBytes <= 0) {
            return 0;
        }
        return (int) Math.max(0, Math.min(100, Math.round(volumeUsedBytes * 100.0 / volumeTotalBytes)));
    }

    public String repositoryLabel() {
        return formatBytes(repositoryBytes);
    }

    public String volumeUsedLabel() {
        return formatBytes(volumeUsedBytes);
    }

    public String volumeTotalLabel() {
        return formatBytes(volumeTotalBytes);
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
        return String.format(Locale.ROOT, value >= 10 ? "%.1f %s" : "%.2f %s", value, units[unit]);
    }
}
