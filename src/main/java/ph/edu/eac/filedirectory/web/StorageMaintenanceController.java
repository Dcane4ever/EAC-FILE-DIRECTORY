package ph.edu.eac.filedirectory.web;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ph.edu.eac.filedirectory.audit.AuditService;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.maintenance.StorageMaintenanceReport;
import ph.edu.eac.filedirectory.maintenance.StorageMaintenanceService;
import ph.edu.eac.filedirectory.maintenance.OrphanCleanupEntry;
import ph.edu.eac.filedirectory.maintenance.OrphanCleanupEntryRepository;
import ph.edu.eac.filedirectory.maintenance.OrphanCleanupStatus;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.Role;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Admin-only storage integrity reports, exports, and explicitly reviewed cleanup actions. */
@Controller
public class StorageMaintenanceController {

    private final StorageMaintenanceService storageMaintenanceService;
    private final FileRepository fileRepository;
    private final AuditService auditService;
    private final OrphanCleanupEntryRepository orphanCleanupEntryRepository;

    public StorageMaintenanceController(StorageMaintenanceService storageMaintenanceService,
                                        FileRepository fileRepository,
                                        AuditService auditService,
                                        OrphanCleanupEntryRepository orphanCleanupEntryRepository) {
        this.storageMaintenanceService = storageMaintenanceService;
        this.fileRepository = fileRepository;
        this.auditService = auditService;
        this.orphanCleanupEntryRepository = orphanCleanupEntryRepository;
    }

    @GetMapping("/admin/maintenance")
    public String maintenance(Model model) {
        model.addAttribute("report", storageMaintenanceService.scan());
        var cleanupEntries = orphanCleanupEntryRepository.findAllByOrderByScheduledAtDesc();
        model.addAttribute("orphanCleanupEntries", cleanupEntries);
        model.addAttribute("orphanCleanupByPath", cleanupEntries.stream()
                .collect(Collectors.toMap(OrphanCleanupEntry::getStoredPath, Function.identity(), (first, ignored) -> first)));
        return "admin/maintenance";
    }

    /**
     * Phase 2's only mutation: archive a current record whose file is still
     * absent from disk. Historical version findings remain report-only so a
     * missing old version cannot hide a working current document.
     */
    @PostMapping("/admin/maintenance/files/{id}/archive-missing")
    @Transactional
    public String archiveMissingCurrentFile(@PathVariable Long id,
                                            @RequestParam String reason,
                                            @AuthenticationPrincipal EacUserDetails principal,
                                            RedirectAttributes redirectAttributes) {
        AppUser admin = requireAdmin(principal);
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        String cleanReason = reason == null ? "" : reason.trim();

        if (cleanReason.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Give a reason before archiving a missing-file record.");
            return "redirect:/admin/maintenance";
        }
        if (cleanReason.length() > 400) {
            redirectAttributes.addFlashAttribute("errorMessage", "The archive reason must be 400 characters or fewer.");
            return "redirect:/admin/maintenance";
        }
        if (file.getStatus() == FileStatus.ARCHIVED) {
            redirectAttributes.addFlashAttribute("infoMessage", "This file is already archived.");
            return "redirect:/admin/maintenance";
        }
        if (storageMaintenanceService.storedFileExists(file.getFilePath())) {
            redirectAttributes.addFlashAttribute("infoMessage", "The stored file is available again, so no archive action was taken.");
            return "redirect:/admin/maintenance";
        }

        FileStatus previousStatus = file.getStatus();
        String archiveReason = "Storage maintenance: " + cleanReason;
        file.setStatusBeforeArchive(previousStatus);
        file.setStatus(FileStatus.ARCHIVED);
        file.setArchivedBy(admin);
        file.setArchivedAt(java.time.Instant.now());
        file.setArchiveReason(archiveReason);
        fileRepository.save(file);
        auditService.fileArchived(admin, file.getId(), file.getTitle(), previousStatus.name(), archiveReason);

        redirectAttributes.addFlashAttribute("infoMessage", "Archived missing-file record \"" + file.getTitle() + "\".");
        return "redirect:/admin/maintenance";
    }

