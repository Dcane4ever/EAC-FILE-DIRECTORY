package ph.edu.eac.filedirectory.web;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.eac.filedirectory.maintenance.StorageMaintenanceReport;
import ph.edu.eac.filedirectory.maintenance.StorageMaintenanceService;

import java.nio.charset.StandardCharsets;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Admin-only, read-only storage integrity reports and CSV exports. */
@Controller
public class StorageMaintenanceController {

    private final StorageMaintenanceService storageMaintenanceService;

    public StorageMaintenanceController(StorageMaintenanceService storageMaintenanceService) {
        this.storageMaintenanceService = storageMaintenanceService;
    }

    @GetMapping("/admin/maintenance")
    public String maintenance(Model model) {
        model.addAttribute("report", storageMaintenanceService.scan());
        return "admin/maintenance";
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
}
