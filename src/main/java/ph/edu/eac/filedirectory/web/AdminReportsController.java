package ph.edu.eac.filedirectory.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ph.edu.eac.filedirectory.access.AccessRequestRepository;
import ph.edu.eac.filedirectory.access.AccessRequestStatus;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.taxonomy.DepartmentRepository;
import ph.edu.eac.filedirectory.user.AppUserRepository;

import java.text.DecimalFormat;
import java.util.EnumMap;
import java.util.Map;

@Controller
public class AdminReportsController {

    private final FileRepository fileRepository;
    private final AccessRequestRepository accessRequestRepository;
    private final DepartmentRepository departmentRepository;
    private final AppUserRepository userRepository;

    public AdminReportsController(FileRepository fileRepository,
                                  AccessRequestRepository accessRequestRepository,
                                  DepartmentRepository departmentRepository,
                                  AppUserRepository userRepository) {
        this.fileRepository = fileRepository;
        this.accessRequestRepository = accessRequestRepository;
        this.departmentRepository = departmentRepository;
        this.userRepository = userRepository;
    }

    @GetMapping("/admin/reports")
    public String reports(Model model) {
        Map<FileStatus, Long> statusCounts = new EnumMap<>(FileStatus.class);
        for (FileStatus status : FileStatus.values()) {
            statusCounts.put(status, 0L);
        }
        fileRepository.countFilesByStatus()
                .forEach(row -> statusCounts.put(row.getStatus(), row.getFileCount()));

        long totalFiles = statusCounts.values().stream().mapToLong(Long::longValue).sum();
        long totalDownloads = fileRepository.sumDownloadCount();
        long totalStorageBytes = fileRepository.sumFileSize();
        long pendingRequests = accessRequestRepository.countByStatus(AccessRequestStatus.PENDING);

        model.addAttribute("totalFiles", totalFiles);
        model.addAttribute("approvedFiles", statusCounts.get(FileStatus.APPROVED));
        model.addAttribute("pendingFiles", statusCounts.get(FileStatus.PENDING));
        model.addAttribute("rejectedFiles", statusCounts.get(FileStatus.REJECTED));
        model.addAttribute("archivedFiles", statusCounts.get(FileStatus.ARCHIVED));
        model.addAttribute("totalDownloads", totalDownloads);
        model.addAttribute("pendingRequests", pendingRequests);
        model.addAttribute("approvedRequests", accessRequestRepository.countByStatus(AccessRequestStatus.APPROVED));
        model.addAttribute("deniedRequests", accessRequestRepository.countByStatus(AccessRequestStatus.DENIED));
        model.addAttribute("expiredRequests", accessRequestRepository.countByStatus(AccessRequestStatus.EXPIRED));
        model.addAttribute("departmentCount", departmentRepository.count());
        model.addAttribute("autoApproveCount", departmentRepository.countByAutoApproveTrue());
        model.addAttribute("userCount", userRepository.count());
        model.addAttribute("storageUsed", formatBytes(totalStorageBytes));
        model.addAttribute("topFiles", fileRepository.findByStatusOrderByDownloadCountDescCreatedAtDesc(FileStatus.APPROVED, PageRequest.of(0, 8)));
        model.addAttribute("departmentBreakdown", fileRepository.countFilesByDepartment(PageRequest.of(0, 8)));
        model.addAttribute("categoryBreakdown", fileRepository.countFilesByCategory(PageRequest.of(0, 8)));
        return "admin/reports";
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value = value / 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return new DecimalFormat(value >= 10 ? "0.#" : "0.##").format(value) + " " + units[unit];
    }
}
