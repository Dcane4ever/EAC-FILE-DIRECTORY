package ph.edu.eac.filedirectory.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.taxonomy.Department;
import ph.edu.eac.filedirectory.taxonomy.DepartmentIcons;
import ph.edu.eac.filedirectory.taxonomy.DepartmentRepository;
import ph.edu.eac.filedirectory.user.AppUser;

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

    public AdminController(FileRepository fileRepository, DepartmentRepository departmentRepository) {
        this.fileRepository = fileRepository;
        this.departmentRepository = departmentRepository;
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

    @PostMapping("/departments/{id}/auto-approve")
    public String toggleAutoApprove(@PathVariable Long id,
                                     @RequestParam boolean enabled,
                                     RedirectAttributes redirectAttributes) {
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        department.setAutoApprove(enabled);
        departmentRepository.save(department);

        redirectAttributes.addFlashAttribute("infoMessage",
                "Auto-approve " + (enabled ? "enabled" : "disabled") + " for " + department.getName() + ".");
        return "redirect:/admin/queue";
    }

    @PostMapping("/files/{id}/approve")
    public String approve(@PathVariable Long id, @AuthenticationPrincipal EacUserDetails principal, RedirectAttributes redirectAttributes) {
        AppUser moderator = requireModerator(principal);
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        file.setStatus(FileStatus.APPROVED);
        file.setApprovedBy(moderator);
        file.setApprovedAt(Instant.now());
        file.setRejectionReason(null);
        fileRepository.save(file);

        redirectAttributes.addFlashAttribute("infoMessage", "Approved \"" + file.getTitle() + "\".");
        return "redirect:/admin/queue";
    }

    @PostMapping("/files/{id}/reject")
    public String reject(@PathVariable Long id,
                          @RequestParam(required = false) String reason,
                          @AuthenticationPrincipal EacUserDetails principal,
                          RedirectAttributes redirectAttributes) {
        AppUser moderator = requireModerator(principal);
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        file.setStatus(FileStatus.REJECTED);
        file.setApprovedBy(moderator);
        file.setApprovedAt(Instant.now());
        file.setRejectionReason(reason == null || reason.isBlank() ? "No reason given" : reason.trim());
        fileRepository.save(file);

        redirectAttributes.addFlashAttribute("infoMessage", "Rejected \"" + file.getTitle() + "\".");
        return "redirect:/admin/queue";
    }

    private AppUser requireModerator(EacUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return principal.getAppUser();
    }
}