    /** A backup reference and 30-day retention period are mandatory before any deletion can be considered. */
    @PostMapping("/admin/maintenance/orphans/schedule")
    @Transactional
    public String scheduleOrphanCleanup(@RequestParam String storedPath,
                                        @RequestParam long sizeBytes,
                                        @RequestParam String reason,
                                        @RequestParam String backupReference,
                                        @AuthenticationPrincipal EacUserDetails principal,
                                        RedirectAttributes redirectAttributes) {
        AppUser admin = requireAdmin(principal);
        String cleanPath = storedPath == null ? "" : storedPath.trim();
        String cleanReason = reason == null ? "" : reason.trim();
        String cleanBackupReference = backupReference == null ? "" : backupReference.trim();
        if (cleanPath.isBlank() || cleanReason.isBlank() || cleanBackupReference.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "A path, reason, and verified backup reference are required.");
            return "redirect:/admin/maintenance";
        }
        if (cleanPath.length() > 512 || cleanReason.length() > 400 || cleanBackupReference.length() > 255) {
            redirectAttributes.addFlashAttribute("errorMessage", "One or more cleanup fields exceed the allowed length.");
            return "redirect:/admin/maintenance";
        }
        if (!storageMaintenanceService.isOrphanedStoredFile(cleanPath)) {
            redirectAttributes.addFlashAttribute("infoMessage", "That path is no longer an orphaned stored file, so it was not scheduled.");
            return "redirect:/admin/maintenance";
        }
        if (orphanCleanupEntryRepository.findByStoredPath(cleanPath).isPresent()) {
            redirectAttributes.addFlashAttribute("infoMessage", "This path already has a recorded cleanup decision. Review the cleanup queue below.");
            return "redirect:/admin/maintenance";
        }

