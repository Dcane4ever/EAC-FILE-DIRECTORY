package ph.edu.eac.filedirectory.settings;

import org.springframework.stereotype.Service;

/**
 * Reads/writes the single SystemSetting row, creating it with defaults on
 * first access if it doesn't exist yet (a fresh database has none) so every
 * other caller can just ask "is X enabled" without null-checking. No
 * caching - this app runs as a single instance with infrequent reads (once
 * per upload at most), so a plain repository read is not a meaningful cost;
 * adding a cache here would be exactly the kind of speculative complexity
 * the roadmap says to avoid.
 */
@Service
public class SystemSettingService {

    private final SystemSettingRepository repository;

    public SystemSettingService(SystemSettingRepository repository) {
        this.repository = repository;
    }

    public SystemSetting current() {
        return repository.findById(SystemSetting.SINGLETON_ID)
                .orElseGet(() -> repository.save(SystemSetting.defaults()));
    }

    public boolean isAllowDuplicateUploads() {
        return current().isAllowDuplicateUploads();
    }

    public void setAllowDuplicateUploads(boolean allowed) {
        SystemSetting settings = current();
        settings.setAllowDuplicateUploads(allowed);
        repository.save(settings);
    }

    public boolean isAllowModeratorAccessRequestFallback() {
        return current().isAllowModeratorAccessRequestFallback();
    }

    public void setAllowModeratorAccessRequestFallback(boolean allowed) {
        SystemSetting settings = current();
        settings.setAllowModeratorAccessRequestFallback(allowed);
        repository.save(settings);
    }
}
