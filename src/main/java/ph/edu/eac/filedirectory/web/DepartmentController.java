package ph.edu.eac.filedirectory.web;

import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.follow.FollowService;
import ph.edu.eac.filedirectory.follow.FollowType;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.taxonomy.Department;
import ph.edu.eac.filedirectory.taxonomy.DepartmentRepository;
import ph.edu.eac.filedirectory.user.AppUser;

import java.util.List;

@Controller
public class DepartmentController {

    private final DepartmentRepository departmentRepository;
    private final FileRepository fileRepository;
    private final FollowService followService;

    public DepartmentController(DepartmentRepository departmentRepository, FileRepository fileRepository,
                                FollowService followService) {
        this.departmentRepository = departmentRepository;
        this.fileRepository = fileRepository;
        this.followService = followService;
    }

    @GetMapping("/departments")
    public String departments(@AuthenticationPrincipal EacUserDetails principal, Model model) {
        AppUser currentUser = principal != null ? principal.getAppUser() : null;
        List<DepartmentRow> departments = departmentRepository.findAll(Sort.by("name")).stream()
                .map(department -> DepartmentRow.from(department, fileRepository, followService, currentUser))
                .toList();

        model.addAttribute("departments", departments);
        model.addAttribute("totalDepartments", departments.size());
        model.addAttribute("totalApprovedFiles", departments.stream().mapToLong(DepartmentRow::approvedFileCount).sum());
        return "departments";
    }

    public record DepartmentRow(Long id, String code, String name, long approvedFileCount, boolean following) {
        static DepartmentRow from(Department department, FileRepository fileRepository, FollowService followService,
                                  AppUser currentUser) {
            return new DepartmentRow(
                    department.getId(),
                    department.getCode(),
                    department.getName(),
                    fileRepository.countByStatusAndDepartmentId(FileStatus.APPROVED, department.getId()),
                    followService.isFollowing(currentUser, FollowType.DEPARTMENT, department.getId())
            );
        }
    }
}
