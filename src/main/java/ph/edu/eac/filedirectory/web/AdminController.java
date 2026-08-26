package ph.edu.eac.filedirectory.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ph.edu.eac.filedirectory.audit.AuditService;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileSearchCriteria;
import ph.edu.eac.filedirectory.file.FileSortOption;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.file.FileSpecifications;
import ph.edu.eac.filedirectory.file.FileTypeValidator;
import ph.edu.eac.filedirectory.notification.NotificationService;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.taxonomy.CategoryRepository;
import ph.edu.eac.filedirectory.taxonomy.Department;
import ph.edu.eac.filedirectory.taxonomy.DepartmentIcons;
import ph.edu.eac.filedirectory.taxonomy.DepartmentRepository;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.Role;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Moderator/admin approval queue - see SecurityConfig, /admin/** requires
 * ROLE_MODERATOR or ROLE_ADMIN. Pending uploads are grouped by department
 * (every real department always gets a card, even with 0 pending, so the
 * auto-approve toggle stays reachable) and can be:
 *  - sorted by how many are waiting in each (highest/lowest volume)
 *  - filtered down to one department (sidebar click, ?department=ID)
 *  - searched by department name (?q=..., server-side, case-insensitive)
 *  - paged (?page=, ?size= - "items per page")
 * Each department also has an auto-approve toggle (ADMIN only - see
 * SecurityConfig) that makes future uploads to it skip this queue entirely
 * - see UploadController.
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final List<Integer> PAGE_SIZE_OPTIONS = List.of(5, 10, 20, 50);

    private final FileRepository fileRepository;
    private final DepartmentRepository departmentRepository;
    private final CategoryRepository categoryRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;

    public AdminController(FileRepository fileRepository, DepartmentRepository departmentRepository,
                            CategoryRepository categoryRepository,
                            NotificationService notificationService, AuditService auditService) {
        this.fileRepository = fileRepository;
        this.departmentRepository = departmentRepository;
        this.categoryRepository = categoryRepository;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    public record DepartmentGroup(Department department, List<FileEntity> files, String icon) {
    }

    @GetMapping("/queue")
    public String queue(@RequestParam(defaultValue = "highest") String sort,
                         @RequestParam(required = false) Long department,
                         @RequestParam(required = false) String q,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
                         Model model) {
        List<FileEntity> pending = fileRepository.findByStatusOrderByCreatedAtAsc(FileStatus.PENDING);

        Map<Department, List<FileEntity>> byDepartment = pending.stream()
                .collect(Collectors.groupingBy(FileEntity::getDepartment, LinkedHashMap::new, Collectors.toList()));

        Comparator<DepartmentGroup> byVolume = Comparator.comparingInt(g -> g.files().size());
        if ("highest".equals(sort)) {
            byVolume = byVolume.reversed();
        }

        // Every real department gets a card - including ones with zero
        // pending files - so the auto-approve toggle is always reachable,
        // not only when something happens to be waiting in it right now.
        List<Department> allDepartments = departmentRepository.findAll();
        List<DepartmentGroup> allGroups = allDepartments.stream()
                .map(dept -> new DepartmentGroup(dept, byDepartment.getOrDefault(dept, List.of()), DepartmentIcons.forCode(dept.getCode())))
                .sorted(byVolume.thenComparing(g -> g.department().getName()))
                .collect(Collectors.toList());

        // Sidebar department filter (?department=ID) - narrows to one department's card.
        List<DepartmentGroup> filtered = allGroups;
        if (department != null) {
            filtered = allGroups.stream()
                    .filter(g -> g.department().getId().equals(department))
                    .collect(Collectors.toList());
        }

        // Department name search (?q=...) - server-side, case-insensitive substring match.
        String query = q == null ? "" : q.trim();
        if (!query.isBlank()) {
            String needle = query.toLowerCase();
            filtered = filtered.stream()
                    .filter(g -> g.department().getName().toLowerCase().contains(needle))
                    .collect(Collectors.toList());
        }

        // Items-per-page over the (filtered) department list itself.
        int pageSize = PAGE_SIZE_OPTIONS.contains(size) ? size : DEFAULT_PAGE_SIZE;
        int totalDepartments = filtered.size();
        int totalPages = Math.max(1, (int) Math.ceil(totalDepartments / (double) pageSize));
        int currentPage = Math.max(0, Math.min(page, totalPages - 1));
        int fromIndex = Math.min(currentPage * pageSize, totalDepartments);
        int toIndex = Math.min(fromIndex + pageSize, totalDepartments);
        List<DepartmentGroup> pageOfGroups = filtered.subList(fromIndex, toIndex);

        model.addAttribute("allDepartments", allDepartments);
        model.addAttribute("departmentCounts", byDepartment.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().getId(), e -> e.getValue().size())));
        model.addAttribute("groups", pageOfGroups);
        model.addAttribute("totalPending", pending.size());
        model.addAttribute("totalDepartments", totalDepartments);
        model.addAttribute("sort", sort);
        model.addAttribute("selectedDepartment", department);
        model.addAttribute("q", q);
        model.addAttribute("page", currentPage);
        model.addAttribute("size", pageSize);
        model.addAttribute("pageSizeOptions", PAGE_SIZE_OPTIONS);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("fromIndex", totalDepartments == 0 ? 0 : fromIndex + 1);
        model.addAttribute("toIndex", toIndex);
        return "admin/queue";
    }

    @GetMapping("/files")
    public String files(@RequestParam(required = false) FileStatus status,
                        @RequestParam(required = false) Long departmentId,
                        @RequestParam(required = false) Long categoryId,
                        @RequestParam(required = false) String fileType,
                        @RequestParam(required = false) String q,
                        @RequestParam(required = false) String sort,
                        @RequestParam(defaultValue = "0") int page,
                        Model model) {
        String trimmedQuery = q != null && !q.isBlank() ? q.trim() : null;
        FileSearchCriteria criteria = new FileSearchCriteria(
                trimmedQuery, departmentId, null, null, categoryId, null, null, fileType, null);
        FileSortOption sortOption = FileSortOption.fromParam(sort);
        Pageable pageable = PageRequest.of(page, 20, sortOption.toSort());
        List<FileStatus> statuses = status == null ? Arrays.asList(FileStatus.values()) : List.of(status);
        Page<FileEntity> files = fileRepository.findAll(FileSpecifications.matching(statuses, criteria), pageable);

        model.addAttribute("files", files);
        model.addAttribute("status", status);
        model.addAttribute("statuses", FileStatus.values());
        model.addAttribute("departments", departmentRepository.findAll(Sort.by("name")));
        model.addAttribute("categories", categoryRepository.findAll(Sort.by("name")));
        model.addAttribute("selectedDepartmentId", departmentId);
        model.addAttribute("selectedCategoryId", categoryId);
        model.addAttribute("fileType", fileType);
        model.addAttribute("fileTypeOptions", FileTypeValidator.ALLOWED_EXTENSIONS.stream().sorted().toList());
        model.addAttribute("q", q);
        model.addAttribute("sort", sortOption.name());
        model.addAttribute("sortOptions", FileSortOption.values());
        return "admin/files";
    }

    @GetMapping("/archived")
    public String archived(@RequestParam(defaultValue = "0") int page, Model model) {
        Pageable pageable = PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "archivedAt"));
        Page<FileEntity> files = fileRepository.findByStatus(FileStatus.ARCHIVED, pageable);
        model.addAttribute("files", files);
        return "admin/archived";
    }

    @PostMapping("/departments/{id}/auto-approve")
    public String toggleAutoApprove(@PathVariable Long id,
                                     @RequestParam boolean enabled,
                                     @AuthenticationPrincipal EacUserDetails principal,
                                     RedirectAttributes redirectAttributes) {
        requireAdmin(principal);
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        department.setAutoApprove(enabled);
        departmentRepository.save(department);

        auditService.departmentAutoApproveToggled(principal.getAppUser(), department.getId(), department.getName(), enabled);

        redirectAttributes.addFlashAttribute("infoMessage",
                "Auto-approve " + (enabled ? "enabled" : "disabled") + " for " + department.getName() + ".");
        return "redirect:/admin/queue";
    }

    @PostMapping("/files/{id}/approve")
    @Transactional
    public String approve(@PathVariable Long id, @AuthenticationPrincipal EacUserDetails principal, RedirectAttributes redirectAttributes) {
        AppUser moderator = requireModerator(principal);
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        approveOne(file, moderator);

        redirectAttributes.addFlashAttribute("infoMessage", "Approved \"" + file.getTitle() + "\".");
        return "redirect:/admin/queue";
    }

    @PostMapping("/files/{id}/reject")
    @Transactional
    public String reject(@PathVariable Long id,
                          @RequestParam(required = false) String reason,
                          @AuthenticationPrincipal EacUserDetails principal,
                          RedirectAttributes redirectAttributes) {
        AppUser moderator = requireModerator(principal);
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        rejectOne(file, moderator, reason);

        redirectAttributes.addFlashAttribute("infoMessage", "Rejected \"" + file.getTitle() + "\".");
        return "redirect:/admin/queue";
    }

    @PostMapping("/files/{id}/archive")
    @Transactional
    public String archive(@PathVariable Long id,
                          @RequestParam(required = false) String reason,
                          @AuthenticationPrincipal EacUserDetails principal,
                          RedirectAttributes redirectAttributes) {
        AppUser moderator = requireModerator(principal);
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (file.getStatus() != FileStatus.ARCHIVED) {
            FileStatus previousStatus = file.getStatus();
            String archiveReason = reason == null || reason.isBlank() ? "No reason given" : reason.trim();
            file.setStatusBeforeArchive(previousStatus);
            file.setStatus(FileStatus.ARCHIVED);
            file.setArchivedBy(moderator);
            file.setArchivedAt(Instant.now());
            file.setArchiveReason(archiveReason);
            fileRepository.save(file);
            auditService.fileArchived(moderator, file.getId(), file.getTitle(), previousStatus.name(), archiveReason);
        }

        redirectAttributes.addFlashAttribute("infoMessage", "Archived \"" + file.getTitle() + "\".");
        return "redirect:/files/" + file.getId();
    }

    @PostMapping("/files/{id}/restore")
    @Transactional
    public String restore(@PathVariable Long id,
                          @AuthenticationPrincipal EacUserDetails principal,
                          RedirectAttributes redirectAttributes) {
        AppUser moderator = requireModerator(principal);
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (file.getStatus() == FileStatus.ARCHIVED) {
            FileStatus restoredStatus = file.getStatusBeforeArchive() == null
                    ? FileStatus.APPROVED
                    : file.getStatusBeforeArchive();
            file.setStatus(restoredStatus);
            file.setStatusBeforeArchive(null);
            file.setArchivedBy(null);
            file.setArchivedAt(null);
            file.setArchiveReason(null);
            fileRepository.save(file);
            auditService.fileRestored(moderator, file.getId(), file.getTitle(), restoredStatus.name());
        }

        redirectAttributes.addFlashAttribute("infoMessage", "Restored \"" + file.getTitle() + "\".");
        return "redirect:/files/" + file.getId();
    }

    /**
     * Bulk approve - see admin/queue.html's checkbox selection UI. Each file
     * still gets its own full moderation record (approvedBy/approvedAt),
     * its own audit event, and its own notification to the uploader - see
     * approveOne(), the exact same per-file logic the single-file endpoint
     * above uses, just looped. Authorization is checked once for the acting
     * moderator (a single role, not per-file - there's no per-file
     * ownership concept for moderation, see requireModerator's own comment),
     * but every file ID is still individually looked up and validated to
     * exist and be PENDING before acting on it - a stale or already-decided
     * ID in the submitted set is silently skipped, not treated as an error
     * that aborts the whole batch.
     */
    @PostMapping("/files/bulk-approve")
    @Transactional
    public String bulkApprove(@RequestParam(required = false) List<Long> fileIds,
                               @AuthenticationPrincipal EacUserDetails principal,
                               RedirectAttributes redirectAttributes) {
        AppUser moderator = requireModerator(principal);

        if (fileIds == null || fileIds.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "No files were selected.");
            return "redirect:/admin/queue";
        }

        int approved = 0;
        for (Long id : fileIds) {
            FileEntity file = fileRepository.findById(id).orElse(null);
            if (file == null || file.getStatus() != FileStatus.PENDING) {
                // Already acted on (e.g. by another moderator) or deleted
                // since the page loaded - skip rather than fail the batch.
                continue;
            }
            approveOne(file, moderator);
            approved++;
        }

        redirectAttributes.addFlashAttribute("infoMessage",
                approved == 0 ? "No files were approved - they may have already been reviewed."
                        : "Approved " + approved + " file(s).");
        return "redirect:/admin/queue";
    }

    /** Bulk reject counterpart to bulkApprove() - see that method's comment. One shared reason applies to every file in the batch. */
    @PostMapping("/files/bulk-reject")
    @Transactional
    public String bulkReject(@RequestParam(required = false) List<Long> fileIds,
                              @RequestParam(required = false) String reason,
                              @AuthenticationPrincipal EacUserDetails principal,
                              RedirectAttributes redirectAttributes) {
        AppUser moderator = requireModerator(principal);

        if (fileIds == null || fileIds.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "No files were selected.");
            return "redirect:/admin/queue";
        }

        int rejected = 0;
        for (Long id : fileIds) {
            FileEntity file = fileRepository.findById(id).orElse(null);
            if (file == null || file.getStatus() != FileStatus.PENDING) {
                continue;
            }
            rejectOne(file, moderator, reason);
            rejected++;
        }

        redirectAttributes.addFlashAttribute("infoMessage",
                rejected == 0 ? "No files were rejected - they may have already been reviewed."
                        : "Rejected " + rejected + " file(s).");
        return "redirect:/admin/queue";
    }

    private void approveOne(FileEntity file, AppUser moderator) {
        file.setStatus(FileStatus.APPROVED);
        file.setApprovedBy(moderator);
        file.setApprovedAt(Instant.now());
        file.setRejectionReason(null);
        fileRepository.save(file);

        notificationService.uploadApproved(file.getUploader(), file.getTitle(), file.getId());
        auditService.fileApproved(moderator, file.getId(), file.getTitle());
    }

    private void rejectOne(FileEntity file, AppUser moderator, String reason) {
        file.setStatus(FileStatus.REJECTED);
        file.setApprovedBy(moderator);
        file.setApprovedAt(Instant.now());
        String rejectionReason = reason == null || reason.isBlank() ? "No reason given" : reason.trim();
        file.setRejectionReason(rejectionReason);
        fileRepository.save(file);

        notificationService.uploadRejected(file.getUploader(), file.getTitle(), file.getId(), rejectionReason);
        auditService.fileRejected(moderator, file.getId(), file.getTitle(), rejectionReason);
    }

    /**
     * Real enforcement of MODERATOR/ADMIN-only access to this controller is
     * SecurityConfig's URL matcher (everything under /admin/** requires
     * hasAnyRole("ADMIN", "MODERATOR")) - this check exists as defense in
     * depth so approve/reject can't silently run for a null principal, and
     * now actually verifies the role too rather than only checking
     * non-null, which is all it did before.
     */
    private AppUser requireModerator(EacUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        AppUser user = principal.getAppUser();
        if (user.getRole() != Role.MODERATOR && user.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return user;
    }

    /**
     * Defense-in-depth for the auto-approve toggle, matching requireModerator's
     * pattern - real enforcement is still SecurityConfig's stricter
     * /admin/departments/** -> hasRole("ADMIN") matcher (this endpoint is
     * intentionally ADMIN-only, unlike approve/reject which any moderator
     * can do), this is just a code-level backstop in case that matcher is
     * ever loosened or a new URL alias bypasses it.
     */
    private AppUser requireAdmin(EacUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        AppUser user = principal.getAppUser();
        if (user.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return user;
    }
}