        Instant eligibleAt = Instant.now().plus(Duration.ofDays(30));
        OrphanCleanupEntry entry = orphanCleanupEntryRepository.save(
                new OrphanCleanupEntry(cleanPath, Math.max(sizeBytes, 0), admin, eligibleAt, cleanReason, cleanBackupReference));
        auditService.storageOrphanScheduled(admin, entry.getStoredPath(), cleanReason, eligibleAt.toString());
        redirectAttributes.addFlashAttribute("infoMessage", "Scheduled orphan cleanup after the 30-day retention window.");
        return "redirect:/admin/maintenance";
    }

    @PostMapping("/admin/maintenance/orphans/{id}/cancel")
    @Transactional
    public String cancelOrphanCleanup(@PathVariable Long id,
                                      @RequestParam String reason,
                                      @AuthenticationPrincipal EacUserDetails principal,
                                      RedirectAttributes redirectAttributes) {
        AppUser admin = requireAdmin(principal);
        OrphanCleanupEntry entry = orphanCleanupEntryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        String cleanReason = reason == null ? "" : reason.trim();
        if (entry.getStatus() != OrphanCleanupStatus.SCHEDULED || cleanReason.isBlank() || cleanReason.length() > 400) {
            redirectAttributes.addFlashAttribute("errorMessage", "A scheduled cleanup and a reason of up to 400 characters are required to cancel.");
            return "redirect:/admin/maintenance";
        }
        entry.cancel(admin, cleanReason);
        auditService.storageOrphanCancelled(admin, entry.getStoredPath(), cleanReason);
        redirectAttributes.addFlashAttribute("infoMessage", "Cancelled the scheduled orphan cleanup.");
        return "redirect:/admin/maintenance";
    }

    @PostMapping("/admin/maintenance/orphans/{id}/delete")
    @Transactional
    public String deleteEligibleOrphan(@PathVariable Long id,
                                       @RequestParam String confirmation,
                                       @AuthenticationPrincipal EacUserDetails principal,
                                       RedirectAttributes redirectAttributes) {
        AppUser admin = requireAdmin(principal);
        OrphanCleanupEntry entry = orphanCleanupEntryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        if (entry.getStatus() != OrphanCleanupStatus.SCHEDULED || Instant.now().isBefore(entry.getEligibleAt())) {
            redirectAttributes.addFlashAttribute("errorMessage", "This cleanup is not yet eligible for deletion.");
            return "redirect:/admin/maintenance";
        }
        if (!"DELETE".equals(confirmation)) {
            redirectAttributes.addFlashAttribute("errorMessage", "Type DELETE to confirm the permanent file deletion.");
            return "redirect:/admin/maintenance";
        }
        if (!storageMaintenanceService.deleteOrphanedStoredFile(entry.getStoredPath())) {
            entry.cancel(admin, "Cancelled automatically: the path was missing already or gained a database reference.");
            auditService.storageOrphanCancelled(admin, entry.getStoredPath(), entry.getCompletionNote());
            redirectAttributes.addFlashAttribute("infoMessage", "The path was not deleted because it was no longer an eligible orphan.");
            return "redirect:/admin/maintenance";
        }
        entry.complete(admin, "Deleted after retention period. Backup reference: " + entry.getBackupReference());
        auditService.storageOrphanDeleted(admin, entry.getStoredPath(), entry.getReason());
        redirectAttributes.addFlashAttribute("infoMessage", "Deleted the reviewed orphaned disk file.");
        return "redirect:/admin/maintenance";
    }

    @GetMapping("/admin/maintenance/export/{reportType}")
    public ResponseEntity<ByteArrayResource> export(@PathVariable String reportType) {
        StorageMaintenanceReport report = storageMaintenanceService.scan();
        String csv = switch (reportType) {
            case "missing" -> missingCsv(report);
            case "orphaned" -> orphanedCsv(report);
            case "duplicates" -> duplicatesCsv(report);
            case "largest" -> largestCsv(report);
            case "departments" -> departmentsCsv(report);
            default -> throw new ResponseStatusException(NOT_FOUND);
        };
        byte[] bytes = ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("storage-maintenance-" + reportType + ".csv").build().toString())
                .body(new ByteArrayResource(bytes));
    }

    private String missingCsv(StorageMaintenanceReport report) {
        StringBuilder csv = new StringBuilder("Record type,File ID,Version,Title,Stored path\n");
        report.missingStoredFiles().forEach(item -> row(csv, item.recordType(), item.fileId(), item.versionNumber(), item.title(), item.relativePath()));
        return csv.toString();
    }

    private String orphanedCsv(StorageMaintenanceReport report) {
        StringBuilder csv = new StringBuilder("Stored path,Size bytes\n");
        report.orphanedStoredFiles().forEach(item -> row(csv, item.relativePath(), item.size()));
        return csv.toString();
    }

    private String duplicatesCsv(StorageMaintenanceReport report) {
        StringBuilder csv = new StringBuilder("Checksum,Distinct physical files,Record type,File ID,Version,Title,Stored path\n");
        report.duplicateChecksums().forEach(group -> group.references().forEach(item ->
                row(csv, group.checksum(), group.distinctPhysicalFileCount(), item.recordType(), item.fileId(),
                        item.versionNumber(), item.title(), item.relativePath())));
        return csv.toString();
    }

    private String departmentsCsv(StorageMaintenanceReport report) {
        StringBuilder csv = new StringBuilder("Department,Stored files,Storage bytes\n");
        report.departmentStorage().forEach(item -> row(csv, item.departmentCode(), item.fileCount(), item.storageBytes()));
        return csv.toString();
    }

    private String largestCsv(StorageMaintenanceReport report) {
        StringBuilder csv = new StringBuilder("Stored path,Size bytes\n");
        report.largestStoredFiles().forEach(item -> row(csv, item.relativePath(), item.size()));
        return csv.toString();
    }

    private void row(StringBuilder csv, Object... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                csv.append(',');
            }
            csv.append(escape(values[index] == null ? "" : String.valueOf(values[index])));
        }
        csv.append('\n');
    }

    /** Prefix formula-like values so spreadsheet programs do not evaluate report data as a formula. */
    private String escape(String value) {
        String safe = value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private AppUser requireAdmin(EacUserDetails principal) {
        if (principal == null || principal.getAppUser().getRole() != Role.ADMIN) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        }
        return principal.getAppUser();
    }
}
